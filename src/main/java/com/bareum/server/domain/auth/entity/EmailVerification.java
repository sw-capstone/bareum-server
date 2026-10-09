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
}
