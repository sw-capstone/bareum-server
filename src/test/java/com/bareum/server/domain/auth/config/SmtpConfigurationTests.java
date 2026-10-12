package com.bareum.server.domain.auth.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class SmtpConfigurationTests {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
            .withInitializer(ctx -> {
                try {
                    var sources = new YamlPropertySourceLoader().load(
                            "mail-test", new ClassPathResource("application.yml"));
                    sources.forEach(source -> ctx.getEnvironment()
                            .getPropertySources().addLast(source));
                } catch (java.io.IOException exception) {
                    throw new IllegalStateException(exception);
                }
            });

    @Test
    void configuredSenderReceivesFiniteDefaultsFromApplicationYaml() {
        context.withPropertyValues("spring.mail.host=127.0.0.1").run(ctx -> {
            assertNull(ctx.getStartupFailure());
            var properties = ctx.getBean(JavaMailSenderImpl.class).getJavaMailProperties();
            assertEquals("5000", properties.getProperty("mail.smtp.connectiontimeout"));
            assertEquals("5000", properties.getProperty("mail.smtp.timeout"));
            assertEquals("5000", properties.getProperty("mail.smtp.writetimeout"));
        });
    }

    @Test
    void timeoutEnvironmentOverridesReachJavaMail() {
        context.withPropertyValues("spring.mail.host=127.0.0.1",
                "SMTP_CONNECTION_TIMEOUT_MS=1100", "SMTP_READ_TIMEOUT_MS=1200",
                "SMTP_WRITE_TIMEOUT_MS=1300").run(ctx -> {
            assertNull(ctx.getStartupFailure());
            var properties = ctx.getBean(JavaMailSenderImpl.class).getJavaMailProperties();
            assertEquals("1100", properties.getProperty("mail.smtp.connectiontimeout"));
            assertEquals("1200", properties.getProperty("mail.smtp.timeout"));
            assertEquals("1300", properties.getProperty("mail.smtp.writetimeout"));
        });
    }

    @Test
    void gmailVerificationProfileRequiresAuthenticatedTlsAndKeepsCredentialsExternal() {
        context.withInitializer(ctx -> {
            try {
                var sources = new YamlPropertySourceLoader().load(
                        "gmail-verification", new ClassPathResource("application-gmail-verification.yml"));
                sources.forEach(source -> ctx.getEnvironment().getPropertySources().addFirst(source));
            } catch (java.io.IOException exception) {
                throw new IllegalStateException(exception);
            }
        }).withPropertyValues("GMAIL_TEST_ADDRESS=smtp-test@example.invalid",
                "GMAIL_APP_PASSWORD=test-only-placeholder").run(ctx -> {
            assertNull(ctx.getStartupFailure());
            var sender = ctx.getBean(JavaMailSenderImpl.class);
            assertEquals("smtp.gmail.com", sender.getHost());
            assertEquals(587, sender.getPort());
            assertEquals("smtp-test@example.invalid", sender.getUsername());
            assertEquals("smtp-test@example.invalid", ctx.getEnvironment()
                    .getProperty("auth.email-verification.mail-from"));
            var properties = sender.getJavaMailProperties();
            assertEquals("true", properties.getProperty("mail.smtp.auth"));
            assertEquals("true", properties.getProperty("mail.smtp.starttls.enable"));
            assertEquals("true", properties.getProperty("mail.smtp.starttls.required"));
            assertEquals("true", properties.getProperty("mail.smtp.ssl.checkserveridentity"));
            assertEquals("5000", properties.getProperty("mail.smtp.timeout"));
            assertFalse(Boolean.parseBoolean(ctx.getEnvironment().getProperty("spring.jpa.show-sql")));
            assertNull(ctx.getEnvironment().getProperty("auth.email-verification.max-sends-per-hour"));
        });
    }

    @Test
    void noProviderIsSelectedWhenHostIsMissing() {
        context.run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertFalse(ctx.containsBean("mailSender"));
            assertEquals(0, ctx.getBeansOfType(JavaMailSender.class).size());
        });
    }
}
