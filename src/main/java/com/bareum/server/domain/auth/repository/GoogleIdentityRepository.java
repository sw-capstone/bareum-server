package com.bareum.server.domain.auth.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class GoogleIdentityRepository {
	private final JdbcTemplate jdbc;

	public void lockSubject(String subject) {
		jdbc.execute("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
				(PreparedStatementCallback<Void>)statement -> {
					statement.setString(1, "bareum:google:" + subject);
					statement.execute();
					return null;
				});
	}

	public void replaceProof(String hash, String subject, String email, String name, Instant expiry, Instant now) {
		jdbc.update("DELETE FROM google_signup_proof WHERE google_subject=?", subject);
		jdbc.update("""
				INSERT INTO google_signup_proof(token_hash,google_subject,email,name,expires_at,created_at)
				VALUES(?,?,?,?,?,?)
				""", hash, subject, email, name, Timestamp.from(expiry), Timestamp.from(now));
	}

	public void deleteExpiredProofs(Instant now) {
		jdbc.update("DELETE FROM google_signup_proof WHERE expires_at<=?", Timestamp.from(now));
	}

	public Optional<String> findProofSubject(String hash) {
		return jdbc.query("SELECT google_subject FROM google_signup_proof WHERE token_hash=?",
				(rs, row) -> rs.getString("google_subject"), hash).stream().findFirst();
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<SignupProof> findProofForUpdate(String hash) {
		return jdbc.query("""
				SELECT google_subject,email,name,expires_at,consumed_at
				FROM google_signup_proof WHERE token_hash=? FOR UPDATE
				""", (rs, row) -> {
			Timestamp consumedAt = rs.getTimestamp("consumed_at");
			return new SignupProof(rs.getString("google_subject"), rs.getString("email"),
					rs.getString("name"), rs.getTimestamp("expires_at").toInstant(), consumedAt != null);
		}, hash).stream().findFirst();
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public boolean consumeProof(String hash, Instant now) {
		Timestamp consumedAt = Timestamp.from(now);
		return jdbc.update("""
				UPDATE google_signup_proof SET consumed_at=?
				WHERE token_hash=? AND consumed_at IS NULL AND expires_at>?
				""", consumedAt, hash, consumedAt) == 1;
	}

	public record SignupProof(
		String subject,
		String email,
		String name,
		Instant expiresAt,
		boolean consumed
	) {
		@Override
		public String toString() {
			return "GoogleSignupProof[redacted]";
		}
	}
}
