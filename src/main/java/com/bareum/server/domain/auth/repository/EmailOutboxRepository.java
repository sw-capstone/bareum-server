package com.bareum.server.domain.auth.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.bareum.server.domain.auth.config.EmailOutboxProperties;
import com.bareum.server.domain.auth.config.EmailVerificationProperties;
import com.bareum.server.domain.auth.entity.EmailVerification;
import com.bareum.server.domain.auth.enums.EmailOutboxState;
import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class EmailOutboxRepository {
	private static final Set<EmailOutboxState> ACTIVE_STATES =
			Set.of(EmailOutboxState.QUEUED, EmailOutboxState.SENDING, EmailOutboxState.RETRY);
	private static final Set<EmailOutboxState> TERMINAL_STATES =
			Set.of(EmailOutboxState.FAILED, EmailOutboxState.CANCELLED);
	private final JdbcTemplate jdbc;
	private final EmailOutboxQueryRepository queries;
	private final EmailVerificationSendLogRepository sendLogs;

	public void checkCanQueue(String email, Instant now, EmailVerificationProperties policy) {
		boolean blocked = queries.hasPendingOrRecentDelivery(email, ACTIVE_STATES,
				now.minusSeconds(policy.getMinimumSendIntervalSeconds()));
		if (blocked
				|| reservedAndSent(email, now.minusSeconds(3600)) >= policy.getMaxSendsPerHour()
				|| reservedAndSent(email, now.minusSeconds(86400)) >= policy.getMaxSendsPerDay()) {
			throw new AuthException(AuthErrorCode.EMAIL_SEND_LIMIT_EXCEEDED);
		}
	}

	private long reservedAndSent(String email, Instant since) {
		return queries.countByEmailIgnoreCaseAndQueuedAtGreaterThanEqualAndStateNot(email, since,
				EmailOutboxState.SENT)
				+ sendLogs.countByEmailIgnoreCaseAndSentAtGreaterThanEqual(email, since);
	}

	public void enqueue(
		UUID id,
		EmailVerification verification,
		byte[] encrypted,
		String keyId,
		long ttl,
		Instant now
	) {
		jdbc.update("""
				INSERT INTO email_verification_outbox (id, verification_id, email, purpose, code_hash,
				    encrypted_code, key_id, code_ttl_seconds, state, queued_at, next_attempt_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?, ?)
				""", id, verification.getId(), verification.getEmail(), verification.getPurpose().name(),
				verification.getCodeHash(), encrypted, keyId, ttl, timestamp(now), timestamp(now), timestamp(now));
	}

	public boolean hasTerminalDelivery(EmailVerification verification) {
		return queries.existsByVerification_IdAndCodeHashAndStateIn(verification.getId(),
				verification.getCodeHash(), TERMINAL_STATES);
	}

	public Optional<Job> claim(Instant now, EmailOutboxProperties policy) {
		// Expired leases are recoverable; a live lease is never reclaimed or erased.
		jdbc.update("""
				UPDATE email_verification_outbox SET state='FAILED', encrypted_code=NULL,
				    lease_token=NULL, lease_until=NULL, updated_at=?
				WHERE state IN ('QUEUED','RETRY','SENDING')
				    AND (state <> 'SENDING' OR lease_until <= ?)
				    AND (attempt_count >= ? OR queued_at <= ?)
				""", timestamp(now), timestamp(now), policy.maxAttempts(),
				timestamp(now.minusSeconds(policy.maxQueueAgeSeconds())));
		return jdbc
				.query("""
						UPDATE email_verification_outbox SET state='SENDING', lease_token=?, lease_until=?,
						    attempt_count=attempt_count+1, updated_at=?
						WHERE id = (SELECT id FROM email_verification_outbox
						    WHERE (state IN ('QUEUED','RETRY') AND next_attempt_at <= ?)
						       OR (state='SENDING' AND lease_until <= ?)
						    ORDER BY queued_at, id FOR UPDATE SKIP LOCKED LIMIT 1)
						RETURNING *
						""", this::map, UUID.randomUUID(), timestamp(now.plusSeconds(policy.leaseSeconds())),
						timestamp(now), timestamp(now), timestamp(now))
				.stream()
				.findFirst();
	}

	public boolean lockOwned(Job job) {
		return !jdbc.query("""
				SELECT id FROM email_verification_outbox
				    WHERE id=? AND state='SENDING' AND lease_token=? FOR UPDATE
				""", (rs, row) -> rs.getObject(1), job.id(), job.leaseToken()).isEmpty();
	}

	public void finish(Job job, String state, Instant now) {
		jdbc.update("""
				UPDATE email_verification_outbox SET state=?, encrypted_code=NULL,
				    lease_token=NULL, lease_until=NULL, updated_at=?, accepted_at=?
				    WHERE id=? AND state='SENDING' AND lease_token=?
				""", state, timestamp(now), "SENT".equals(state) ? timestamp(now) : null,
				job.id(), job.leaseToken());
	}

	public void retry(Job job, Instant now, EmailOutboxProperties policy) {
		if (job.attemptCount() >= policy.maxAttempts()
				|| !now.isBefore(job.queuedAt().plusSeconds(policy.maxQueueAgeSeconds()))) {
			finish(job, "FAILED", now);
		} else {
			jdbc.update("""
					UPDATE email_verification_outbox SET state='RETRY', lease_token=NULL, lease_until=NULL,
					    next_attempt_at=?, updated_at=? WHERE id=? AND state='SENDING' AND lease_token=?
					""", timestamp(now.plusSeconds(policy.retryDelaySeconds())), timestamp(now),
					job.id(),
					job.leaseToken());
		}
	}

	public void recordSent(Job job, Instant acceptedAt) {
		jdbc.update("""
				INSERT INTO email_verification_send_log (email,purpose,sent_at,outbox_id)
				    VALUES (?,?,?,?)
				""", job.email(), job.purpose().name(), timestamp(acceptedAt), job.id());
	}

	private Job map(ResultSet rs, int row) throws SQLException {
		return new Job(rs.getObject("id", UUID.class), rs.getLong("verification_id"),
				rs.getString("email"),
				VerificationPurpose.valueOf(rs.getString("purpose")),
				rs.getString("code_hash"),
				rs.getBytes("encrypted_code"), rs.getString("key_id"),
				rs.getLong("code_ttl_seconds"),
				rs.getInt("attempt_count"),
				rs.getTimestamp("queued_at").toInstant(),
				rs.getObject("lease_token", UUID.class));
	}

	private static Timestamp timestamp(Instant instant) {
		return Timestamp.from(instant);
	}

	public record Job(
		UUID id,
		long verificationId,
		String email,
		VerificationPurpose purpose,
		String codeHash,
		byte[] encryptedCode,
		String keyId,
		long ttlSeconds,
		int attemptCount,
		Instant queuedAt,
		UUID leaseToken
	) {
		@Override
		public String toString() {
			return "EmailOutboxJob[id=" + id + ", payload=REDACTED]";
		}
	}
}
