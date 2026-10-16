package com.bareum.server.domain.auth.config;

import static org.junit.jupiter.api.Assertions.*;

import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.domain.auth.exception.AuthException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class EmailVerificationPropertiesTests {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(EmailVerificationTimeConfig.class);

    @Test
    void envV2NamesBindTemporaryPeriodsAndLimitsForBothPurposes() {
        context.withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("env-v2-systemEnvironment", Map.of(
                        "AUTH_EMAILVERIFICATION_SIGNUPCODETTLSECONDS", "300",
                        "AUTH_EMAILVERIFICATION_PASSWORDRESETCODETTLSECONDS", "300",
                        "AUTH_EMAILVERIFICATION_MINIMUMSENDINTERVALSECONDS", "60",
                        "AUTH_EMAILVERIFICATION_MAXSENDSPERHOUR", "5",
                        "AUTH_EMAILVERIFICATION_MAXSENDSPERDAY", "20"))))
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure());
                    var properties = ctx.getBean(EmailVerificationProperties.class);
                    assertEquals(300L, properties.codeTtlSeconds(VerificationPurpose.SIGNUP));
                    assertEquals(300L, properties.codeTtlSeconds(VerificationPurpose.PASSWORD_RESET));
                    assertEquals(60L, properties.getMinimumSendIntervalSeconds());
                    assertEquals(5, properties.getMaxSendsPerHour());
                    assertEquals(20, properties.getMaxSendsPerDay());
                });
    }

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

    @Test
    void confirmationRequiresAttemptLimitAndKeepsSignupProofAtTenMinutes() {
        context.run(ctx -> {
            var properties = ctx.getBean(EmailVerificationProperties.class);
            assertNull(properties.getMaxVerificationAttempts());
            assertEquals(600L, properties.getSignupVerificationTtlSeconds());
            assertNull(properties.getPasswordResetVerificationTtlSeconds());
            assertThrows(AuthException.class,
                    () -> properties.verificationTtlSeconds(VerificationPurpose.SIGNUP));
        });
        context.withPropertyValues("auth.email-verification.max-verification-attempts=3")
                .run(ctx -> {
                    var properties = ctx.getBean(EmailVerificationProperties.class);
                    assertEquals(600L, properties.verificationTtlSeconds(VerificationPurpose.SIGNUP));
                    assertThrows(AuthException.class,
                            () -> properties.verificationTtlSeconds(VerificationPurpose.PASSWORD_RESET));
                });
    }

    @Test
    void confirmationRejectsNonPositiveLimitOrProofValidity() {
        for (String setting : new String[]{
                "auth.email-verification.max-verification-attempts=0",
                "auth.email-verification.max-verification-attempts=-1",
                "auth.email-verification.signup-verification-ttl-seconds=0"
        }) {
            context.withPropertyValues(setting).run(ctx -> assertThrows(AuthException.class,
                    () -> ctx.getBean(EmailVerificationProperties.class)
                            .verificationTtlSeconds(VerificationPurpose.SIGNUP)));
        }
        context.withPropertyValues(
                "auth.email-verification.max-verification-attempts=3",
                "auth.email-verification.password-reset-verification-ttl-seconds=0"
        ).run(ctx -> assertThrows(AuthException.class,
                () -> ctx.getBean(EmailVerificationProperties.class)
                        .verificationTtlSeconds(VerificationPurpose.PASSWORD_RESET)));
    }

    @Test
    void applicationYamlBindsConfirmationEnvironmentNamesWithoutChangingSendPolicy() {
        context.withInitializer(new ConfigDataApplicationContextInitializer())
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("confirmation-environment", Map.of(
                                "EMAIL_VERIFICATION_MAX_VERIFICATION_ATTEMPTS", "3",
                                "EMAIL_VERIFICATION_PASSWORD_RESET_VERIFICATION_TTL_SECONDS", "420"))))
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure());
                    var properties = ctx.getBean(EmailVerificationProperties.class);
                    assertEquals(3, properties.getMaxVerificationAttempts());
                    assertEquals(600L, properties.verificationTtlSeconds(VerificationPurpose.SIGNUP));
                    assertEquals(420L, properties.verificationTtlSeconds(VerificationPurpose.PASSWORD_RESET));
                    assertNull(properties.getMinimumSendIntervalSeconds());
                });
    }
}
