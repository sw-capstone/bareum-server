package com.bareum.server.domain.auth.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.domain.auth.exception.AuthException;

class EmailVerificationPropertiesTests {
	private final ApplicationContextRunner context = new ApplicationContextRunner()
			.withUserConfiguration(EmailVerificationTimeConfig.class);

	@Test
	void envV2NamesBindTemporaryPeriodsAndLimitsForBothPurposes() {
		context.withInitializer(ctx -> ctx.getEnvironment()
				.getPropertySources()
				.addFirst(
						new SystemEnvironmentPropertySource("env-v2-systemEnvironment",
								Map.of(
										"AUTH_EMAILVERIFICATION_SIGNUPCODETTLSECONDS", "300",
										"AUTH_EMAILVERIFICATION_PASSWORDRESETCODETTLSECONDS", "300",
										"AUTH_EMAILVERIFICATION_MINIMUMSENDINTERVALSECONDS", "60",
										"AUTH_EMAILVERIFICATION_MAXSENDSPERHOUR", "5",
										"AUTH_EMAILVERIFICATION_MAXSENDSPERDAY",
										"20"))))
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
		)
				.run(ctx -> {
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
		)
				.run(ctx -> assertEquals(420L, ctx.getBean(EmailVerificationProperties.class)
						.codeTtlSeconds(VerificationPurpose.PASSWORD_RESET)));
	}
}
