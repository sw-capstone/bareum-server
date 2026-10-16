package com.bareum.server.domain.auth.entity;

import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(name = "email_verification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmailVerification extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false)
	private Long id;

	@Column(name = "email", nullable = false, length = 255)
	private String email;

	@Enumerated(EnumType.STRING)
	@Column(name = "purpose", nullable = false, length = 30)
	private VerificationPurpose purpose;

	@Column(name = "code_hash", nullable = false, length = 255)
	private String codeHash;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "attempt_count", nullable = false)
	@ColumnDefault("0")
	private int attemptCount;

	@Column(name = "attempts_blocked_until")
	private Instant attemptsBlockedUntil;

	@Column(name = "last_sent_at", nullable = true)
	private Instant lastSentAt;

	@Column(name = "verified_at", nullable = true)
	private Instant verifiedAt;

	@Column(name = "verification_token_hash", nullable = true, length = 255)
	private String verificationTokenHash;

	@Column(name = "verification_expires_at", nullable = true)
	private Instant verificationExpiresAt;

	@Column(name = "consumed_at", nullable = true)
	private Instant consumedAt;

	private EmailVerification(
			String email,
			VerificationPurpose purpose,
			String codeHash,
			Instant expiresAt
	) {
		Objects.requireNonNull(purpose, "purpose");
		Objects.requireNonNull(expiresAt, "expiresAt");

		this.email = email;
		this.purpose = purpose;
		this.codeHash = codeHash;
		this.expiresAt = expiresAt;
		this.attemptCount = 0;
	}

	public static EmailVerification request(
			String email,
			VerificationPurpose purpose,
			String codeHash,
			Instant expiresAt
	) {
		return new EmailVerification(email, purpose, codeHash, expiresAt);
	}

	public static EmailVerification request(
			String email,
			VerificationPurpose purpose,
			String codeHash,
			Instant expiresAt,
			int previousAttemptCount
	) {
		if (previousAttemptCount < 0) {
			throw new IllegalArgumentException(
					"previousAttemptCount must not be negative"
			);
		}

		EmailVerification verification =
				request(email, purpose, codeHash, expiresAt);

		verification.attemptCount = previousAttemptCount;
		return verification;
	}

	public void markSent(Instant sentAt) {
		this.lastSentAt = Objects.requireNonNull(sentAt, "sentAt");
	}

	public void replaceCode(String codeHash, Instant expiresAt) {
		this.codeHash = Objects.requireNonNull(codeHash, "codeHash");
		this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
	}

	public void recordFailedAttempt() {
		this.attemptCount = Math.incrementExact(attemptCount);
	}

    public void recordFailedAttempt(Instant now, int limit, long cooldownSeconds) {
        recordFailedAttempt();
        if (attemptCount >= limit) {
            attemptsBlockedUntil = now.plusSeconds(cooldownSeconds);
        }
    }

    /** A new code starts a fresh budget only after the cooldown; resending alone never resets it. */
    public int attemptsForNewCode(Instant now, Integer limit, long cooldownSeconds) {
        if (limit == null || limit <= 0) {
            throw new AuthException(AuthErrorCode.EMAIL_VERIFICATION_NOT_CONFIGURED);
        }
        if (attemptsBlockedUntil != null) {
            if (now.isBefore(attemptsBlockedUntil)) {
                throw new AuthException(AuthErrorCode.EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED);
            }
            return 0;
        }
        if (attemptCount >= limit) {
            // Existing rows predate the cooldown column. Do not leave them permanently blocked.
            Instant anchor = lastSentAt != null ? lastSentAt : expiresAt;
            if (now.isBefore(anchor.plusSeconds(cooldownSeconds))) {
                throw new AuthException(AuthErrorCode.EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED);
            }
            return 0;
        }
        return attemptCount;
    }

	public void markVerified(String tokenHash, Instant verifiedAt, Instant tokenExpiresAt) {
		this.verificationTokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
		this.verifiedAt = Objects.requireNonNull(verifiedAt, "verifiedAt");
		this.verificationExpiresAt = Objects.requireNonNull(tokenExpiresAt, "tokenExpiresAt");
	}

	public void consume(Instant consumedAt) {
		Objects.requireNonNull(consumedAt, "consumedAt");
		if (this.consumedAt != null || verifiedAt == null || verificationTokenHash == null
				|| verificationExpiresAt == null || lastSentAt == null) {
			throw new AuthException(AuthErrorCode.EMAIL_VERIFICATION_INVALID);
		}
		if (!consumedAt.isBefore(verificationExpiresAt)) {
			throw new AuthException(AuthErrorCode.EMAIL_VERIFICATION_TOKEN_EXPIRED);
		}
		this.consumedAt = consumedAt;
	}
    public void markSent(Instant sentAt, long ttlSeconds) {
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("ttlSeconds must be positive");
        }
        markSent(sentAt);
        this.expiresAt = sentAt.plusSeconds(ttlSeconds);
    }

    /** Preserve request ID and failed attempts; pending codes cannot be confirmed. */
    public void queueReplacement(String recipient, String newCodeHash, Instant queuedAt,
            int retainedAttempts) {
        if (retainedAttempts < 0 || retainedAttempts > attemptCount) {
            throw new IllegalArgumentException("Invalid retained verification attempts");
        }
        this.email = Objects.requireNonNull(recipient, "recipient");
        this.codeHash = Objects.requireNonNull(newCodeHash, "newCodeHash");
        this.expiresAt = Objects.requireNonNull(queuedAt, "queuedAt");
        this.lastSentAt = null;
        this.attemptCount = retainedAttempts;
        this.attemptsBlockedUntil = null;
        this.verifiedAt = null;
        this.verificationTokenHash = null;
        this.verificationExpiresAt = null;
    }
}
