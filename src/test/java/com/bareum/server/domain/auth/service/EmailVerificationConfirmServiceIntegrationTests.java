package com.bareum.server.domain.auth.service;

import static org.junit.jupiter.api.Assertions.*;

import com.bareum.server.domain.auth.config.EmailVerificationProperties;
import com.bareum.server.domain.auth.dto.request.EmailVerificationConfirmRequest;
import com.bareum.server.domain.auth.entity.EmailVerification;
import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.repository.EmailVerificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        // 제한 횟수와 재설정 기간은 테스트 전용 값이며 팀 정책이 아닙니다.
        "auth.email-verification.max-verification-attempts=3",
        "auth.email-verification.password-reset-verification-ttl-seconds=420",
        "auth.email-verification.hash-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class EmailVerificationConfirmServiceIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");
    private static final String CODE = "012345";
    private final String email = "confirm-" + UUID.randomUUID() + "@example.invalid";

    @Autowired private EmailVerificationConfirmService service;
    @Autowired private EmailVerificationRepository repository;
    @Autowired private EmailVerificationCodeHasher hasher;
    @Autowired private EmailVerificationTokenGenerator tokens;
    @Autowired private EmailVerificationProperties properties;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean(name = "emailVerificationClock") private Clock clock;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        properties.setMaxVerificationAttempts(3);
        properties.setPasswordResetVerificationTtlSeconds(420L);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM email_verification WHERE email = ?", email);
        properties.setMaxVerificationAttempts(3);
        properties.setPasswordResetVerificationTtlSeconds(420L);
    }

    private EmailVerification seed(VerificationPurpose purpose, Instant expiresAt) {
        EmailVerification row = EmailVerification.request(email, purpose,
                hasher.hash(email, purpose, CODE), expiresAt);
        row.markSent(NOW.minusSeconds(10));
        return repository.saveAndFlush(row);
    }

    private EmailVerificationConfirmRequest request(String code) {
        return new EmailVerificationConfirmRequest(email, VerificationPurpose.SIGNUP, code);
    }

    private EmailVerification latest() {
        return repository.findFirstByEmailIgnoreCaseAndPurposeOrderByIdDesc(
                email, VerificationPurpose.SIGNUP).orElseThrow();
    }

    private void expectError(AuthErrorCode error, EmailVerificationConfirmRequest request) {
        assertSame(error, assertThrows(AuthException.class, () -> service.confirm(request)).getBaseErrorCode());
    }

    @Test
    void successCommitsOnlyTokenHashAndTenMinuteExpiry() {
        seed(VerificationPurpose.SIGNUP, NOW.plusSeconds(300));
        var response = service.confirm(request(CODE));
        var row = latest();
        assertEquals(600, response.expiresIn());
        assertEquals(43, response.verificationToken().length());
        assertNotEquals(response.verificationToken(), row.getVerificationTokenHash());
        assertEquals(tokens.hash(response.verificationToken()), row.getVerificationTokenHash());
        assertEquals(NOW, row.getVerifiedAt());
        assertEquals(NOW.plusSeconds(600), row.getVerificationExpiresAt());
        assertNull(row.getConsumedAt());
        assertEquals(0, row.getAttemptCount());
    }

    @Test
    void mismatchCommitsCountDespiteErrorAndStopsAtConfiguredLimit() {
        seed(VerificationPurpose.SIGNUP, NOW.plusSeconds(300));
        for (int i = 1; i <= 3; i++) {
            expectError(AuthErrorCode.EMAIL_VERIFICATION_CODE_MISMATCH, request("999999"));
            assertEquals(i, latest().getAttemptCount());
        }
        expectError(AuthErrorCode.EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED, request(CODE));
        assertEquals(3, latest().getAttemptCount());
        assertNull(latest().getVerificationTokenHash());
    }

    @Test
    void expiryBoundaryIsRejectedWithoutIncreasingCount() {
        seed(VerificationPurpose.SIGNUP, NOW);
        expectError(AuthErrorCode.EMAIL_VERIFICATION_EXPIRED, request(CODE));
        assertEquals(0, latest().getAttemptCount());
    }

    @Test
    void missingRequestAndWrongPurposeAreRejected() {
        expectError(AuthErrorCode.EMAIL_VERIFICATION_INVALID, request(CODE));
        seed(VerificationPurpose.PASSWORD_RESET, NOW.plusSeconds(300));
        expectError(AuthErrorCode.EMAIL_VERIFICATION_INVALID, request(CODE));
    }

    @Test
    void consumedRequestCannotIssueProof() {
        var row = seed(VerificationPurpose.SIGNUP, NOW.plusSeconds(300));
        jdbc.update("UPDATE email_verification SET consumed_at = ? WHERE id = ?",
                java.sql.Timestamp.from(NOW), row.getId());
        expectError(AuthErrorCode.EMAIL_VERIFICATION_INVALID, request(CODE));
        assertNull(latest().getVerifiedAt());
    }

    @Test
    void alreadyVerifiedRequestKeepsOriginalProof() {
        seed(VerificationPurpose.SIGNUP, NOW.plusSeconds(300));
        var response = service.confirm(request(CODE));
        expectError(AuthErrorCode.EMAIL_VERIFICATION_ALREADY_VERIFIED, request(CODE));
        assertEquals(tokens.hash(response.verificationToken()), latest().getVerificationTokenHash());
    }

    @Test
    void onlyLatestRequestCanBeConfirmed() {
        var old = seed(VerificationPurpose.SIGNUP, NOW.plusSeconds(300));
        var newest = EmailVerification.request(email, VerificationPurpose.SIGNUP,
                hasher.hash(email, VerificationPurpose.SIGNUP, "654321"), NOW.plusSeconds(300));
        newest.markSent(NOW);
        repository.saveAndFlush(newest);
        expectError(AuthErrorCode.EMAIL_VERIFICATION_CODE_MISMATCH, request(CODE));
        assertEquals(0, repository.findById(old.getId()).orElseThrow().getAttemptCount());
        assertEquals(1, latest().getAttemptCount());
        assertNotNull(service.confirm(request("654321")).verificationToken());
    }

    @Test
    void emailCaseMatchesExistingSendHashRules() {
        seed(VerificationPurpose.SIGNUP, NOW.plusSeconds(300));
        assertNotNull(service.confirm(new EmailVerificationConfirmRequest(
                email.toUpperCase(java.util.Locale.ROOT), VerificationPurpose.SIGNUP, CODE)).verificationToken());
    }

    @Test
    void missingFailurePolicyBlocksConfirmation() {
        seed(VerificationPurpose.SIGNUP, NOW.plusSeconds(300));
        properties.setMaxVerificationAttempts(null);
        expectError(AuthErrorCode.EMAIL_VERIFICATION_NOT_CONFIGURED, request(CODE));
        assertNull(latest().getVerifiedAt());
    }

    @Test
    void resetProofLifetimeRequiresSeparateConfiguration() {
        var row = seed(VerificationPurpose.PASSWORD_RESET, NOW.plusSeconds(300));
        var request = new EmailVerificationConfirmRequest(email, VerificationPurpose.PASSWORD_RESET, CODE);
        properties.setPasswordResetVerificationTtlSeconds(null);
        expectError(AuthErrorCode.EMAIL_VERIFICATION_NOT_CONFIGURED, request);
        properties.setPasswordResetVerificationTtlSeconds(420L);
        assertEquals(420, service.confirm(request).expiresIn());
        assertEquals(NOW.plusSeconds(420), repository.findById(row.getId()).orElseThrow().getVerificationExpiresAt());
    }

    @Test
    void concurrentWrongCodesCannotLoseFailureCountsOrBypassLimit() throws Exception {
        seed(VerificationPurpose.SIGNUP, NOW.plusSeconds(300));
        try (var executor = Executors.newFixedThreadPool(5)) {
            var calls = IntStream.range(0, 5).mapToObj(i -> (Callable<AuthErrorCode>) () -> {
                try {
                    service.confirm(request("999999"));
                    throw new AssertionError("wrong code accepted");
                } catch (AuthException error) {
                    return (AuthErrorCode) error.getBaseErrorCode();
                }
            }).toList();
            var results = executor.invokeAll(calls);
            int mismatches = 0;
            int blocked = 0;
            for (var result : results) {
                var error = result.get();
                if (error == AuthErrorCode.EMAIL_VERIFICATION_CODE_MISMATCH) mismatches++;
                else if (error == AuthErrorCode.EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED) blocked++;
                else fail("unexpected authentication error");
            }
            assertEquals(3, mismatches);
            assertEquals(2, blocked);
        }
        assertEquals(3, latest().getAttemptCount());
    }

    @Test
    void concurrentCorrectCodesIssueOnlyOneProof() throws Exception {
        seed(VerificationPurpose.SIGNUP, NOW.plusSeconds(300));
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> call = () -> {
                try {
                    service.confirm(request(CODE));
                    return true;
                } catch (AuthException error) {
                    assertSame(AuthErrorCode.EMAIL_VERIFICATION_ALREADY_VERIFIED, error.getBaseErrorCode());
                    return false;
                }
            };
            var results = executor.invokeAll(java.util.List.of(call, call));
            int successes = 0;
            for (var result : results) if (result.get()) successes++;
            assertEquals(1, successes);
        }
    }
}
