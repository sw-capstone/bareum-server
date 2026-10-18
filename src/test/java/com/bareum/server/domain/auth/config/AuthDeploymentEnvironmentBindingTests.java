package com.bareum.server.domain.auth.config;

import static org.junit.jupiter.api.Assertions.*;

import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.domain.auth.exception.AuthException;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class AuthDeploymentEnvironmentBindingTests {
    private void load(ConfigurableEnvironment environment, Map<String, Object> variables) {
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        try {
            for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
                environment.getPropertySources().addLast(source);
            }
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private <T> T bind(Map<String, Object> variables, String prefix, Class<T> type) {
        var environment = new StandardEnvironment();
        load(environment, variables);
        var binder = new Binder(ConfigurationPropertySources.get(environment),
                new PropertySourcesPlaceholdersResolver(environment));
        return binder.bindOrCreate(prefix, Bindable.of(type));
    }

    @Test
    void deploymentLimitsReachVerificationPolicyForBothPurposes() {
        var properties = bind(Map.of(
                "EMAIL_VERIFICATION_MINIMUM_SEND_INTERVAL_SECONDS", "60",
                "EMAIL_VERIFICATION_MAX_SENDS_PER_HOUR", "10",
                "EMAIL_VERIFICATION_MAX_SENDS_PER_DAY", "30",
                "EMAIL_VERIFICATION_MAX_VERIFICATION_ATTEMPTS", "5",
                "EMAIL_VERIFICATION_PASSWORD_RESET_CODE_TTL_SECONDS", "180",
                "EMAIL_VERIFICATION_PASSWORD_RESET_VERIFICATION_TTL_SECONDS", "240"),
                "auth.email-verification", EmailVerificationProperties.class);
        assertDoesNotThrow(() -> properties.requireConfigured(VerificationPurpose.SIGNUP));
        assertDoesNotThrow(() -> properties.requireConfigured(VerificationPurpose.PASSWORD_RESET));
        assertEquals(60, properties.getMinimumSendIntervalSeconds());
        assertEquals(10, properties.getMaxSendsPerHour());
        assertEquals(30, properties.getMaxSendsPerDay());
        assertEquals(5, properties.getMaxVerificationAttempts());
        assertEquals(300, properties.codeTtlSeconds(VerificationPurpose.SIGNUP));
        assertEquals(600, properties.verificationTtlSeconds(VerificationPurpose.SIGNUP));
        assertEquals(180, properties.codeTtlSeconds(VerificationPurpose.PASSWORD_RESET));
        assertEquals(240, properties.verificationTtlSeconds(VerificationPurpose.PASSWORD_RESET));
    }

    @Test
    void missingDeploymentLimitsRemainBlocked() {
        var properties = bind(Map.of(), "auth.email-verification", EmailVerificationProperties.class);
        assertThrows(AuthException.class, () -> properties.requireConfigured(VerificationPurpose.SIGNUP));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void smtpEnvironmentReachesAuthenticatedTlsSender(boolean implicitTls) {
        var variables = Map.<String, Object>of(
                "SPRING_MAIL_HOST", "smtp.example.invalid",
                "SPRING_MAIL_PORT", implicitTls ? "465" : "587",
                "SPRING_MAIL_USERNAME", "sender@example.invalid",
                "SPRING_MAIL_PASSWORD", "test-only-placeholder",
                "SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH", "true",
                "SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_ENABLE", String.valueOf(implicitTls),
                "SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE", String.valueOf(!implicitTls),
                "SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED", String.valueOf(!implicitTls));
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
                .withInitializer(ctx -> load(ctx.getEnvironment(), variables))
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure());
                    var sender = ctx.getBean(JavaMailSenderImpl.class);
                    assertEquals("smtp.example.invalid", sender.getHost());
                    assertEquals(implicitTls ? 465 : 587, sender.getPort());
                    assertEquals("sender@example.invalid", sender.getUsername());
                    assertEquals("test-only-placeholder", sender.getPassword());
                    var properties = sender.getJavaMailProperties();
                    assertEquals("true", properties.getProperty("mail.smtp.auth"));
                    assertEquals(String.valueOf(implicitTls), properties.getProperty("mail.smtp.ssl.enable"));
                    assertEquals(String.valueOf(!implicitTls), properties.getProperty("mail.smtp.starttls.required"));
                    assertEquals("true", properties.getProperty("mail.smtp.ssl.checkserveridentity"));
                    assertEquals("5000", properties.getProperty("mail.smtp.timeout"));
                });
    }

    @Test
    void exactOriginsAndGoogleClientIdsReachExistingAuthenticationConfiguration() {
        var variables = Map.<String, Object>of(
                "LOGIN_ALLOWED_ORIGINS", "https://bareum.site,https://bareum-web-git-develop-numkites-projects.vercel.app",
                "GOOGLE_CLIENT_IDS", "test-web.apps.googleusercontent.com",
                "GOOGLE_SIGNUP_TTL_SECONDS", "300");
        var login = bind(variables, "auth.login", LoginProperties.class);
        assertEquals(2, login.allowedOrigins().size());
        assertEquals(java.util.List.of("https://bareum.site",
                "https://bareum-web-git-develop-numkites-projects.vercel.app"), login.allowedOrigins());
        assertEquals(900, login.accessTtlSeconds());
        assertEquals(28800, login.sessionTtlSeconds());
        var google = bind(variables, "auth.google", GoogleLoginProperties.class);
        assertTrue(google.allowedClientIds().contains("test-web.apps.googleusercontent.com"));
        assertEquals(300, google.signupTtlSeconds());
    }
}
