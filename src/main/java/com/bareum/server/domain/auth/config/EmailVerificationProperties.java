package com.bareum.server.domain.auth.config;

import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "auth.email-verification")
public class EmailVerificationProperties {

    private long signupCodeTtlSeconds = 300;

    private Long passwordResetCodeTtlSeconds;
    private Long minimumSendIntervalSeconds;
    private Integer maxSendsPerHour;
    private Integer maxSendsPerDay;

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
