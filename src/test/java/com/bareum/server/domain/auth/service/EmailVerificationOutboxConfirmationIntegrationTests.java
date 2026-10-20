package com.bareum.server.domain.auth.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.bareum.server.domain.auth.dto.request.EmailVerificationConfirmRequest;
import com.bareum.server.domain.auth.dto.request.EmailVerificationRequest;
import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.repository.EmailVerificationRepository;
import com.bareum.server.domain.member.entity.Member;
import com.bareum.server.domain.member.repository.MemberRepository;

@SpringBootTest(properties = {
	"auth.email-verification.minimum-send-interval-seconds=60",
	"auth.email-verification.max-sends-per-hour=5",
	"auth.email-verification.max-sends-per-day=20",
	"auth.email-verification.max-verification-attempts=3",
	"auth.email-verification.password-reset-code-ttl-seconds=300",
	"auth.email-verification.password-reset-verification-ttl-seconds=600",
	"auth.email-verification.hash-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
	"auth.email-verification.outbox.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
	"auth.email-verification.outbox.poll-delay-ms=3600000"
})
class EmailVerificationOutboxConfirmationIntegrationTests {
	private static final Instant NOW = Instant.parse("2026-10-16T00:00:00Z");
	private final String email = "confirm-outbox-" + UUID.randomUUID() + "@example.invalid";
	private final AtomicReference<Instant> time = new AtomicReference<>(NOW);

	@Autowired
	private EmailVerificationSendService send;
	@Autowired
	private EmailVerificationConfirmService confirm;
	@Autowired
	private EmailVerificationDeliveryService delivery;
	@Autowired
	private EmailOutboxWorker worker;
	@Autowired
	private EmailVerificationRepository verifications;
	@Autowired
	private EmailVerificationTokenGenerator tokens;
	@Autowired
	private EmailVerificationCodeHasher hasher;
	@Autowired
	private MemberRepository members;
	@Autowired
	private JdbcTemplate jdbc;
	@MockitoBean
	private EmailVerificationMailSender sender;
	@MockitoBean
	private EmailVerificationCodeGenerator codes;
	@MockitoBean
	private Clock emailVerificationClock;

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
		String registeredEmail = "I" + email;
		jdbc.update("DELETE FROM email_verification_send_log WHERE email=?", registeredEmail);
		jdbc.update("DELETE FROM email_verification_outbox WHERE email=?", registeredEmail);
		jdbc.update("DELETE FROM email_verification WHERE email=?", registeredEmail);
		jdbc.update("DELETE FROM member WHERE email=?", registeredEmail);
	}

	private EmailVerificationConfirmRequest request(String code) {
		return new EmailVerificationConfirmRequest(email, VerificationPurpose.SIGNUP, code);
	}

	private void expectError(AuthErrorCode code, String input) {
		assertSame(code, assertThrows(AuthException.class,
				() -> confirm.confirm(request(input))).getBaseErrorCode());
	}

	@Test
	void confirmationStartsOnlyAfterOutboxDeliveryAndIssuesSeparateProofLifetime() {
		var queued = send.send(new EmailVerificationRequest(email, VerificationPurpose.SIGNUP));
		expectError(AuthErrorCode.EMAIL_VERIFICATION_INVALID, "012345");
		var row = verifications.findFirstByEmailIgnoreCaseAndPurposeOrderByIdDesc(
				email, VerificationPurpose.SIGNUP).orElseThrow();
		assertEquals(0, row.getAttemptCount());
		time.set(NOW.plusSeconds(90));
		assertTrue(worker.runOnce());
		assertEquals("SENT", delivery.status(queued.requestId()).orElseThrow().status());
		var sentCode = ArgumentCaptor.forClass(String.class);
		verify(sender).sendVerificationCode(eq(email), sentCode.capture(), eq(VerificationPurpose.SIGNUP));
		var proof = confirm.confirm(request(sentCode.getValue()));
		row = verifications.findById(row.getId()).orElseThrow();
		assertEquals(time.get().plusSeconds(300), row.getExpiresAt());
		assertEquals(time.get().plusSeconds(600), row.getVerificationExpiresAt());
		assertEquals(tokens.hash(proof.verificationToken()), row.getVerificationTokenHash());
		assertNull(row.getConsumedAt());
	}

	@Test
	void newerPendingRequestPreventsConfirmationOfPreviouslySentCode() {
		send.send(new EmailVerificationRequest(email, VerificationPurpose.SIGNUP));
		assertTrue(worker.runOnce());
		time.set(NOW.plusSeconds(61));
		send.send(new EmailVerificationRequest(email, VerificationPurpose.SIGNUP));
		expectError(AuthErrorCode.EMAIL_VERIFICATION_INVALID, "012345");
		assertTrue(worker.runOnce());
		expectError(AuthErrorCode.EMAIL_VERIFICATION_CODE_MISMATCH, "012345");
		assertNotNull(confirm.confirm(request("654321")).verificationToken());
	}

	@Test
	void passwordResetConfirmsDeliveredCodeUsingStoredEmailDespiteUnicodeCaseAlias() {
		String registeredEmail = "I" + email;
		String inputEmail = "\u0131" + email;
		assertEquals(jdbc.queryForObject("SELECT upper(?)", String.class, registeredEmail),
				jdbc.queryForObject("SELECT upper(?)", String.class, inputEmail));
		members.saveAndFlush(Member.createLocal(registeredEmail, "reset test", "password-hash"));

		var queued = send.send(new EmailVerificationRequest(inputEmail, VerificationPurpose.PASSWORD_RESET));
		var row = verifications.findFirstByEmailIgnoreCaseAndPurposeOrderByIdDesc(
				inputEmail, VerificationPurpose.PASSWORD_RESET).orElseThrow();
		assertEquals(registeredEmail, row.getEmail());
		assertTrue(hasher.matches(registeredEmail, VerificationPurpose.PASSWORD_RESET, "012345", row.getCodeHash()));
		assertFalse(hasher.matches(inputEmail, VerificationPurpose.PASSWORD_RESET, "012345", row.getCodeHash()));

		assertTrue(worker.runOnce());
		verify(sender).sendVerificationCode(registeredEmail, "012345", VerificationPurpose.PASSWORD_RESET);
		assertEquals("SENT", delivery.status(queued.requestId()).orElseThrow().status());
		var proof = confirm.confirm(new EmailVerificationConfirmRequest(
				inputEmail, VerificationPurpose.PASSWORD_RESET, "012345"));
		row = verifications.findById(row.getId()).orElseThrow();
		assertEquals(tokens.hash(proof.verificationToken()), row.getVerificationTokenHash());
		assertEquals(0, row.getAttemptCount());
		assertEquals(600, proof.expiresIn());
	}

	@Test
	void proofCommitFailureRollsBackAndSameDeliveredCodeCanBeRetried() {
		send.send(new EmailVerificationRequest(email, VerificationPurpose.SIGNUP));
		assertTrue(worker.runOnce());
		var row = verifications.findFirstByEmailIgnoreCaseAndPurposeOrderByIdDesc(
				email, VerificationPurpose.SIGNUP).orElseThrow();
		String trigger = "confirm_failure_" + UUID.randomUUID().toString().replace("-", "");
		jdbc.execute("CREATE FUNCTION " + trigger + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
				+ "IF NEW.id = " + row.getId() + " AND NEW.verification_token_hash IS NOT NULL "
				+ "THEN RAISE EXCEPTION 'test failure'; END IF; RETURN NEW; END $$");
		jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE UPDATE ON email_verification "
				+ "FOR EACH ROW EXECUTE FUNCTION " + trigger + "()");
		try {
			assertThrows(RuntimeException.class, () -> confirm.confirm(request("012345")));
			row = verifications.findById(row.getId()).orElseThrow();
			assertNull(row.getVerifiedAt());
			assertNull(row.getVerificationTokenHash());
			assertNull(row.getVerificationExpiresAt());
		} finally {
			jdbc.execute("DROP TRIGGER " + trigger + " ON email_verification");
			jdbc.execute("DROP FUNCTION " + trigger + "()");
		}
		assertNotNull(confirm.confirm(request("012345")).verificationToken());
	}
}
