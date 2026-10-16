package com.bareum.server.domain.auth.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bareum.server.domain.auth.dto.request.EmailVerificationConfirmRequest;
import com.bareum.server.domain.auth.dto.request.EmailVerificationRequest;
import com.bareum.server.domain.auth.dto.response.EmailVerificationConfirmResponse;
import com.bareum.server.domain.auth.dto.response.EmailVerificationDeliveryResponse;
import com.bareum.server.domain.auth.entity.EmailVerification;
import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.repository.EmailVerificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "auth.email-verification.minimum-send-interval-seconds=60",
        "auth.email-verification.max-sends-per-hour=5",
        "auth.email-verification.max-sends-per-day=20",
        "auth.email-verification.max-verification-attempts=5",
        "auth.email-verification.hash-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "auth.email-verification.outbox.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "auth.email-verification.outbox.poll-delay-ms=3600000"
})
class EmailVerificationResendConfirmationIntegrationTests {
    private static final Instant NOW = Instant.parse("2026-10-16T01:00:00Z");
    private final String email = "resend-confirm-" + UUID.randomUUID() + "@example.invalid";
    private final AtomicReference<Instant> time = new AtomicReference<>(NOW);
    @Autowired private EmailVerificationSendService send;
    @Autowired private EmailVerificationResendService resend;
    @Autowired private EmailVerificationConfirmService confirm;
    @Autowired private EmailVerificationDeliveryService delivery;
    @Autowired private EmailVerificationRepository verifications;
    @Autowired private EmailVerificationTokenGenerator tokens;
    @Autowired private EmailOutboxWorker worker;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private EmailVerificationMailSender sender;
    @MockitoBean private EmailVerificationCodeGenerator codes;
    @MockitoBean private Clock emailVerificationClock;

    @BeforeEach
    void setUp() {
        when(emailVerificationClock.instant()).thenAnswer(invocation -> time.get());
        when(codes.generate()).thenReturn("012345", "654321");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM email_verification_send_log WHERE email=?", email);
        jdbc.update("DELETE FROM email_verification_outbox WHERE email=?", email);
        jdbc.update("DELETE FROM email_verification WHERE email=?", email);
    }

    private EmailVerificationRequest request() {
        return new EmailVerificationRequest(email, VerificationPurpose.SIGNUP);
    }

    private EmailVerificationConfirmRequest confirmation(String code) {
        return new EmailVerificationConfirmRequest(email, VerificationPurpose.SIGNUP, code);
    }

    private EmailVerification latest() {
        return verifications.findFirstByEmailIgnoreCaseAndPurposeOrderByIdDesc(
                email, VerificationPurpose.SIGNUP).orElseThrow();
    }

    private void expectConfirmationError(AuthErrorCode error, String code) {
        assertSame(error, assertThrows(AuthException.class,
                () -> confirm.confirm(confirmation(code))).getBaseErrorCode());
    }

    private EmailVerificationDeliveryResponse delivered() {
        var initial = send.send(request());
        assertTrue(worker.runOnce());
        time.set(NOW.plusSeconds(61));
        return initial;
    }

    @Test
    void resendInvalidatesOldCodeAndDeliveryThenNewCodeIssuesProof() {
        var initial = delivered();
        expectConfirmationError(AuthErrorCode.EMAIL_VERIFICATION_CODE_MISMATCH, "999999");
        long id = latest().getId();
        var replacement = resend.resend(request());
        assertEquals(id, latest().getId());
        assertEquals(1, latest().getAttemptCount());
        assertEquals("CANCELLED", delivery.status(initial.requestId()).orElseThrow().status());
        assertEquals(0, delivery.status(initial.requestId()).orElseThrow().expiresIn());
        expectConfirmationError(AuthErrorCode.EMAIL_VERIFICATION_INVALID, "012345");
        expectConfirmationError(AuthErrorCode.EMAIL_VERIFICATION_INVALID, "654321");
        assertEquals(1, latest().getAttemptCount());
        assertTrue(worker.runOnce());
        assertEquals("SENT", delivery.status(replacement.requestId()).orElseThrow().status());
        expectConfirmationError(AuthErrorCode.EMAIL_VERIFICATION_CODE_MISMATCH, "012345");
        var proof = confirm.confirm(confirmation("654321"));
        assertEquals(2, latest().getAttemptCount());
        assertEquals(tokens.hash(proof.verificationToken()), latest().getVerificationTokenHash());
        assertEquals(time.get().plusSeconds(600), latest().getVerificationExpiresAt());
        expectConfirmationError(AuthErrorCode.EMAIL_VERIFICATION_ALREADY_VERIFIED, "654321");
    }

    @Test
    void resendPreservesCompletedProofUntilRecoveryPolicyIsApplied() {
        delivered();
        var proof = confirm.confirm(confirmation("012345"));
        var before = latest();
        assertSame(AuthErrorCode.EMAIL_VERIFICATION_NOT_CONFIGURED,
                assertThrows(AuthException.class, () -> resend.resend(request())).getBaseErrorCode());
        var after = latest();
        assertEquals(before.getCodeHash(), after.getCodeHash());
        assertEquals(tokens.hash(proof.verificationToken()), after.getVerificationTokenHash());
        assertEquals(before.getVerifiedAt(), after.getVerifiedAt());
        assertEquals(before.getVerificationExpiresAt(), after.getVerificationExpiresAt());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT count(*) FROM email_verification_outbox WHERE email=?", Long.class, email));
    }

    @Test
    void queueInsertFailureRollsBackReplacementAndOldCodeStillConfirms() {
        var initial = delivered();
        var before = latest();
        String trigger = "resend_enqueue_failure_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE FUNCTION " + trigger + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.verification_id = " + before.getId()
                + " THEN RAISE EXCEPTION 'test failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON email_verification_outbox "
                + "FOR EACH ROW EXECUTE FUNCTION " + trigger + "()");
        try {
            assertThrows(RuntimeException.class, () -> resend.resend(request()));
            var after = latest();
            assertEquals(before.getCodeHash(), after.getCodeHash());
            assertEquals(before.getLastSentAt(), after.getLastSentAt());
            assertEquals(before.getExpiresAt(), after.getExpiresAt());
            assertEquals(before.getAttemptCount(), after.getAttemptCount());
            assertEquals("SENT", delivery.status(initial.requestId()).orElseThrow().status());
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger + " ON email_verification_outbox");
            jdbc.execute("DROP FUNCTION " + trigger + "()");
        }
        assertNotNull(confirm.confirm(confirmation("012345")).verificationToken());
    }

    @Test
    void concurrentConfirmationAndResendCommitOnlyOneEligibleOutcome() throws Exception {
        delivered();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var verification = executor.submit(() -> {
                start.await();
                return attempt(() -> confirm.confirm(confirmation("012345")));
            });
            var replacement = executor.submit(() -> {
                start.await();
                return attempt(() -> resend.resend(request()));
            });
            start.countDown();
            Object verified = verification.get(20, TimeUnit.SECONDS);
            Object resent = replacement.get(20, TimeUnit.SECONDS);
            if (verified instanceof EmailVerificationConfirmResponse proof) {
                assertSame(AuthErrorCode.EMAIL_VERIFICATION_NOT_CONFIGURED, resent);
                assertEquals(tokens.hash(proof.verificationToken()), latest().getVerificationTokenHash());
            } else {
                assertSame(AuthErrorCode.EMAIL_VERIFICATION_INVALID, verified);
                assertInstanceOf(EmailVerificationDeliveryResponse.class, resent);
                assertNull(latest().getVerificationTokenHash());
                assertTrue(worker.runOnce());
                expectConfirmationError(AuthErrorCode.EMAIL_VERIFICATION_CODE_MISMATCH, "012345");
                assertNotNull(confirm.confirm(confirmation("654321")).verificationToken());
            }
        }
    }

    private Object attempt(Supplier<?> action) {
        try {
            return action.get();
        } catch (AuthException exception) {
            return exception.getBaseErrorCode();
        }
    }
}
