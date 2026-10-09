package com.bareum.server.domain.auth.config;

import static org.junit.jupiter.api.Assertions.*;

import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.domain.auth.exception.AuthException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class EmailVerificationPropertiesTests {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(EmailVerificationTimeConfig.class);

    @Test
    void defaultsKeepSignupValidityButDoNotInventSendLimits() {
        context.run(ctx -> {
            assertNull(ctx.getStartupFailure());
            var properties = ctx.getBean(EmailVerificationProperties.class);
            assertEquals(300L, properties.getSignupCodeTtlSeconds());
            assertNull(properties.getMinimumSendIntervalSeconds());
            assertNull(properties.getPasswordResetCodeTtlSeconds());
            assertThrows(AuthException.class,
                    () -> properties.requireConfigured(VerificationPurpose.SIGNUP));
        });
    }

    @Test
    void constructorBindingSupportsOverridesAndResetRequiresItsOwnValidity() {
        context.withPropertyValues(
                "auth.email-verification.signup-code-ttl-seconds=240",
                "auth.email-verification.minimum-send-interval-seconds=60",
                "auth.email-verification.max-sends-per-hour=5",
                "auth.email-verification.max-sends-per-day=20"
        ).run(ctx -> {
            assertNull(ctx.getStartupFailure());
            var properties = ctx.getBean(EmailVerificationProperties.class);
            assertEquals(240L, properties.codeTtlSeconds(VerificationPurpose.SIGNUP));
            assertThrows(AuthException.class,
                    () -> properties.codeTtlSeconds(VerificationPurpose.PASSWORD_RESET));
        });
        context.withPropertyValues(
                "auth.email-verification.minimum-send-interval-seconds=60",
                "auth.email-verification.max-sends-per-hour=5",
                "auth.email-verification.max-sends-per-day=20",
                "auth.email-verification.password-reset-code-ttl-seconds=420"
        ).run(ctx -> assertEquals(420L, ctx.getBean(EmailVerificationProperties.class)
                .codeTtlSeconds(VerificationPurpose.PASSWORD_RESET)));
    }
}
