package com.bareum.server.domain.auth.service;

import static org.junit.jupiter.api.Assertions.*;

import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.support.TestSmtpServer;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class SmtpEmailVerificationMailSenderTests {
    public static JavaMailSenderImpl transport(int port, int readTimeout) {
        var transport = new JavaMailSenderImpl();
        transport.setHost("127.0.0.1");
        transport.setPort(port);
        transport.getJavaMailProperties().setProperty("mail.smtp.connectiontimeout", "1000");
        transport.getJavaMailProperties().setProperty("mail.smtp.timeout", String.valueOf(readTimeout));
        transport.getJavaMailProperties().setProperty("mail.smtp.writetimeout", "1000");
        return transport;
    }

    public static SmtpEmailVerificationMailSender sender(JavaMailSender transport, String from) {
        var factory = new DefaultListableBeanFactory();
        if (transport != null) { factory.registerSingleton("mailSender", transport); }
        return new SmtpEmailVerificationMailSender(factory.getBeanProvider(JavaMailSender.class), from);
    }

    @Test
    void realSmtpProtocolCarriesRegisteredRecipientAndCode() throws Exception {
        try (var server = new TestSmtpServer(false, false)) {
            var transport = transport(server.port(), 1000);
            sender(transport, "sender@example.invalid").sendVerificationCode(
                    "Registered@Example.invalid", "123456", VerificationPurpose.PASSWORD_RESET);
            assertEquals("RCPT TO:<Registered@Example.invalid>", server.recipient());
            var mail = new MimeMessage(transport.getSession(), new ByteArrayInputStream(
                    server.message().getBytes(StandardCharsets.US_ASCII)));
            assertEquals("[바름] 비밀번호 재설정 이메일 인증", mail.getSubject());
            assertTrue(mail.getContent().toString().contains("123456"));
            assertEquals("sender@example.invalid", mail.getFrom()[0].toString());
        }
    }

    @Test
    void recipientRejectionBecomesSendFailure() throws Exception {
        try (var server = new TestSmtpServer(true, false)) {
            var sender = sender(transport(server.port(), 1000), "sender@example.invalid");
            var failure = assertThrows(AuthException.class, () -> sender.sendVerificationCode(
                    "user@example.invalid", "123456", VerificationPurpose.SIGNUP));
            assertSame(AuthErrorCode.EMAIL_SEND_FAILED, failure.getBaseErrorCode());
        }
    }

    @Test
    void unresponsiveSmtpStopsWithinReadTimeout() throws Exception {
        try (var server = new TestSmtpServer(false, true)) {
            var sender = sender(transport(server.port(), 200), "sender@example.invalid");
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                var failure = assertThrows(AuthException.class, () -> sender.sendVerificationCode(
                        "user@example.invalid", "123456", VerificationPurpose.SIGNUP));
                assertSame(AuthErrorCode.EMAIL_SEND_FAILED, failure.getBaseErrorCode());
            });
        }
    }

    @Test
    void missingSenderOrFromAddressFailsClosed() {
        for (var sender : new SmtpEmailVerificationMailSender[] {
                sender(null, "sender@example.invalid"), sender(new JavaMailSenderImpl(), "")
        }) {
            var failure = assertThrows(AuthException.class, () -> sender.sendVerificationCode(
                    "user@example.invalid", "123456", VerificationPurpose.SIGNUP));
            assertSame(AuthErrorCode.EMAIL_VERIFICATION_NOT_CONFIGURED, failure.getBaseErrorCode());
        }
    }
}
