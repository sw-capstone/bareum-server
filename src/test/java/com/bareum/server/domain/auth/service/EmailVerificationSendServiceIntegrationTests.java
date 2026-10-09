package com.bareum.server.domain.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.bareum.server.domain.auth.dto.request.EmailVerificationRequest;
import com.bareum.server.domain.auth.entity.EmailVerification;
import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.repository.EmailVerificationRepository;
import com.bareum.server.domain.auth.repository.EmailVerificationSendLogRepository;
import java.time.Instant;
import java.time.Clock;
import java.util.Locale;
import com.bareum.server.domain.auth.support.TestSmtpServer;
import com.bareum.server.domain.member.entity.Member;
import com.bareum.server.domain.member.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "auth.email-verification.signup-code-ttl-seconds=300",
        "auth.email-verification.password-reset-code-ttl-seconds=420",
        "auth.email-verification.minimum-send-interval-seconds=60",
        "auth.email-verification.max-sends-per-hour=5",
        "auth.email-verification.max-sends-per-day=20",
        // 테스트 전용 키. 실제 서비스 설정에 사용하지 않습니다.
        "auth.email-verification.hash-secret="
                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class EmailVerificationSendServiceIntegrationTests {

    @Autowired
    private EmailVerificationSendService sendService;

    @Autowired
    private EmailVerificationRepository verificationRepository;

    @Autowired
    private EmailVerificationSendLogRepository sendLogRepository;

    @Autowired
    private EmailVerificationCodeHasher codeHasher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private EmailVerificationMailSender mailSender;

    @Autowired
    private MemberRepository memberRepository;

    @MockitoBean
    private Clock emailVerificationClock;

    private static final Instant NOW = Instant.parse("2026-10-09T08:00:00Z");

    @BeforeEach
    void fixedClock() {
        when(emailVerificationClock.instant()).thenReturn(NOW);
    }

    private final String email =
            "send-test-" + UUID.randomUUID() + "@example.invalid";

    @AfterEach
    void cleanUp() {
        // 이 테스트에서 만든 이메일의 데이터만 삭제합니다.
        jdbcTemplate.update(
                "DELETE FROM email_verification_send_log WHERE email = ?",
                email
        );
        jdbcTemplate.update(
                "DELETE FROM email_verification WHERE email = ?",
                email
        );
        jdbcTemplate.update("DELETE FROM member WHERE email = ?", email);
    }

    @Test
    void mailFailureRollsBackVerificationAndSendLog() {
        doThrow(new AuthException(AuthErrorCode.EMAIL_SEND_FAILED))
                .when(mailSender)
                .sendVerificationCode(
                        eq(email),
                        anyString(),
                        eq(VerificationPurpose.SIGNUP)
                );

        AuthException exception = assertThrows(
                AuthException.class,
                () -> sendService.send(
                        new EmailVerificationRequest(
                                email,
                                VerificationPurpose.SIGNUP
                        )
                )
        );

        assertSame(
                AuthErrorCode.EMAIL_SEND_FAILED,
                exception.getBaseErrorCode()
        );

        verify(mailSender).sendVerificationCode(
                eq(email),
                anyString(),
                eq(VerificationPurpose.SIGNUP)
        );

        assertTrue(
                verificationRepository
                        .findFirstByEmailIgnoreCaseAndPurposeOrderByIdDesc(
                                email,
                                VerificationPurpose.SIGNUP
                        )
                        .isEmpty()
        );

        assertEquals(
                0L,
                sendLogRepository
                        .countByEmailIgnoreCaseAndSentAtGreaterThanEqual(
                                email,
                                Instant.EPOCH
                        )
        );
    }

    @Test
    void successfulSendCommitsVerificationAndSendLog() {
        var response = sendService.send(
                new EmailVerificationRequest(
                        email,
                        VerificationPurpose.SIGNUP
                )
        );

        ArgumentCaptor<String> codeCaptor =
                ArgumentCaptor.forClass(String.class);

        verify(mailSender).sendVerificationCode(
                eq(email),
                codeCaptor.capture(),
                eq(VerificationPurpose.SIGNUP)
        );

        EmailVerification verification = verificationRepository
                .findFirstByEmailIgnoreCaseAndPurposeOrderByIdDesc(
                        email,
                        VerificationPurpose.SIGNUP
                )
                .orElseThrow();

        String sentCode = codeCaptor.getValue();

        assertTrue(sentCode.matches("\\d{6}"));
        assertNotEquals(sentCode, verification.getCodeHash());

        assertTrue(
                codeHasher.matches(
                        email,
                        VerificationPurpose.SIGNUP,
                        sentCode,
                        verification.getCodeHash()
                )
        );

        assertFalse(
                sendLogRepository
                        .findFirstByEmailIgnoreCaseOrderBySentAtDesc(email)
                        .isEmpty()
        );

        assertEquals(
                1L,
                sendLogRepository
                        .countByEmailIgnoreCaseAndSentAtGreaterThanEqual(
                                email,
                                Instant.EPOCH
                        )
        );

        assertTrue(response.expiresIn() > 0);
        assertTrue(response.expiresIn() <= 300);
    }
    @Test
    void delayedSmtpAcceptanceCommitsFullValidityAndAcceptedTimestamp() {
        when(emailVerificationClock.instant()).thenReturn(
                NOW, NOW, NOW.plusSeconds(301), NOW.plusSeconds(301));
        var response = sendService.send(new EmailVerificationRequest(email, VerificationPurpose.SIGNUP));
        var verification = verificationRepository
                .findFirstByEmailIgnoreCaseAndPurposeOrderByIdDesc(email, VerificationPurpose.SIGNUP)
                .orElseThrow();
        assertEquals(300L, response.expiresIn());
        assertEquals(NOW.plusSeconds(601), verification.getExpiresAt());
        assertEquals(NOW.plusSeconds(301), verification.getLastSentAt());
        assertEquals(NOW.plusSeconds(301), sendLogRepository
                .findFirstByEmailIgnoreCaseOrderBySentAtDesc(email).orElseThrow().getSentAt());
    }

    @Test
    void resetSmtpProtocolAndDatabaseUseRegisteredAddressDespiteRequestCase() throws Exception {
        memberRepository.saveAndFlush(Member.createLocal(email, "test", "test-password-hash"));
        try (var server = new TestSmtpServer(false, false)) {
            var smtp = SmtpEmailVerificationMailSenderTests.sender(
                    SmtpEmailVerificationMailSenderTests.transport(server.port(), 1000),
                    "sender@example.invalid");
            doAnswer(invocation -> {
                smtp.sendVerificationCode(invocation.getArgument(0), invocation.getArgument(1),
                        invocation.getArgument(2));
                return null;
            }).when(mailSender).sendVerificationCode(eq(email), anyString(), eq(VerificationPurpose.PASSWORD_RESET));
            var response = sendService.send(new EmailVerificationRequest(
                    email.toUpperCase(Locale.ROOT), VerificationPurpose.PASSWORD_RESET));
            assertEquals(420L, response.expiresIn());
            assertEquals("RCPT TO:<" + email + ">", server.recipient());
            assertEquals(email, verificationRepository
                    .findFirstByEmailIgnoreCaseAndPurposeOrderByIdDesc(email, VerificationPurpose.PASSWORD_RESET)
                    .orElseThrow().getEmail());
            assertEquals(email, sendLogRepository.findFirstByEmailIgnoreCaseOrderBySentAtDesc(email)
                    .orElseThrow().getEmail());
        }
    }

}
