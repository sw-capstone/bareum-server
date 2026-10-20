package com.bareum.server.domain.auth.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.Timestamp;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.bareum.server.domain.auth.config.EmailVerificationProperties;
import com.bareum.server.domain.auth.dto.request.EmailVerificationRequest;
import com.bareum.server.domain.auth.entity.EmailVerification;
import com.bareum.server.domain.auth.entity.EmailVerificationSendLog;
import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.repository.EmailVerificationRepository;
import com.bareum.server.domain.auth.repository.EmailVerificationSendLogRepository;
import com.bareum.server.domain.auth.support.TestSmtpServer;
import com.bareum.server.domain.member.entity.Member;
import com.bareum.server.domain.member.repository.MemberRepository;

@SpringBootTest(
	properties = {
		// These limits, periods and keys are test values, not approved operating settings.
		"auth.email-verification.minimum-send-interval-seconds=60",
		"auth.email-verification.max-sends-per-hour=5",
		"auth.email-verification.max-sends-per-day=20",
		"auth.email-verification.password-reset-code-ttl-seconds=420",
		"auth.email-verification.hash-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
		"auth.email-verification.outbox.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
		"auth.email-verification.outbox.poll-delay-ms=3600000"
	})
class EmailVerificationResendServiceIntegrationTests {
	private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");
	private static final String OLD = "123456", NEW = "654321";
	private final String email = "resend-" + UUID.randomUUID() + "@example.invalid";
	private final AtomicReference<Instant> time = new AtomicReference<>(NOW);
	@Autowired
	private EmailVerificationResendService service;
	@Autowired
	private EmailVerificationSendService initial;
	@Autowired
	private EmailVerificationDeliveryService delivery;
	@Autowired
	private EmailOutboxWorker worker;
	@Autowired
	private EmailVerificationRepository verifications;
	@Autowired
	private EmailVerificationSendLogRepository logs;
	@Autowired
	private EmailVerificationCodeHasher hasher;
	@Autowired
	private MemberRepository members;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private PlatformTransactionManager manager;
	@MockitoSpyBean
	private EmailVerificationProperties policy;
	@MockitoBean
	private EmailVerificationMailSender sender;
	@MockitoBean
	private EmailVerificationCodeGenerator generator;

	@MockitoBean(name = "emailVerificationClock")
	private Clock clock;

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
		jdbc.update("DELETE FROM member WHERE upper(email)=upper(?)", email);
	}

	@Test
	void queuesReplacementOnSameRequestPreservingAttemptsWithoutSending() {
		var old = existing(VerificationPurpose.SIGNUP, email);
		var result = service.resend(request());
		var current = latest();
		assertEquals(old.getId(), current.getId());
		assertEquals(2, current.getAttemptCount());
		assertNull(current.getLastSentAt());
		assertEquals(NOW, current.getExpiresAt());
		assertFalse(hasher.matches(email, VerificationPurpose.SIGNUP, OLD, current.getCodeHash()));
		assertTrue(hasher.matches(email, VerificationPurpose.SIGNUP, NEW, current.getCodeHash()));
		assertEquals("PENDING", result.status());
		assertEquals(0, result.expiresIn());
		assertEquals(1L, logCount());
		assertEquals(1L, jobCount());
		verify(sender, never()).sendVerificationCode(anyString(), anyString(), any());
		byte[] encrypted =
				jdbc.queryForObject(
						"SELECT encrypted_code FROM email_verification_outbox WHERE id=?",
						byte[].class,
						result.requestId());
		assertNotNull(encrypted);
		assertFalse(new String(encrypted, java.nio.charset.StandardCharsets.UTF_8).contains(NEW));
	}

	@Test
	void validityStartsAfterDelayedSmtpAcceptanceOutsideTransactionAndLock() {
		existing(VerificationPurpose.SIGNUP, email);
		var result = service.resend(request());
		time.set(NOW.plusSeconds(200));
		doAnswer(
				invocation -> {
					assertFalse(
							TransactionSynchronizationManager.isActualTransactionActive());
					assertEquals(
							0L,
							jdbc.queryForObject(
									"SELECT count(*) FROM pg_locks WHERE"
											+ " locktype='advisory' AND granted",
									Long.class));
					assertEquals(
							0,
							delivery.status(result.requestId()).orElseThrow().expiresIn());
					time.set(NOW.plusSeconds(501));
					return null;
				})
				.when(sender)
				.sendVerificationCode(eq(email), eq(NEW), eq(VerificationPurpose.SIGNUP));
		assertTrue(worker.runOnce());
		assertEquals(NOW.plusSeconds(501), latest().getLastSentAt());
		assertEquals(NOW.plusSeconds(801), latest().getExpiresAt());
		assertEquals("SENT", delivery.status(result.requestId()).orElseThrow().status());
		assertEquals(300, delivery.status(result.requestId()).orElseThrow().expiresIn());
		assertEquals(2L, logCount());
		assertPayloadDeleted(result.requestId());
		time.set(NOW.plusSeconds(503).plusMillis(100));
		assertEquals(298, delivery.status(result.requestId()).orElseThrow().expiresIn());
	}

	@Test
	void expiredRequestCanBeResentAndOnlyLatestRequestChanges() {
		var old = existing(VerificationPurpose.SIGNUP, email);
		jdbc.update(
				"UPDATE email_verification SET expires_at=? WHERE id=?",
				Timestamp.from(NOW.minusSeconds(1)),
				old.getId());
		var recent = existing(VerificationPurpose.SIGNUP, email);
		service.resend(
				new EmailVerificationRequest(
						email.toUpperCase(Locale.ROOT), VerificationPurpose.SIGNUP));
		assertEquals(recent.getId(), latest().getId());
		assertEquals(
				old.getCodeHash(), verifications.findById(old.getId()).orElseThrow().getCodeHash());
		assertEquals(2, latest().getAttemptCount());
		assertTrue(worker.runOnce());
		verify(sender).sendVerificationCode(email, NEW, VerificationPurpose.SIGNUP);
	}

	@Test
	void passwordResetStoresAndSendsRegisteredAddressWithConfiguredLifetime() {
		String registered = email.toUpperCase(Locale.ROOT);
		members.saveAndFlush(Member.createLocal(registered, "test", "password-hash"));
		// Simulate a legacy request stored using a different spelling.
		var old = existing(VerificationPurpose.PASSWORD_RESET, email);
		var result =
				service.resend(
						new EmailVerificationRequest(email, VerificationPurpose.PASSWORD_RESET));
		assertEquals(registered, verifications.findById(old.getId()).orElseThrow().getEmail());
		assertEquals(
				registered,
				jdbc.queryForObject(
						"SELECT email FROM email_verification_outbox WHERE id=?",
						String.class,
						result.requestId()));
		assertTrue(worker.runOnce());
		verify(sender).sendVerificationCode(registered, NEW, VerificationPurpose.PASSWORD_RESET);
		assertTrue(
				hasher.matches(
						registered,
						VerificationPurpose.PASSWORD_RESET,
						NEW,
						latest(VerificationPurpose.PASSWORD_RESET).getCodeHash()));
		assertEquals(420, delivery.status(result.requestId()).orElseThrow().expiresIn());
		assertEquals(
				registered,
				jdbc.queryForObject(
						"SELECT email FROM email_verification_send_log WHERE outbox_id=?",
						String.class,
						result.requestId()));
	}

	@Test
	void missingRequestAndDifferentPurposeCannotQueue() {
		expect(AuthErrorCode.EMAIL_VERIFICATION_INVALID, request());
		existing(VerificationPurpose.SIGNUP, email);
		expect(
				AuthErrorCode.EMAIL_VERIFICATION_INVALID,
				new EmailVerificationRequest(email, VerificationPurpose.PASSWORD_RESET));
		assertEquals(0L, jobCount());
		verify(generator, never()).generate();
	}

	@Test
	void bareUnsentAndConsumedRequestsAreRejected() {
		var old = existing(VerificationPurpose.SIGNUP, email);
		jdbc.update("UPDATE email_verification SET last_sent_at=NULL WHERE id=?", old.getId());
		expect(AuthErrorCode.EMAIL_VERIFICATION_INVALID, request());
		jdbc.update(
				"UPDATE email_verification SET last_sent_at=?,consumed_at=? WHERE id=?",
				Timestamp.from(NOW.minusSeconds(60)),
				Timestamp.from(NOW),
				old.getId());
		expect(AuthErrorCode.EMAIL_VERIFICATION_INVALID, request());
		assertEquals(0L, jobCount());
	}

	@ParameterizedTest
	@ValueSource(strings = {"verified_at", "verification_token_hash", "verification_expires_at"})
	void completedOrPartialProofRemainsUntouched(String field) {
		var old = existing(VerificationPurpose.SIGNUP, email);
		Object value =
				field.equals("verification_token_hash")
						? "proof-test-hash"
						: Timestamp.from(NOW.plusSeconds(600));
		jdbc.update("UPDATE email_verification SET " + field + "=? WHERE id=?", value, old.getId());
		expect(AuthErrorCode.EMAIL_VERIFICATION_NOT_CONFIGURED, request());
		assertUnchanged(old);
	}

	@Test
	void registeredSignupAccountIsRechecked() {
		var old = existing(VerificationPurpose.SIGNUP, email);
		members.saveAndFlush(Member.createLocal(email, "test", "password-hash"));
		expect(AuthErrorCode.EMAIL_ALREADY_REGISTERED, request());
		assertUnchanged(old);
	}

	@Test
	void intervalBoundaryUsesSharedSuccessfulSendHistory() {
		var old = existing(VerificationPurpose.SIGNUP, email);
		jdbc.update(
				"UPDATE email_verification_send_log SET sent_at=? WHERE email=?",
				Timestamp.from(NOW.minusSeconds(59)),
				email);
		expect(AuthErrorCode.EMAIL_SEND_LIMIT_EXCEEDED, request());
		assertUnchanged(old);
		time.set(NOW.plusSeconds(1));
		assertEquals("PENDING", service.resend(request()).status());
	}

	@Test
	void hourlyAndDailySuccessfulSendsLimitResends() {
		var old = existing(VerificationPurpose.SIGNUP, email);
		for (int i = 1; i < 5; i++)
			logs.saveAndFlush(
					EmailVerificationSendLog.sent(
							email, VerificationPurpose.SIGNUP, NOW.minusSeconds(60L * (i + 1))));
		expect(AuthErrorCode.EMAIL_SEND_LIMIT_EXCEEDED, request());
		for (int i = 0; i < 15; i++)
			logs.saveAndFlush(
					EmailVerificationSendLog.sent(
							email, VerificationPurpose.SIGNUP, NOW.minusSeconds(3601L + i)));
		when(policy.getMaxSendsPerHour()).thenReturn(100);
		expect(AuthErrorCode.EMAIL_SEND_LIMIT_EXCEEDED, request());
		assertEquals(old.getCodeHash(), latest().getCodeHash());
	}

	@Test
	void missingPolicyAndCipherOrMailSettingsLeaveOldRequestUsable() {
		var old = existing(VerificationPurpose.SIGNUP, email);
		doThrow(new AuthException(AuthErrorCode.EMAIL_VERIFICATION_NOT_CONFIGURED))
				.when(policy)
				.requireConfigured(any());
		expect(AuthErrorCode.EMAIL_VERIFICATION_NOT_CONFIGURED, request());
		assertUnchanged(old);
		reset(policy);
		doThrow(new AuthException(AuthErrorCode.EMAIL_VERIFICATION_NOT_CONFIGURED))
				.when(sender)
				.requireConfigured();
		expect(AuthErrorCode.EMAIL_VERIFICATION_NOT_CONFIGURED, request());
		assertUnchanged(old);
	}

	@Test
	void sameGeneratedCodeCannotInvalidateOldRequest() {
		var old = existing(VerificationPurpose.SIGNUP, email);
		when(generator.generate()).thenReturn(OLD);
		expect(AuthErrorCode.EMAIL_SEND_FAILED, request());
		assertUnchanged(old);
	}

	@Test
	void enclosingRollbackRestoresOldCodeAndLeavesNoOutboxWork() {
		var old = existing(VerificationPurpose.SIGNUP, email);
		new TransactionTemplate(manager)
				.executeWithoutResult(
						status -> {
							service.resend(request());
							status.setRollbackOnly();
						});
		assertUnchanged(old);
		assertFalse(worker.runOnce());
		verify(sender, never()).sendVerificationCode(anyString(), anyString(), any());
	}

	@Test
	void outboxInsertFailureRollsBackAlreadyFlushedReplacement() {
		var old = existing(VerificationPurpose.SIGNUP, email);
		String fn = "resend_test_" + UUID.randomUUID().toString().replace("-", "");
		jdbc.execute(
				"CREATE FUNCTION "
						+ fn
						+ "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.email='"
						+ email
						+ "' THEN RAISE EXCEPTION 'test failure'; END IF; RETURN NEW; END $$");
		jdbc.execute(
				"CREATE TRIGGER "
						+ fn
						+ " BEFORE INSERT ON email_verification_outbox FOR EACH ROW EXECUTE"
						+ " FUNCTION "
						+ fn
						+ "()");
		try {
			assertThrows(RuntimeException.class, () -> service.resend(request()));
			assertUnchanged(old);
		} finally {
			jdbc.execute("DROP TRIGGER " + fn + " ON email_verification_outbox");
			jdbc.execute("DROP FUNCTION " + fn + "()");
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"QUEUED", "RETRY", "SENDING"})
	void pendingInitialOrResendDeliveryCannotBeReplacedEvenAfterInterval(String state) {
		existing(VerificationPurpose.SIGNUP, email);
		var result = service.resend(request());
		if (state.equals("SENDING")) {
			jdbc.update(
					"UPDATE email_verification_outbox SET state=?,lease_token=?,lease_until=? WHERE"
							+ " id=?",
					state,
					UUID.randomUUID(),
					Timestamp.from(NOW.plusSeconds(120)),
					result.requestId());
		} else {
			jdbc.update(
					"UPDATE email_verification_outbox SET state=? WHERE id=?",
					state,
					result.requestId());
		}
		time.set(NOW.plusSeconds(61));
		expect(AuthErrorCode.EMAIL_SEND_LIMIT_EXCEEDED, request());
		expect(
				AuthErrorCode.EMAIL_SEND_LIMIT_EXCEEDED,
				new EmailVerificationRequest(
						email.toUpperCase(Locale.ROOT), VerificationPurpose.SIGNUP));
		assertEquals(1L, jobCount());
		assertEquals(2, latest().getAttemptCount());
	}

	@Test
	void pendingInitialDeliveryCannotBeResent() {
		var result = initial.send(request());
		time.set(NOW.plusSeconds(61));
		expect(AuthErrorCode.EMAIL_SEND_LIMIT_EXCEEDED, request());
		assertEquals(1L, jobCount());
		assertEquals("PENDING", delivery.status(result.requestId()).orElseThrow().status());
	}

	@Test
	void workerRetriesSameCodeThenFailsWithoutRevivingPreviousCode() {
		existing(VerificationPurpose.SIGNUP, email);
		var result = service.resend(request());
		doThrow(new AuthException(AuthErrorCode.EMAIL_SEND_FAILED))
				.when(sender)
				.sendVerificationCode(anyString(), anyString(), any());
		for (int i = 0; i < 3; i++) {
			time.set(NOW.plusSeconds(i * 5));
			assertTrue(worker.runOnce());
		}
		var codes = ArgumentCaptor.forClass(String.class);
		verify(sender, times(3))
				.sendVerificationCode(eq(email), codes.capture(), eq(VerificationPurpose.SIGNUP));
		assertEquals(1, codes.getAllValues().stream().distinct().count());
		assertEquals("FAILED", delivery.status(result.requestId()).orElseThrow().status());
		assertNull(latest().getLastSentAt());
		assertEquals(2, latest().getAttemptCount());
		assertEquals(1L, logCount());
		assertPayloadDeleted(result.requestId());
		time.set(NOW.plusSeconds(61));
		when(generator.generate()).thenReturn("222333");
		var next = service.resend(request());
		assertNotEquals(result.requestId(), next.requestId());
		assertEquals(2, latest().getAttemptCount());
		assertEquals(2L, jobCount());
	}

	@Test
	void failedInitialDeliveryCanEnterResendAfterReservationInterval() {
		var first = initial.send(request());
		time.set(NOW.plusSeconds(300));
		assertFalse(worker.runOnce());
		assertEquals("FAILED", delivery.status(first.requestId()).orElseThrow().status());
		when(generator.generate()).thenReturn("222333");
		assertEquals("PENDING", service.resend(request()).status());
	}

	@Test
	void failedReservationsCountTowardsLimits() {
		existing(VerificationPurpose.SIGNUP, email);
		for (int i = 0; i < 4; i++) {
			when(generator.generate()).thenReturn("22233" + i);
			var result = service.resend(request());
			time.set(time.get().plusSeconds(300));
			assertFalse(worker.runOnce());
			assertEquals("FAILED", delivery.status(result.requestId()).orElseThrow().status());
		}
		// One successful original send plus four failed reservations consume the hourly budget.
		expect(AuthErrorCode.EMAIL_SEND_LIMIT_EXCEEDED, request());
		assertEquals(4L, jobCount());
	}

	@Test
	void previousSentDeliveryCannotExposeReplacementTimer() {
		when(generator.generate()).thenReturn(OLD);
		var first = initial.send(request());
		assertTrue(worker.runOnce());
		var id = latest().getId();
		time.set(NOW.plusSeconds(60));
		when(generator.generate()).thenReturn(NEW);
		var result = service.resend(request());
		assertEquals(id, latest().getId());
		assertEquals("CANCELLED", delivery.status(first.requestId()).orElseThrow().status());
		assertEquals(0, delivery.status(first.requestId()).orElseThrow().expiresIn());
		assertEquals("PENDING", delivery.status(result.requestId()).orElseThrow().status());
		assertTrue(worker.runOnce());
		assertEquals("CANCELLED", delivery.status(first.requestId()).orElseThrow().status());
		assertEquals("SENT", delivery.status(result.requestId()).orElseThrow().status());
	}

	@Test
	void smtpAcceptanceThenDatabaseFailureRecoversSameResendWithoutDuplicateLog() {
		existing(VerificationPurpose.SIGNUP, email);
		var result = service.resend(request());
		String fn = "resend_commit_" + UUID.randomUUID().toString().replace("-", "");
		jdbc.execute(
				"CREATE FUNCTION "
						+ fn
						+ "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.outbox_id='"
						+ result.requestId()
						+ "'::uuid THEN RAISE EXCEPTION 'test failure'; END IF; RETURN NEW; END"
						+ " $$");
		jdbc.execute(
				"CREATE TRIGGER "
						+ fn
						+ " BEFORE INSERT ON email_verification_send_log FOR EACH ROW EXECUTE"
						+ " FUNCTION "
						+ fn
						+ "()");
		try {
			assertThrows(RuntimeException.class, () -> worker.runOnce());
			assertNull(latest().getLastSentAt());
			assertEquals(1L, logCount());
		} finally {
			jdbc.execute("DROP TRIGGER " + fn + " ON email_verification_send_log");
			jdbc.execute("DROP FUNCTION " + fn + "()");
		}
		time.set(NOW.plusSeconds(121));
		assertTrue(worker.runOnce());
		verify(sender, times(2)).sendVerificationCode(email, NEW, VerificationPurpose.SIGNUP);
		assertEquals(2L, logCount());
		assertEquals("SENT", delivery.status(result.requestId()).orElseThrow().status());
	}

	@Test
	void simultaneousResendsAndInitialSendUseSameLockAndReservation() throws Exception {
		var old = existing(VerificationPurpose.SIGNUP, email);
		var results = concurrent(false);
		assertTrue(java.util.Arrays.asList(results).contains("queued"));
		assertTrue(java.util.Arrays.asList(results).contains("AUTH-0004"));
		assertEquals(old.getId(), latest().getId());
		assertEquals(1L, jobCount());
		assertTrue(worker.runOnce());
		verify(sender).sendVerificationCode(email, NEW, VerificationPurpose.SIGNUP);
	}

	@Test
	void initialSendRacingResendOnlyQueuesOneWorkItem() throws Exception {
		existing(VerificationPurpose.SIGNUP, email);
		var results = concurrent(true);
		assertTrue(java.util.Arrays.asList(results).contains("queued"));
		assertTrue(java.util.Arrays.asList(results).contains("AUTH-0004"));
		assertEquals(1L, jobCount());
		assertEquals(2, latest().getAttemptCount());
		assertTrue(worker.runOnce());
	}

	@Test
	void realSmtpProtocolReceivesResendCodeAndMarksSuccess() throws Exception {
		existing(VerificationPurpose.SIGNUP, email);
		try (var smtp = new TestSmtpServer(false, false)) {
			var javaMail = new org.springframework.mail.javamail.JavaMailSenderImpl();
			javaMail.setHost("127.0.0.1");
			javaMail.setPort(smtp.port());
			javaMail.setDefaultEncoding("UTF-8");
			javaMail.getJavaMailProperties().setProperty("mail.smtp.timeout", "2000");
			javaMail.getJavaMailProperties().setProperty("mail.smtp.connectiontimeout", "2000");
			javaMail.getJavaMailProperties().setProperty("mail.smtp.writetimeout", "2000");
			var provider = mock(org.springframework.beans.factory.ObjectProvider.class);
			when(provider.getIfAvailable()).thenReturn(javaMail);
			var real = new SmtpEmailVerificationMailSender(provider, "sender@example.invalid");
			doAnswer(
					invocation -> {
						real.sendVerificationCode(
								invocation.getArgument(0),
								invocation.getArgument(1),
								invocation.getArgument(2));
						return null;
					})
					.when(sender)
					.sendVerificationCode(anyString(), anyString(), any());
			var result = service.resend(request());
			assertTrue(worker.runOnce());
			assertTrue(smtp.recipient().contains(email));
			// Decode the MIME body rather than assuming the SMTP transport encoding.
			var mime =
					new jakarta.mail.internet.MimeMessage(
							jakarta.mail.Session.getInstance(new java.util.Properties()),
							new java.io.ByteArrayInputStream(
									smtp.message()
											.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
			assertTrue(mime.getContent().toString().contains(NEW));
			assertEquals("SENT", delivery.status(result.requestId()).orElseThrow().status());
		}
	}

	private String[] concurrent(boolean send) throws Exception {
		var ready = new CountDownLatch(2);
		var start = new CountDownLatch(1);
		try (var pool = Executors.newFixedThreadPool(2)) {
			var a = pool.submit(() -> race(ready, start, false));
			var b = pool.submit(() -> race(ready, start, send));
			assertTrue(ready.await(5, TimeUnit.SECONDS));
			start.countDown();
			return new String[] {a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)};
		} finally {
			start.countDown();
		}
	}

	private String race(CountDownLatch ready, CountDownLatch start, boolean send) throws Exception {
		ready.countDown();
		assertTrue(start.await(5, TimeUnit.SECONDS));
		try {
			if (send)
				initial.send(request());
			else
				service.resend(request());
			return "queued";
		} catch (AuthException e) {
			return e.getBaseErrorCode().getCode();
		}
	}

	private EmailVerification existing(VerificationPurpose purpose, String address) {
		var v =
				EmailVerification.request(
						address,
						purpose,
						hasher.hash(address, purpose, OLD),
						NOW.plusSeconds(100),
						2);
		v.markSent(NOW.minusSeconds(60));
		var result = verifications.saveAndFlush(v);
		logs.saveAndFlush(EmailVerificationSendLog.sent(address, purpose, NOW.minusSeconds(60)));
		return result;
	}

	private EmailVerificationRequest request() {
		return new EmailVerificationRequest(email, VerificationPurpose.SIGNUP);
	}

	private EmailVerification latest() {
		return latest(VerificationPurpose.SIGNUP);
	}

	private EmailVerification latest(VerificationPurpose purpose) {
		return verifications
				.findFirstByEmailIgnoreCaseAndPurposeOrderByIdDesc(email, purpose)
				.orElseThrow();
	}

	private long logCount() {
		return logs.countByEmailIgnoreCaseAndSentAtGreaterThanEqual(email, Instant.EPOCH);
	}

	private long jobCount() {
		return jdbc.queryForObject(
				"SELECT count(*) FROM email_verification_outbox WHERE upper(email)=upper(?)",
				Long.class,
				email);
	}

	private void expect(AuthErrorCode code, EmailVerificationRequest request) {
		assertSame(
				code,
				assertThrows(AuthException.class, () -> service.resend(request))
						.getBaseErrorCode());
	}

	private void assertUnchanged(EmailVerification old) {
		assertEquals(old.getCodeHash(), latest().getCodeHash());
		assertEquals(old.getExpiresAt(), latest().getExpiresAt());
		assertEquals(old.getLastSentAt(), latest().getLastSentAt());
		assertEquals(2, latest().getAttemptCount());
		assertEquals(0L, jobCount());
	}

	private void assertPayloadDeleted(UUID id) {
		assertNull(
				jdbc.queryForObject(
						"SELECT encrypted_code FROM email_verification_outbox WHERE id=?",
						byte[].class,
						id));
	}
}
