package com.bareum.server.domain.auth.config;

import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@Getter
@ConfigurationProperties(prefix = "auth.email-verification")
public final class EmailVerificationProperties {

    private final long signupCodeTtlSeconds;
    private final Long passwordResetCodeTtlSeconds;
    private final Long minimumSendIntervalSeconds;
    private final Integer maxSendsPerHour;
    private final Integer maxSendsPerDay;

    public EmailVerificationProperties(
            @DefaultValue("300") long signupCodeTtlSeconds,
            Long passwordResetCodeTtlSeconds,
            Long minimumSendIntervalSeconds,
            Integer maxSendsPerHour,
            Integer maxSendsPerDay
    ) {
        this.signupCodeTtlSeconds = signupCodeTtlSeconds;
        this.passwordResetCodeTtlSeconds = passwordResetCodeTtlSeconds;
        this.minimumSendIntervalSeconds = minimumSendIntervalSeconds;
        this.maxSendsPerHour = maxSendsPerHour;
        this.maxSendsPerDay = maxSendsPerDay;
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
