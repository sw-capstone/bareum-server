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
    void noProviderIsSelectedWhenHostIsMissing() {
        context.run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertFalse(ctx.containsBean("mailSender"));
            assertEquals(0, ctx.getBeansOfType(JavaMailSender.class).size());
        });
    }
}
