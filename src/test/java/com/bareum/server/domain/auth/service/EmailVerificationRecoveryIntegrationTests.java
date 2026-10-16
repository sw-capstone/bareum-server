package com.bareum.server.domain.auth.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.bareum.server.domain.auth.dto.request.EmailSignupRequest;
import com.bareum.server.domain.auth.dto.request.EmailVerificationConfirmRequest;
import com.bareum.server.domain.auth.dto.request.EmailVerificationRequest;
import com.bareum.server.domain.auth.entity.EmailVerification;
import com.bareum.server.domain.auth.entity.EmailVerificationSendLog;
import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.repository.EmailVerificationRepository;
import com.bareum.server.domain.auth.repository.EmailVerificationSendLogRepository;
import com.bareum.server.domain.term.dto.request.SignupAgreementsRequest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "auth.email-verification.minimum-send-interval-seconds=60",
        "auth.email-verification.max-sends-per-hour=5",
        "auth.email-verification.max-sends-per-day=20",
        "auth.email-verification.password-reset-code-ttl-seconds=300",
        "auth.email-verification.hash-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "auth.email-verification.outbox.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "auth.email-verification.outbox.poll-delay-ms=3600000"
})
class EmailVerificationRecoveryIntegrationTests {
    private static final Instant NOW = Instant.parse("2026-10-16T00:00:00Z");
    private static final String OLD = "123456", NEW = "654321";
    private final String email = "recovery-" + UUID.randomUUID() + "@example.invalid";
    private final AtomicReference<Instant> time = new AtomicReference<>(NOW);
    @Autowired private EmailVerificationSendService initial;
    @Autowired private EmailVerificationResendService resend;
    @Autowired private EmailVerificationConfirmService confirm;
    @Autowired private EmailSignupService signup;
    @Autowired private EmailOutboxWorker worker;
    @Autowired private EmailVerificationRepository rows;
    @Autowired private EmailVerificationSendLogRepository logs;
    @Autowired private EmailVerificationCodeHasher hasher;
    @Autowired private EmailVerificationTokenGenerator tokens;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @MockitoBean private EmailVerificationMailSender sender;
    @MockitoBean private EmailVerificationCodeGenerator generator;
    @MockitoBean(name = "emailVerificationClock") private Clock clock;

    @BeforeEach
    void setup() {
        when(clock.instant()).thenAnswer(invocation -> time.get());
        when(generator.generate()).thenReturn(NEW);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM email_verification_send_log WHERE upper(email)=upper(?)", email);
        jdbc.update("DELETE FROM email_verification_outbox WHERE upper(email)=upper(?)", email);
        jdbc.update("DELETE FROM email_verification WHERE upper(email)=upper(?)", email);
    }

    @Test
    void fifthFailureCommitsCooldownAndConcurrentGuessesCannotExceedBudget() throws Exception {
        seed(VerificationPurpose.SIGNUP);
        try (var executor = Executors.newFixedThreadPool(8)) {
            Callable<AuthErrorCode> guess = () -> {
                try {
                    confirm.confirm(code("999999"));
                    throw new AssertionError("Wrong code accepted");
                } catch (AuthException failure) {
                    return (AuthErrorCode) failure.getBaseErrorCode();
                }
            };
            var results = executor.invokeAll(java.util.Collections.nCopies(8, guess));
            int mismatches = 0, blocked = 0;
            for (var result : results) {
                if (result.get() == AuthErrorCode.EMAIL_VERIFICATION_CODE_MISMATCH) mismatches++;
                else if (result.get() == AuthErrorCode.EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED) blocked++;
                else fail("Unexpected verification error");
            }
            assertEquals(5, mismatches);
            assertEquals(3, blocked);
        }
        assertEquals(5, latest().getAttemptCount());
        assertEquals(NOW.plusSeconds(900), latest().getAttemptsBlockedUntil());
    }

    @Test
    void initialAndResendCannotBypassCooldownUsingEmailCaseOrFreshRequest() {
        seed(VerificationPurpose.SIGNUP);
        exhaust();
        time.set(NOW.plusSeconds(899));
        expect(AuthErrorCode.EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED,
                () -> initial.send(new EmailVerificationRequest(email.toUpperCase(Locale.ROOT), VerificationPurpose.SIGNUP)));
        expect(AuthErrorCode.EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED, () -> resend.resend(request()));
        assertEquals(0L, jobs());
        assertEquals(5, latest().getAttemptCount());
        verify(generator, never()).generate();
    }

    @Test
    void exactCooldownBoundaryAllowsNewResendButNeverRevivesOldCode() {
        var id = seed(VerificationPurpose.SIGNUP).getId();
        exhaust();
        time.set(NOW.plusSeconds(900));
        var delivery = resend.resend(request());
        assertEquals("PENDING", delivery.status());
        assertEquals(id, latest().getId());
        assertEquals(0, latest().getAttemptCount());
        assertNull(latest().getAttemptsBlockedUntil());
        expect(AuthErrorCode.EMAIL_VERIFICATION_INVALID, () -> confirm.confirm(code(NEW)));
        assertTrue(worker.runOnce());
        expect(AuthErrorCode.EMAIL_VERIFICATION_CODE_MISMATCH, () -> confirm.confirm(code(OLD)));
        assertEquals(600, confirm.confirm(code(NEW)).expiresIn());
    }

    @Test
    void initialRequestAfterCooldownCreatesFreshBudgetAndInvalidatesEarlierRequest() {
        var old = seed(VerificationPurpose.SIGNUP);
        exhaust();
        time.set(NOW.plusSeconds(900));
        initial.send(request());
        assertNotEquals(old.getId(), latest().getId());
        assertEquals(0, latest().getAttemptCount());
        assertEquals(5, rows.findById(old.getId()).orElseThrow().getAttemptCount());
        assertTrue(worker.runOnce());
        assertNotNull(confirm.confirm(code(NEW)).verificationToken());
    }

    @Test
    void ordinaryResendKeepsFailuresUntilThresholdCooldownHasActuallyElapsed() {
        seed(VerificationPurpose.SIGNUP);
        expect(AuthErrorCode.EMAIL_VERIFICATION_CODE_MISMATCH, () -> confirm.confirm(code("999999")));
        expect(AuthErrorCode.EMAIL_VERIFICATION_CODE_MISMATCH, () -> confirm.confirm(code("999999")));
        time.set(NOW.plusSeconds(1000));
        resend.resend(request());
        assertEquals(2, latest().getAttemptCount());
        assertNull(latest().getAttemptsBlockedUntil());
    }

    @Test
    void lostProofRequiresFreshEmailVerificationAndOldProofCannotCreateMember() {
        seed(VerificationPurpose.SIGNUP);
        var original = confirm.confirm(code(OLD));
        resend.resend(request());
        assertNull(latest().getVerifiedAt());
        assertNull(latest().getVerificationTokenHash());
        assertNull(latest().getVerificationExpiresAt());
        expect(AuthErrorCode.EMAIL_VERIFICATION_INVALID,
                () -> signup.signup(new EmailSignupRequest(email, "test", "ValidPassword1!",
                        original.verificationToken(), new SignupAgreementsRequest(true, true, true, false))));
        expect(AuthErrorCode.EMAIL_VERIFICATION_INVALID, () -> confirm.confirm(code(NEW)));
        assertTrue(worker.runOnce());
        var replacement = confirm.confirm(code(NEW));
        assertNotEquals(original.verificationToken(), replacement.verificationToken());
        assertEquals(tokens.hash(replacement.verificationToken()), latest().getVerificationTokenHash());
    }

    @Test
    void rejectedRecoveryPreservesCompletedProofAndItsOriginalExpiry() {
        seed(VerificationPurpose.SIGNUP);
        var original = confirm.confirm(code(OLD));
        var expiry = latest().getVerificationExpiresAt();
        jdbc.update("UPDATE email_verification_send_log SET sent_at=? WHERE email=?",
                Timestamp.from(NOW), email);
        expect(AuthErrorCode.EMAIL_SEND_LIMIT_EXCEEDED, () -> resend.resend(request()));
        assertEquals(tokens.hash(original.verificationToken()), latest().getVerificationTokenHash());
        assertEquals(expiry, latest().getVerificationExpiresAt());
        assertEquals(0L, jobs());
    }

    @Test
    void queueFailureRollsBackProofRevocationAndLeavesNoJob() {
        seed(VerificationPurpose.SIGNUP);
        var original = confirm.confirm(code(OLD));
        String name = "recovery_failure_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE FUNCTION " + name
                + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.email='" + email
                + "' THEN RAISE EXCEPTION 'simulated queue failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER " + name
                + " BEFORE INSERT ON email_verification_outbox FOR EACH ROW EXECUTE FUNCTION " + name + "()");
        try {
            assertThrows(RuntimeException.class, () -> resend.resend(request()));
            assertEquals(tokens.hash(original.verificationToken()), latest().getVerificationTokenHash());
            assertEquals(0L, jobs());
        } finally {
            jdbc.execute("DROP TRIGGER " + name + " ON email_verification_outbox");
            jdbc.execute("DROP FUNCTION " + name + "()");
        }
    }

    @Test
    void enclosingRollbackPreservesExhaustedBudgetAndCooldown() {
        seed(VerificationPurpose.SIGNUP);
        exhaust();
        time.set(NOW.plusSeconds(900));
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            resend.resend(request());
            status.setRollbackOnly();
        });
        assertEquals(5, latest().getAttemptCount());
        assertEquals(NOW.plusSeconds(900), latest().getAttemptsBlockedUntil());
        assertEquals(0L, jobs());
    }

    @Test
    void legacyExhaustedRowsRecoverWithoutExtendingCooldownOnRejectedRequests() {
        var row = seed(VerificationPurpose.SIGNUP);
        jdbc.update("UPDATE email_verification SET attempt_count=5 WHERE id=?", row.getId());
        time.set(NOW.plusSeconds(839));
        expect(AuthErrorCode.EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED, () -> resend.resend(request()));
        time.set(NOW.plusSeconds(840));
        resend.resend(request());
        assertEquals(0, latest().getAttemptCount());
    }

    @Test
    void consumedProofCannotBeReplacedByRecovery() {
        var row = seed(VerificationPurpose.SIGNUP);
        confirm.confirm(code(OLD));
        jdbc.update("UPDATE email_verification SET consumed_at=? WHERE id=?", Timestamp.from(NOW), row.getId());
        expect(AuthErrorCode.EMAIL_VERIFICATION_INVALID, () -> resend.resend(request()));
        assertEquals(0L, jobs());
    }

    @Test
    void resetProofHasItsOwnTenMinuteDefault() {
        seed(VerificationPurpose.PASSWORD_RESET);
        var response = confirm.confirm(new EmailVerificationConfirmRequest(email, VerificationPurpose.PASSWORD_RESET, OLD));
        assertEquals(600, response.expiresIn());
        var row = rows.findFirstByEmailIgnoreCaseAndPurposeOrderByIdDesc(email, VerificationPurpose.PASSWORD_RESET).orElseThrow();
        assertEquals(NOW.plusSeconds(600), row.getVerificationExpiresAt());
    }

    private EmailVerification seed(VerificationPurpose purpose) {
        var row = EmailVerification.request(email, purpose, hasher.hash(email, purpose, OLD), NOW.plusSeconds(300));
        row.markSent(NOW.minusSeconds(60));
        var saved = rows.saveAndFlush(row);
        logs.saveAndFlush(EmailVerificationSendLog.sent(email, purpose, NOW.minusSeconds(60)));
        return saved;
    }

    private EmailVerification latest() {
        return rows.findFirstByEmailIgnoreCaseAndPurposeOrderByIdDesc(email, VerificationPurpose.SIGNUP).orElseThrow();
    }

    private void exhaust() {
        for (int i = 0; i < 5; i++)
            expect(AuthErrorCode.EMAIL_VERIFICATION_CODE_MISMATCH, () -> confirm.confirm(code("999999")));
    }

    private long jobs() {
        return jdbc.queryForObject("SELECT count(*) FROM email_verification_outbox WHERE email=?", Long.class, email);
    }

    private EmailVerificationRequest request() {
        return new EmailVerificationRequest(email, VerificationPurpose.SIGNUP);
    }

    private EmailVerificationConfirmRequest code(String value) {
        return new EmailVerificationConfirmRequest(email, VerificationPurpose.SIGNUP, value);
    }

    private void expect(AuthErrorCode error, Runnable action) {
        assertSame(error, assertThrows(AuthException.class, action::run).getBaseErrorCode());
    }
}
