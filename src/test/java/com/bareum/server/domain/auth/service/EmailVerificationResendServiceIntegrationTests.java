package com.bareum.server.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bareum.server.domain.auth.config.EmailVerificationProperties;
import com.bareum.server.domain.auth.dto.request.EmailVerificationRequest;
import com.bareum.server.domain.auth.entity.EmailVerification;
import com.bareum.server.domain.auth.entity.EmailVerificationSendLog;
import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.repository.EmailVerificationRepository;
import com.bareum.server.domain.auth.repository.EmailVerificationSendLogRepository;
import com.bareum.server.domain.member.entity.Member;
import com.bareum.server.domain.member.repository.MemberRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        // 아래 제한과 재설정 기간·키는 테스트 전용이며 운영 정책이 아닙니다.
        "auth.email-verification.minimum-send-interval-seconds=60",
        "auth.email-verification.max-sends-per-hour=5",
        "auth.email-verification.max-sends-per-day=20",
        "auth.email-verification.password-reset-code-ttl-seconds=420",
        "auth.email-verification.hash-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class EmailVerificationResendServiceIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");
    private static final String OLD_CODE = "123456";
    private static final String NEW_CODE = "654321";
    private final String email = "resend-test-" + UUID.randomUUID() + "@example.invalid";

    @Autowired private EmailVerificationResendService resendService;
    @Autowired private EmailVerificationSendService sendService;
    @Autowired private EmailVerificationRepository verificationRepository;
    @Autowired private EmailVerificationSendLogRepository sendLogRepository;
    @Autowired private EmailVerificationCodeHasher codeHasher;
    @Autowired private EmailVerificationProperties properties;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MemberRepository memberRepository;
    @MockitoBean private EmailVerificationMailSender mailSender;
    @MockitoBean private EmailVerificationCodeGenerator codeGenerator;
    @MockitoBean(name = "emailVerificationClock") private Clock clock;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        when(codeGenerator.generate()).thenReturn(NEW_CODE);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM email_verification_send_log WHERE email = ?", email);
        jdbcTemplate.update("DELETE FROM email_verification WHERE email = ?", email);
        jdbcTemplate.update("DELETE FROM member WHERE email = ?", email);
    }

    @Test
    void replacesExpiredCodeOnSameRequestAndPreservesFailedAttempts() {
        EmailVerification original = existingRequest();
        jdbcTemplate.update("UPDATE email_verification SET expires_at = ? WHERE id = ?", Timestamp.from(NOW.minusSeconds(1)), original.getId());

        var response = resendService.resend(request());
        EmailVerification updated = latestRequest();

        assertThat(updated.getId()).isEqualTo(original.getId());
        assertThat(updated.getAttemptCount()).isEqualTo(2);
        assertThat(updated.getExpiresAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(updated.getLastSentAt()).isEqualTo(NOW);
        assertThat(updated.getVerifiedAt()).isNull();
        assertThat(updated.getCodeHash()).isNotEqualTo(NEW_CODE);
        assertThat(codeHasher.matches(email, VerificationPurpose.SIGNUP, OLD_CODE, updated.getCodeHash())).isFalse();
        assertThat(codeHasher.matches(email, VerificationPurpose.SIGNUP, NEW_CODE, updated.getCodeHash())).isTrue();
        assertThat(response.expiresIn()).isEqualTo(300);
        assertThat(logCount()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM email_verification WHERE email = ?", Long.class, email)).isEqualTo(1);
        verify(mailSender).sendVerificationCode(email, NEW_CODE, VerificationPurpose.SIGNUP);
    }

    @Test
    void updatesOnlyLatestRequestAndAcceptsEmailCaseVariants() {
        EmailVerification old = existingRequest();
        EmailVerification latest = verificationRepository.saveAndFlush(EmailVerification.request(
                email, VerificationPurpose.SIGNUP, old.getCodeHash(), NOW.plusSeconds(100), 4));
        latest.markSent(NOW.minusSeconds(60));
        verificationRepository.saveAndFlush(latest);

        resendService.resend(new EmailVerificationRequest(email.toUpperCase(Locale.ROOT), VerificationPurpose.SIGNUP));

        assertThat(latestRequest().getId()).isEqualTo(latest.getId());
        assertThat(latestRequest().getAttemptCount()).isEqualTo(4);
        assertThat(verificationRepository.findById(old.getId()).orElseThrow().getCodeHash()).isEqualTo(old.getCodeHash());
        verify(mailSender).sendVerificationCode(email, NEW_CODE, VerificationPurpose.SIGNUP);
    }

    @Test
    void missingRequestAndDifferentPurposeAreRejected() {
        expectError(AuthErrorCode.EMAIL_VERIFICATION_INVALID, request());
        existingRequest();
        expectError(AuthErrorCode.EMAIL_VERIFICATION_INVALID,
                new EmailVerificationRequest(email, VerificationPurpose.PASSWORD_RESET));
        verifyNoInteractions(mailSender, codeGenerator);
        assertThat(logCount()).isEqualTo(1);
    }

    @Test
    void passwordResetUsesConfiguredLifetimeForExistingLocalAccount() {
        memberRepository.saveAndFlush(Member.createLocal(email, "test-member", "test-password-hash"));
        VerificationPurpose purpose = VerificationPurpose.PASSWORD_RESET;
        EmailVerification original = EmailVerification.request(email, purpose,
                codeHasher.hash(email, purpose, OLD_CODE), NOW.plusSeconds(100), 2);
        original.markSent(NOW.minusSeconds(60));
        verificationRepository.saveAndFlush(original);
        sendLogRepository.saveAndFlush(EmailVerificationSendLog.sent(email, purpose, NOW.minusSeconds(60)));

        var response = resendService.resend(new EmailVerificationRequest(email, purpose));
        EmailVerification updated = verificationRepository.findById(original.getId()).orElseThrow();

        assertThat(response.expiresIn()).isEqualTo(420);
        assertThat(updated.getExpiresAt()).isEqualTo(NOW.plusSeconds(420));
        assertThat(updated.getAttemptCount()).isEqualTo(2);
        assertThat(codeHasher.matches(email, purpose, OLD_CODE, updated.getCodeHash())).isFalse();
        assertThat(codeHasher.matches(email, purpose, NEW_CODE, updated.getCodeHash())).isTrue();
        assertThat(logCount()).isEqualTo(2);
        verify(mailSender).sendVerificationCode(email, NEW_CODE, purpose);
    }

    @Test
    void rechecksRegistrationStatusBeforeReplacingCode() {
        EmailVerification original = existingRequest();
        memberRepository.saveAndFlush(Member.createLocal(email, "test-member", "test-password-hash"));
        expectError(AuthErrorCode.EMAIL_ALREADY_REGISTERED, request());
        assertUnchanged(original);
        verifyNoInteractions(mailSender, codeGenerator);
    }

    @Test
    void unsentAndConsumedRequestsAreRejectedWithoutChangingCode() {
        EmailVerification original = existingRequest();
        jdbcTemplate.update("UPDATE email_verification SET last_sent_at = NULL WHERE id = ?", original.getId());
        expectError(AuthErrorCode.EMAIL_VERIFICATION_INVALID, request());
        jdbcTemplate.update("UPDATE email_verification SET last_sent_at = ?, consumed_at = ? WHERE id = ?",
                Timestamp.from(NOW.minusSeconds(60)), Timestamp.from(NOW), original.getId());
        expectError(AuthErrorCode.EMAIL_VERIFICATION_INVALID, request());
        assertUnchanged(original);
        verifyNoInteractions(mailSender, codeGenerator);
    }

    @Test
    void verifiedRequestKeepsProofWhileResendPolicyIsUndecided() {
        EmailVerification original = existingRequest();
        jdbcTemplate.update("UPDATE email_verification SET verified_at = ?, verification_token_hash = ?, verification_expires_at = ? WHERE id = ?",
                Timestamp.from(NOW.minusSeconds(1)), "test-proof-hash", Timestamp.from(NOW.plusSeconds(600)), original.getId());
        expectError(AuthErrorCode.EMAIL_VERIFICATION_NOT_CONFIGURED, request());
        EmailVerification updated = latestRequest();
        assertThat(updated.getVerificationTokenHash()).isEqualTo("test-proof-hash");
        assertThat(updated.getVerificationExpiresAt()).isEqualTo(NOW.plusSeconds(600));
        assertUnchanged(original);
        verifyNoInteractions(mailSender, codeGenerator);
    }

    @ParameterizedTest
    @ValueSource(strings = {"verification_token_hash", "verification_expires_at"})
    void incompleteProofStateIsAlsoPreserved(String field) {
        EmailVerification original = existingRequest();
        if (field.equals("verification_token_hash")) {
            jdbcTemplate.update("UPDATE email_verification SET verification_token_hash = ? WHERE id = ?", "test-proof-hash", original.getId());
        } else {
            jdbcTemplate.update("UPDATE email_verification SET verification_expires_at = ? WHERE id = ?", Timestamp.from(NOW.plusSeconds(600)), original.getId());
        }
        expectError(AuthErrorCode.EMAIL_VERIFICATION_NOT_CONFIGURED, request());
        assertUnchanged(original);
        verifyNoInteractions(mailSender, codeGenerator);
    }

    @Test
    void requestsBeforeIntervalBoundaryAreRejected() {
        EmailVerification original = existingRequest();
        jdbcTemplate.update("UPDATE email_verification_send_log SET sent_at = ? WHERE email = ?", Timestamp.from(NOW.minusSeconds(59)), email);
        expectError(AuthErrorCode.EMAIL_SEND_LIMIT_EXCEEDED, request());
        assertUnchanged(original);
        verifyNoInteractions(mailSender, codeGenerator);
    }

    @Test
    void hourlyAndDailyLimitsIncludeOriginalAndResentMessages() {
        EmailVerification original = existingRequest();
        for (int i = 1; i < 5; i++) {
            sendLogRepository.saveAndFlush(EmailVerificationSendLog.sent(email, VerificationPurpose.SIGNUP, NOW.minusSeconds(60L * (i + 1))));
        }
        expectError(AuthErrorCode.EMAIL_SEND_LIMIT_EXCEEDED, request());
        assertThat(logCount()).isEqualTo(5);
        for (int i = 0; i < 15; i++) {
            sendLogRepository.saveAndFlush(EmailVerificationSendLog.sent(email, VerificationPurpose.SIGNUP, NOW.minusSeconds(3601L + i)));
        }
        // 시간당 상한을 올려 일당 상한을 독립적으로 검증합니다. 테스트 후 원복합니다.
        int previous = properties.getMaxSendsPerHour();
        properties.setMaxSendsPerHour(100);
        try { expectError(AuthErrorCode.EMAIL_SEND_LIMIT_EXCEEDED, request()); }
        finally { properties.setMaxSendsPerHour(previous); }
        assertThat(latestRequest().getCodeHash()).isEqualTo(original.getCodeHash());
        verifyNoInteractions(mailSender, codeGenerator);
    }

    @Test
    void missingLimitConfigurationLeavesOriginalRequestUntouched() {
        EmailVerification original = existingRequest();
        Long previous = properties.getMinimumSendIntervalSeconds();
        properties.setMinimumSendIntervalSeconds(null);
        try { expectError(AuthErrorCode.EMAIL_VERIFICATION_NOT_CONFIGURED, request()); }
        finally { properties.setMinimumSendIntervalSeconds(previous); }
        assertUnchanged(original);
        verifyNoInteractions(mailSender, codeGenerator);
    }

    @Test
    void mailFailureRollsBackCodeExpiryAndSendLog() {
        EmailVerification original = existingRequest();
        doThrow(new AuthException(AuthErrorCode.EMAIL_SEND_FAILED)).when(mailSender)
                .sendVerificationCode(eq(email), anyString(), eq(VerificationPurpose.SIGNUP));
        expectError(AuthErrorCode.EMAIL_SEND_FAILED, request());
        assertUnchanged(original);
        assertThat(latestRequest().getLastSentAt()).isEqualTo(original.getLastSentAt());
    }

    @Test
    void expiryDuringMailDeliveryRollsBackReplacement() {
        EmailVerification original = existingRequest();
        doAnswer(invocation -> {
            when(clock.instant()).thenReturn(NOW.plusSeconds(300));
            return null;
        }).when(mailSender).sendVerificationCode(eq(email), anyString(), eq(VerificationPurpose.SIGNUP));
        expectError(AuthErrorCode.EMAIL_SEND_FAILED, request());
        assertUnchanged(original);
    }

    @Test
    void accidentallyRegeneratedOldCodeIsNeverSent() {
        EmailVerification original = existingRequest();
        when(codeGenerator.generate()).thenReturn(OLD_CODE);
        expectError(AuthErrorCode.EMAIL_SEND_FAILED, request());
        assertUnchanged(original);
        verifyNoInteractions(mailSender);
    }

    @Test
    void remainingLifetimeIsRoundedUpAfterDelivery() {
        existingRequest();
        when(clock.instant()).thenReturn(NOW, NOW, NOW.plusMillis(2100));
        assertThat(resendService.resend(request()).expiresIn()).isEqualTo(298);
    }

    @Test
    void simultaneousResendsAllowOnlyOneCommittedDelivery() throws Exception {
        EmailVerification original = existingRequest();
        var results = concurrent(false);
        assertThat(results).containsExactlyInAnyOrder("sent", "AUTH-0004");
        assertThat(latestRequest().getId()).isEqualTo(original.getId());
        assertThat(latestRequest().getAttemptCount()).isEqualTo(2);
        assertThat(logCount()).isEqualTo(2);
        verify(mailSender).sendVerificationCode(email, NEW_CODE, VerificationPurpose.SIGNUP);
    }

    @Test
    void initialSendAndResendShareTheSameEmailLock() throws Exception {
        existingRequest();
        assertThat(concurrent(true)).containsExactlyInAnyOrder("sent", "AUTH-0004");
        assertThat(latestRequest().getAttemptCount()).isEqualTo(2);
        assertThat(logCount()).isEqualTo(2);
        verify(mailSender).sendVerificationCode(email, NEW_CODE, VerificationPurpose.SIGNUP);
    }

    private String[] concurrent(boolean includeInitialSend) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> invokeConcurrent(ready, start, false));
            var second = executor.submit(() -> invokeConcurrent(ready, start, includeInitialSend));
            boolean allReady = ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            assertThat(allReady).isTrue();
            return new String[]{first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)};
        } finally { start.countDown(); }
    }

    private String invokeConcurrent(CountDownLatch ready, CountDownLatch start, boolean initial) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("Test start timed out"); }
        try {
            if (initial) { sendService.send(request()); }
            else { resendService.resend(request()); }
            return "sent";
        } catch (AuthException exception) {
            return exception.getBaseErrorCode().getCode();
        }
    }

    private EmailVerification existingRequest() {
        EmailVerification verification = EmailVerification.request(email, VerificationPurpose.SIGNUP,
                codeHasher.hash(email, VerificationPurpose.SIGNUP, OLD_CODE), NOW.plusSeconds(100), 2);
        verification.markSent(NOW.minusSeconds(60));
        EmailVerification stored = verificationRepository.saveAndFlush(verification);
        sendLogRepository.saveAndFlush(EmailVerificationSendLog.sent(email, VerificationPurpose.SIGNUP, NOW.minusSeconds(60)));
        return stored;
    }

    private EmailVerificationRequest request() {
        return new EmailVerificationRequest(email, VerificationPurpose.SIGNUP);
    }

    private EmailVerification latestRequest() {
        return verificationRepository.findFirstByEmailIgnoreCaseAndPurposeOrderByIdDesc(email, VerificationPurpose.SIGNUP).orElseThrow();
    }

    private long logCount() {
        return sendLogRepository.countByEmailIgnoreCaseAndSentAtGreaterThanEqual(email, Instant.EPOCH);
    }

    private void expectError(AuthErrorCode expected, EmailVerificationRequest request) {
        AuthException error = assertThrows(AuthException.class, () -> resendService.resend(request));
        assertThat(error.getBaseErrorCode()).isSameAs(expected);
    }

    private void assertUnchanged(EmailVerification original) {
        EmailVerification actual = latestRequest();
        assertThat(actual.getCodeHash()).isEqualTo(original.getCodeHash());
        assertThat(actual.getExpiresAt()).isEqualTo(original.getExpiresAt());
        assertThat(actual.getAttemptCount()).isEqualTo(original.getAttemptCount());
        assertThat(logCount()).isEqualTo(1);
    }
}
