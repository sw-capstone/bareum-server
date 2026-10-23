package com.bareum.server.domain.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;

import lombok.Getter;

@Getter
@ConfigurationProperties(prefix = "auth.email-verification")
public final class EmailVerificationProperties {

	private final long signupCodeTtlSeconds;
	private final Long passwordResetCodeTtlSeconds;
	private final Long minimumSendIntervalSeconds;
	private final Integer maxSendsPerHour;
	private final Integer maxSendsPerDay;
	private final Integer maxVerificationAttempts;
	private final long signupVerificationTtlSeconds;
	private final Long passwordResetVerificationTtlSeconds;

	public EmailVerificationProperties(
		@DefaultValue("300") long signupCodeTtlSeconds,
		Long passwordResetCodeTtlSeconds,
		Long minimumSendIntervalSeconds,
		Integer maxSendsPerHour,
		Integer maxSendsPerDay,
		Integer maxVerificationAttempts,
		@DefaultValue("600") long signupVerificationTtlSeconds,
		Long passwordResetVerificationTtlSeconds
	) {
		this.signupCodeTtlSeconds = signupCodeTtlSeconds;
		this.passwordResetCodeTtlSeconds = passwordResetCodeTtlSeconds;
		this.minimumSendIntervalSeconds = minimumSendIntervalSeconds;
		this.maxSendsPerHour = maxSendsPerHour;
		this.maxSendsPerDay = maxSendsPerDay;
		this.maxVerificationAttempts = maxVerificationAttempts;
		this.signupVerificationTtlSeconds = signupVerificationTtlSeconds;
		this.passwordResetVerificationTtlSeconds = passwordResetVerificationTtlSeconds;
	}

	public long verificationTtlSeconds(VerificationPurpose purpose) {
		Long ttl = purpose == VerificationPurpose.SIGNUP
				? Long.valueOf(signupVerificationTtlSeconds) : passwordResetVerificationTtlSeconds;
		if (maxVerificationAttempts == null || maxVerificationAttempts <= 0
				|| ttl == null || ttl <= 0) {
			throw new AuthException(AuthErrorCode.EMAIL_VERIFICATION_NOT_CONFIGURED);
		}
		return ttl;
	}

	public void requireConfigured(VerificationPurpose purpose) {
		if (minimumSendIntervalSeconds == null
				|| minimumSendIntervalSeconds <= 0
				|| maxSendsPerHour == null
				|| maxSendsPerHour <= 0
				|| maxSendsPerDay == null
				|| maxSendsPerDay <= 0) {
			throw new AuthException(
					AuthErrorCode.EMAIL_VERIFICATION_NOT_CONFIGURED
			);
		}

		if (purpose == VerificationPurpose.SIGNUP
				&& signupCodeTtlSeconds <= 0) {
			throw new AuthException(
					AuthErrorCode.EMAIL_VERIFICATION_NOT_CONFIGURED
			);
		}

		if (purpose == VerificationPurpose.PASSWORD_RESET
				&& (passwordResetCodeTtlSeconds == null
						|| passwordResetCodeTtlSeconds <= 0)) {
			throw new AuthException(
					AuthErrorCode.EMAIL_VERIFICATION_NOT_CONFIGURED
			);
		}
	}

	public long codeTtlSeconds(VerificationPurpose purpose) {
		requireConfigured(purpose);

		return switch (purpose) {
			case SIGNUP -> signupCodeTtlSeconds;
			case PASSWORD_RESET -> passwordResetCodeTtlSeconds;
		};
	}
}
