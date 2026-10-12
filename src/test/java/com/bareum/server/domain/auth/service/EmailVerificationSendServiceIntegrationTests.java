package com.bareum.server.domain.auth.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.bareum.server.domain.auth.config.EmailOutboxProperties;
import com.bareum.server.domain.auth.dto.request.EmailVerificationRequest;
import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.repository.EmailOutboxRepository;
import com.bareum.server.domain.auth.repository.EmailVerificationRepository;
import com.bareum.server.domain.auth.repository.EmailVerificationSendLogRepository;
import com.bareum.server.domain.auth.support.TestSmtpServer;
import com.bareum.server.domain.member.entity.Member;
import com.bareum.server.domain.member.enums.MemberStatus;
import com.bareum.server.domain.member.repository.MemberRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "auth.email-verification.signup-code-ttl-seconds=300",
        "auth.email-verification.password-reset-code-ttl-seconds=420",
        "auth.email-verification.minimum-send-interval-seconds=60",
        "auth.email-verification.max-sends-per-hour=5",
        "auth.email-verification.max-sends-per-day=20",
        "auth.email-verification.hash-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "auth.email-verification.outbox.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "auth.email-verification.outbox.poll-delay-ms=3600000"
})
class EmailVerificationSendServiceIntegrationTests {
    private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");
    private final AtomicReference<Instant> time = new AtomicReference<>(NOW);
    private final String email = "outbox-" + UUID.randomUUID() + "@example.invalid";
    @Autowired private EmailVerificationSendService service;
    @Autowired private EmailVerificationResendService resendService;
    @Autowired private EmailVerificationDeliveryService delivery;
    @Autowired private EmailOutboxWorker worker;
    @Autowired private EmailOutboxRepository outbox;
    @Autowired private EmailOutboxProperties properties;
    @Autowired private EmailOutboxCipher cipher;
    @Autowired private EmailVerificationCodeHasher hasher;
    @Autowired private EmailVerificationRepository verifications;
    @Autowired private EmailVerificationSendLogRepository logs;
    @Autowired private MemberRepository members;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    @MockitoBean private EmailVerificationMailSender sender;
    @MockitoBean private Clock emailVerificationClock;

    @BeforeEach
    void setUp() {
        when(emailVerificationClock.instant()).thenAnswer(invocation -> time.get());
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM email_verification_send_log WHERE email=?",email);
        jdbc.update("DELETE FROM email_verification_outbox WHERE email=?",email);
        jdbc.update("DELETE FROM email_verification WHERE email=?",email);
        jdbc.update("DELETE FROM member WHERE email=?",email);
    }

    @Test
    void enqueueCommitsPendingEncryptedPayloadWithoutCallingSmtp() {
        var response = service.send(request());
        assertEquals("PENDING",response.status());
        assertEquals(0,response.expiresIn());
        verify(sender,never()).sendVerificationCode(anyString(),anyString(),any());
        var v = verification();
        assertNull(v.getLastSentAt());
        assertEquals(NOW,v.getExpiresAt());
        assertEquals(0,logs.countByEmailIgnoreCaseAndSentAtGreaterThanEqual(email,Instant.EPOCH));
        var job = claim();
        var code = cipher.decrypt(job.id(),job.verificationId(),
                EmailOutboxCipher.context(job.email(),job.purpose(),job.ttlSeconds(),job.codeHash()),job.keyId(),job.encryptedCode());
        assertTrue(code.matches("[0-9]{6}"));
        assertTrue(hasher.matches(email,VerificationPurpose.SIGNUP,code,v.getCodeHash()));
        assertFalse(new String(job.encryptedCode(),java.nio.charset.StandardCharsets.UTF_8).contains(code));
    }

    @Test
    void enclosingTransactionRollbackLeavesNoJobOrMail() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            service.send(request());
            status.setRollbackOnly();
        });
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM email_verification_outbox WHERE email=?",Long.class,email));
        assertTrue(verifications.findFirstByEmailIgnoreCaseAndPurposeOrderByIdDesc(email,VerificationPurpose.SIGNUP).isEmpty());
        assertFalse(worker.runOnce());
        verify(sender,never()).sendVerificationCode(anyString(),anyString(),any());
    }

    @Test
    void successRunsOutsideTransactionAndStartsFullValidityAtSmtpAcceptance() {
        var response = service.send(request());
        time.set(NOW.plusSeconds(200));
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("SENDING",state(response.requestId()));
            assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND granted",Long.class));
            time.set(NOW.plusSeconds(501));
            return null;
        }).when(sender).sendVerificationCode(eq(email),anyString(),eq(VerificationPurpose.SIGNUP));
        assertTrue(worker.runOnce());
        assertEquals("SENT",state(response.requestId()));
        assertEquals(NOW.plusSeconds(501),verification().getLastSentAt());
        assertEquals(NOW.plusSeconds(801),verification().getExpiresAt());
        assertEquals(300,delivery.status(response.requestId()).orElseThrow().expiresIn());
        assertEquals(NOW.plusSeconds(501),logs.findFirstByEmailIgnoreCaseOrderBySentAtDesc(email).orElseThrow().getSentAt());
        assertNull(jdbc.queryForObject("SELECT encrypted_code FROM email_verification_outbox WHERE id=?",byte[].class,response.requestId()));
        time.set(NOW.plusSeconds(802));
        assertEquals(0,delivery.status(response.requestId()).orElseThrow().expiresIn());
    }

    @Test
    void smtpFailureRetriesSameCodeThenErasesPayloadAtAttemptLimit() {
        var response = service.send(request());
        doThrow(new AuthException(AuthErrorCode.EMAIL_SEND_FAILED)).when(sender)
                .sendVerificationCode(eq(email),anyString(),any());
        assertTrue(worker.runOnce());
        assertEquals("RETRY",state(response.requestId()));
        assertNull(verification().getLastSentAt());
        assertFalse(worker.runOnce());
        for (int i=1;i<3;i++) {
            time.set(NOW.plusSeconds(i*5));
            assertTrue(worker.runOnce());
        }
        var code = ArgumentCaptor.forClass(String.class);
        verify(sender,times(3)).sendVerificationCode(eq(email),code.capture(),any());
        assertEquals(1,code.getAllValues().stream().distinct().count());
        assertEquals("FAILED",delivery.status(response.requestId()).orElseThrow().status());
        assertEquals(0,delivery.status(response.requestId()).orElseThrow().expiresIn());
        assertNull(jdbc.queryForObject("SELECT encrypted_code FROM email_verification_outbox WHERE id=?",byte[].class,response.requestId()));
        assertEquals(0,logs.countByEmailIgnoreCaseAndSentAtGreaterThanEqual(email,Instant.EPOCH));
    }

    @Test
    void acceptanceThenDatabaseFailureRecoversSameJobAfterLeaseExpiry() {
        var response = service.send(request());
        // Reject exactly this job's success record to reproduce a post-SMTP database failure.
        String function = "outbox_test_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.outbox_id = '" + response.requestId() + "'::uuid THEN RAISE EXCEPTION 'test failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER " + function + " BEFORE INSERT ON email_verification_send_log FOR EACH ROW EXECUTE FUNCTION " + function + "()");
        try {
            assertThrows(RuntimeException.class,() -> worker.runOnce());
            assertEquals("SENDING",state(response.requestId()));
            assertNull(verification().getLastSentAt());
            assertEquals(0,logs.countByEmailIgnoreCaseAndSentAtGreaterThanEqual(email,Instant.EPOCH));
        } finally {
            jdbc.execute("DROP TRIGGER " + function + " ON email_verification_send_log");
            jdbc.execute("DROP FUNCTION " + function + "()");
        }
        time.set(NOW.plusSeconds(121));
        assertTrue(worker.runOnce());
        assertEquals("SENT",state(response.requestId()));
        var code = ArgumentCaptor.forClass(String.class);
        verify(sender,times(2)).sendVerificationCode(eq(email),code.capture(),any());
        assertEquals(code.getAllValues().get(0),code.getAllValues().get(1));
        assertEquals(1,logs.countByEmailIgnoreCaseAndSentAtGreaterThanEqual(email,Instant.EPOCH));
    }

    @Test
    void crashedWorkerIsRecoveredAndStaleLeaseCannotFinalize() {
        service.send(request());
        var old = claim();
        assertFalse(worker.runOnce());
        time.set(NOW.plusSeconds(121));
        var current = claim();
        assertEquals(old.id(),current.id());
        assertNotEquals(old.leaseToken(),current.leaseToken());
        assertEquals(2,current.attemptCount());
        assertEquals(Boolean.FALSE,new TransactionTemplate(manager).execute(status -> outbox.lockOwned(old)));
        new TransactionTemplate(manager).executeWithoutResult(status -> outbox.finish(old,"FAILED",time.get()));
        assertEquals("SENDING",state(current.id()));
    }

    @Test
    void pendingAndCaseVariantRequestsCannotCreateSecondJob() {
        service.send(request());
        var exception = assertThrows(AuthException.class,() -> service.send(new EmailVerificationRequest(
                email.toUpperCase(Locale.ROOT),VerificationPurpose.SIGNUP)));
        assertSame(AuthErrorCode.EMAIL_SEND_LIMIT_EXCEEDED,exception.getBaseErrorCode());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM email_verification_outbox WHERE email=?",Long.class,email));
    }

    @Test
    void queueAgeLimitErasesUnsentPayloadWithoutSending() {
        var response = service.send(request());
        time.set(NOW.plusSeconds(300));
        assertFalse(worker.runOnce());
        assertEquals("FAILED",state(response.requestId()));
        assertNull(jdbc.queryForObject("SELECT encrypted_code FROM email_verification_outbox WHERE id=?",byte[].class,response.requestId()));
        verify(sender,never()).sendVerificationCode(anyString(),anyString(),any());
    }

    @Test
    void supersededOrIneligibleAccountJobIsCancelledWithoutSending() {
        var response = service.send(request());
        members.saveAndFlush(Member.createLocal(email,"test","password-hash"));
        assertTrue(worker.runOnce());
        assertEquals("CANCELLED",state(response.requestId()));
        verify(sender,never()).sendVerificationCode(anyString(),anyString(),any());
    }

    @Test
    void simultaneousWorkersOnlyOneSendsLiveLeasedJob() throws Exception {
        service.send(request());
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            assertTrue(finish.await(10,TimeUnit.SECONDS));
            return null;
        }).when(sender).sendVerificationCode(eq(email),anyString(),any());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(worker::runOnce);
            try {
                assertTrue(started.await(10,TimeUnit.SECONDS));
                assertFalse(executor.submit(worker::runOnce).get(10,TimeUnit.SECONDS));
            } finally {
                finish.countDown();
            }
            assertTrue(first.get(10,TimeUnit.SECONDS));
        }
        verify(sender,times(1)).sendVerificationCode(eq(email),anyString(),any());
    }

    @Test
    void workerRejectsOuterTransactionBeforeClaimingOrSending() {
        service.send(request());
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            assertThrows(IllegalStateException.class,() -> worker.runOnce());
        });
        verify(sender,never()).sendVerificationCode(anyString(),anyString(),any());
    }

    @ParameterizedTest
    @EnumSource(value = MemberStatus.class, names = {"SUSPENDED", "DELETED"})
    void restrictedResetAccountDoesNotCreateVerificationOrJob(MemberStatus status) {
        members.saveAndFlush(Member.createLocal(email, "test", "password-hash"));
        jdbc.update("UPDATE member SET status=? WHERE email=?", status.name(), email);
        var failure = assertThrows(AuthException.class, () -> service.send(
                new EmailVerificationRequest(email, VerificationPurpose.PASSWORD_RESET)));
        assertSame(AuthErrorCode.PASSWORD_RESET_NOT_ALLOWED, failure.getBaseErrorCode());
        assertEquals(0L, jdbc.queryForObject(
                "SELECT count(*) FROM email_verification WHERE email=?", Long.class, email));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT count(*) FROM email_verification_outbox WHERE email=?", Long.class, email));
        verify(sender, never()).sendVerificationCode(anyString(), anyString(), any());
    }

    @Test
    void withdrawalTimestampBlocksResetBeforeQueueing() {
        members.saveAndFlush(Member.createLocal(email, "test", "password-hash"));
        jdbc.update("UPDATE member SET withdrawn_at=? WHERE email=?",
                java.sql.Timestamp.from(NOW), email);
        var failure = assertThrows(AuthException.class, () -> service.send(
                new EmailVerificationRequest(email, VerificationPurpose.PASSWORD_RESET)));
        assertSame(AuthErrorCode.PASSWORD_RESET_NOT_ALLOWED, failure.getBaseErrorCode());
        assertEquals(0L, jdbc.queryForObject(
                "SELECT count(*) FROM email_verification_outbox WHERE email=?", Long.class, email));
        verify(sender, never()).sendVerificationCode(anyString(), anyString(), any());
    }

    @Test
    void accountSuspendedAfterQueueCancelsResetWithoutSmtp() {
        members.saveAndFlush(Member.createLocal(email, "test", "password-hash"));
        var accepted = service.send(new EmailVerificationRequest(email, VerificationPurpose.PASSWORD_RESET));
        jdbc.update("UPDATE member SET status='SUSPENDED' WHERE email=?", email);
        assertTrue(worker.runOnce());
        assertEquals("CANCELLED", delivery.status(accepted.requestId()).orElseThrow().status());
        assertNull(jdbc.queryForObject(
                "SELECT encrypted_code FROM email_verification_outbox WHERE id=?", byte[].class, accepted.requestId()));
        assertEquals(0, logs.countByEmailIgnoreCaseAndSentAtGreaterThanEqual(email, Instant.EPOCH));
        verify(sender, never()).sendVerificationCode(anyString(), anyString(), any());
    }

    @Test
    void suspendedAccountCannotReplacePreviouslySentResetCode() {
        members.saveAndFlush(Member.createLocal(email, "test", "password-hash"));
        var accepted = service.send(new EmailVerificationRequest(email, VerificationPurpose.PASSWORD_RESET));
        assertTrue(worker.runOnce());
        var originalHash = verifications.findFirstByEmailIgnoreCaseAndPurposeOrderByIdDesc(
                email, VerificationPurpose.PASSWORD_RESET).orElseThrow().getCodeHash();
        time.set(NOW.plusSeconds(60));
        jdbc.update("UPDATE member SET status='SUSPENDED' WHERE email=?", email);
        var failure = assertThrows(AuthException.class, () -> resendService.resend(
                new EmailVerificationRequest(email, VerificationPurpose.PASSWORD_RESET)));
        assertSame(AuthErrorCode.PASSWORD_RESET_NOT_ALLOWED, failure.getBaseErrorCode());
        assertEquals(originalHash, verifications.findFirstByEmailIgnoreCaseAndPurposeOrderByIdDesc(
                email, VerificationPurpose.PASSWORD_RESET).orElseThrow().getCodeHash());
        assertEquals("SENT", state(accepted.requestId()));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT count(*) FROM email_verification_outbox WHERE email=?", Long.class, email));
        verify(sender, times(1)).sendVerificationCode(eq(email), anyString(), eq(VerificationPurpose.PASSWORD_RESET));
    }

    @Test
    void resetSmtpProtocolAndDatabaseUseRegisteredAddressDespiteRequestCase() throws Exception {
        members.saveAndFlush(Member.createLocal(email,"test","password-hash"));
        try (var server = new TestSmtpServer(false,false)) {
            var smtp = SmtpEmailVerificationMailSenderTests.sender(
                    SmtpEmailVerificationMailSenderTests.transport(server.port(),1000),"sender@example.invalid");
            doAnswer(invocation -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                smtp.sendVerificationCode(invocation.getArgument(0),invocation.getArgument(1),invocation.getArgument(2));
                return null;
            }).when(sender).sendVerificationCode(eq(email),anyString(),eq(VerificationPurpose.PASSWORD_RESET));
            var response = service.send(new EmailVerificationRequest(email.toUpperCase(Locale.ROOT),VerificationPurpose.PASSWORD_RESET));
            assertTrue(worker.runOnce());
            assertEquals(420,delivery.status(response.requestId()).orElseThrow().expiresIn());
            assertEquals("RCPT TO:<"+email+">",server.recipient());
            assertEquals(email,logs.findFirstByEmailIgnoreCaseOrderBySentAtDesc(email).orElseThrow().getEmail());
        }
    }

    @Test
    void uncommittedJobCannotBeClaimedByAnotherWorker() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                service.send(request());
                try {
                    assertFalse(executor.submit(worker::runOnce).get(10,TimeUnit.SECONDS));
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            });
        }
        verify(sender,never()).sendVerificationCode(anyString(),anyString(),any());
        assertTrue(worker.runOnce());
        verify(sender,times(1)).sendVerificationCode(eq(email),anyString(),any());
    }

    @Test
    void failedReservationsRemainInRateLimitWindow() {
        for (int index=0;index<5;index++) {
            time.set(NOW.plusSeconds(index*60L));
            service.send(request());
            var job = claim();
            new TransactionTemplate(manager).executeWithoutResult(status -> outbox.finish(job,"FAILED",time.get()));
        }
        time.set(NOW.plusSeconds(300));
        assertSame(AuthErrorCode.EMAIL_SEND_LIMIT_EXCEEDED,
                assertThrows(AuthException.class,() -> service.send(request())).getBaseErrorCode());
        assertEquals(0,logs.countByEmailIgnoreCaseAndSentAtGreaterThanEqual(email,Instant.EPOCH));
    }

    @Test
    void sentOutboxIsNotCountedTwiceWithSuccessLog() {
        for (int index=0;index<5;index++) {
            time.set(NOW.plusSeconds(index*60L));
            service.send(request());
            assertTrue(worker.runOnce());
        }
        assertEquals(5,logs.countByEmailIgnoreCaseAndSentAtGreaterThanEqual(email,Instant.EPOCH));
        time.set(NOW.plusSeconds(300));
        assertSame(AuthErrorCode.EMAIL_SEND_LIMIT_EXCEEDED,
                assertThrows(AuthException.class,() -> service.send(request())).getBaseErrorCode());
    }

    @Test
    void changedVerificationHashCancelsJobAndErasesPayload() {
        var response = service.send(request());
        jdbc.update("UPDATE email_verification SET code_hash=? WHERE id=?","replacement-hash",verification().getId());
        assertTrue(worker.runOnce());
        assertEquals("CANCELLED",state(response.requestId()));
        assertNull(jdbc.queryForObject("SELECT encrypted_code FROM email_verification_outbox WHERE id=?",byte[].class,response.requestId()));
        verify(sender,never()).sendVerificationCode(anyString(),anyString(),any());
    }

    @Test
    void simultaneousCaseVariantEnqueueHasOneReservation() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            java.util.concurrent.Callable<Boolean> first = () -> {
                start.await();
                try { service.send(request()); return true; }
                catch (AuthException exception) {
                    assertSame(AuthErrorCode.EMAIL_SEND_LIMIT_EXCEEDED,exception.getBaseErrorCode()); return false;
                }
            };
            java.util.concurrent.Callable<Boolean> second = () -> {
                start.await();
                try { service.send(new EmailVerificationRequest(email.toUpperCase(Locale.ROOT),VerificationPurpose.SIGNUP)); return true; }
                catch (AuthException exception) {
                    assertSame(AuthErrorCode.EMAIL_SEND_LIMIT_EXCEEDED,exception.getBaseErrorCode()); return false;
                }
            };
            var a = executor.submit(first);
            var b = executor.submit(second);
            start.countDown();
            assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM email_verification_outbox WHERE upper(email)=upper(?)",Long.class,email));
        // One request may have supplied the uppercase spelling; cleanup covers both variants.
        jdbc.update("DELETE FROM email_verification_outbox WHERE upper(email)=upper(?)",email);
        jdbc.update("DELETE FROM email_verification WHERE upper(email)=upper(?)",email);
    }

    private EmailVerificationRequest request() { return new EmailVerificationRequest(email,VerificationPurpose.SIGNUP); }
    private com.bareum.server.domain.auth.entity.EmailVerification verification() {
        return verifications.findFirstByEmailIgnoreCaseAndPurposeOrderByIdDesc(email,VerificationPurpose.SIGNUP).orElseThrow();
    }
    private EmailOutboxRepository.Job claim() {
        return new TransactionTemplate(manager).execute(status -> outbox.claim(time.get(),properties).orElseThrow());
    }
    private String state(UUID id) {
        return jdbc.queryForObject("SELECT state FROM email_verification_outbox WHERE id=?",String.class,id);
    }
}
