package com.bareum.server.domain.auth.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.bareum.server.domain.auth.dto.request.EmailSignupRequest;
import com.bareum.server.domain.auth.dto.request.EmailVerificationConfirmRequest;
import com.bareum.server.domain.auth.dto.request.EmailVerificationRequest;
import com.bareum.server.domain.auth.entity.EmailVerification;
import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.repository.EmailVerificationRepository;
import com.bareum.server.domain.member.entity.Member;
import com.bareum.server.domain.member.enums.SignupMethod;
import com.bareum.server.domain.member.repository.MemberRepository;
import com.bareum.server.domain.term.dto.request.SignupAgreementsRequest;
import com.bareum.server.domain.term.entity.Term;
import com.bareum.server.domain.term.enums.TermCode;
import com.bareum.server.domain.term.exception.TermErrorCode;
import com.bareum.server.domain.term.exception.TermException;
import com.bareum.server.domain.term.repository.MemberTermRepository;
import com.bareum.server.domain.term.repository.TermRepository;

@SpringBootTest(properties = {
	// 테스트 전용 제한 값이며 운영 정책이 아닙니다.
	"auth.email-verification.minimum-send-interval-seconds=1",
	"auth.email-verification.max-sends-per-hour=20",
	"auth.email-verification.max-sends-per-day=30",
	"auth.email-verification.max-verification-attempts=3",
	"auth.email-verification.hash-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
	"auth.email-verification.outbox.poll-delay-ms=3600000",
	"auth.email-verification.outbox.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class EmailSignupServiceIntegrationTests {

	private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");
	private static final String PASSWORD = String.join("", "Password", "123", "!");
	private final String email = "signup-" + UUID.randomUUID() + "@example.invalid";
	private final List<Long> termIds = new ArrayList<>();
	private SignupAgreementsRequest consents;

	@Autowired
	private EmailSignupService service;
	@Autowired
	private EmailVerificationConfirmService confirm;
	@Autowired
	private EmailVerificationSendService send;
	@Autowired
	private EmailVerificationResendService resend;
	@Autowired
	private EmailOutboxWorker worker;
	@Autowired
	private EmailVerificationDeliveryService deliveries;
	@Autowired
	private EmailVerificationRepository verifications;
	@Autowired
	private MemberRepository members;
	@Autowired
	private TermRepository terms;
	@Autowired
	private EmailVerificationCodeHasher codes;
	@Autowired
	private EmailVerificationTokenGenerator tokens;
	@Autowired
	private JdbcTemplate jdbc;
	@MockitoSpyBean
	private MemberTermRepository memberTerms;
	@MockitoSpyBean
	private PasswordHashService passwordHashes;
	@MockitoBean(name = "emailVerificationClock")
	private Clock clock;
	@MockitoBean
	private EmailVerificationMailSender mail;
	@MockitoBean
	private EmailVerificationCodeGenerator generator;

	@BeforeEach
	void setUp() {
		when(clock.instant()).thenReturn(NOW);
		consents = new SignupAgreementsRequest(true, true, true, false);
		for (TermCode code : TermCode.values()) {
			Term term = terms.saveAndFlush(Term.create(code, "test-fixed-v1",
					code != TermCode.MARKETING_CONSENT));
			termIds.add(term.getId());
		}
	}

	@AfterEach
	void cleanUp() {
		jdbc.update("DELETE FROM member_term WHERE member_id IN (SELECT id FROM member WHERE lower(email) = lower(?))",
				email);
		jdbc.update("DELETE FROM member WHERE lower(email) = lower(?)", email);
		jdbc.update("DELETE FROM email_verification_send_log WHERE lower(email) = lower(?)", email);
		jdbc.update("DELETE FROM email_verification_outbox WHERE upper(email) = upper(?)", email);
		jdbc.update("DELETE FROM email_verification WHERE lower(email) = lower(?)", email);
		for (Long id : termIds)
			jdbc.update("DELETE FROM term WHERE id = ?", id);
	}

	private String proof(VerificationPurpose purpose) {
		var row = EmailVerification.request(email, purpose, codes.hash(email, purpose, "012345"), NOW.plusSeconds(300));
		row.markSent(NOW);
		String token = tokens.generate();
		row.markVerified(tokens.hash(token), NOW, NOW.plusSeconds(600));
		verifications.saveAndFlush(row);
		return token;
	}

	private EmailSignupRequest request(String token) {
		return new EmailSignupRequest(email, "사용자", PASSWORD, token, consents);
	}

	private EmailVerification latest() {
		return verifications.findFirstByEmailIgnoreCaseAndPurposeOrderByIdDesc(email, VerificationPurpose.SIGNUP)
				.orElseThrow();
	}

	private void assertNotSignedUp() {
		assertFalse(members.existsByEmailIgnoreCase(email));
		assertNull(latest().getConsumedAt());
	}

	private void expectAuth(AuthErrorCode error, EmailSignupRequest request) {
		assertSame(error, assertThrows(AuthException.class, () -> service.signup(request)).getBaseErrorCode());
	}

	private void expectTerm(TermErrorCode error, EmailSignupRequest request) {
		assertSame(error, assertThrows(TermException.class, () -> service.signup(request)).getBaseErrorCode());
		assertNotSignedUp();
	}

	@Test
	void signupCommitsHashedPasswordConsentsAndProofConsumptionTogether() {
		String token = proof(VerificationPurpose.SIGNUP);
		var response = service
				.signup(new EmailSignupRequest(email.toUpperCase(Locale.ROOT), "사용자", PASSWORD, token, consents));
		var member = members.findById(response.memberId()).orElseThrow();
		assertEquals(email, member.getEmail());
		assertEquals(SignupMethod.LOCAL, member.getSignupMethod());
		assertTrue(passwordHashes.matches(PASSWORD, member.getPasswordHash()));
		assertNotEquals(PASSWORD, member.getPasswordHash());
		assertEquals(NOW, latest().getConsumedAt());
		assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM member_term WHERE member_id = ?", Integer.class,
				member.getId()));
		assertEquals(3, jdbc.queryForObject(
				"SELECT count(*) FROM member_term WHERE member_id = ? AND is_agreed = true AND agreed_at = ? AND responded_at = ? AND withdrawn_at IS NULL",
				Integer.class, member.getId(), java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW)));
		assertEquals(1, jdbc.queryForObject(
				"SELECT count(*) FROM member_term WHERE member_id = ? AND is_agreed = false AND agreed_at IS NULL AND responded_at = ? AND withdrawn_at IS NULL",
				Integer.class, member.getId(), java.sql.Timestamp.from(NOW)));
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM member_auth WHERE member_id = ?", Integer.class,
				member.getId()));
		expectAuth(AuthErrorCode.EMAIL_VERIFICATION_INVALID, request(token));
	}

	@Test
	void marketingAcceptanceIsStoredWithoutChangingOtherConsents() {
		String token = proof(VerificationPurpose.SIGNUP);
		consents = new SignupAgreementsRequest(true, true, true, true);
		var response = service.signup(request(token));
		assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM member_term WHERE member_id = ? AND is_agreed = true",
				Integer.class, response.memberId()));
	}

	@ParameterizedTest
	@ValueSource(strings = {"age", "service", "privacy"})
	void refusedRequiredConsentDoesNotConsumeProof(String field) {
		String token = proof(VerificationPurpose.SIGNUP);
		consents = new SignupAgreementsRequest(!field.equals("age"), !field.equals("service"), !field.equals("privacy"),
				false);
		expectTerm(TermErrorCode.REQUIRED_TERM_NOT_AGREED, request(token));
	}

	@ParameterizedTest
	@ValueSource(strings = {"age", "service", "privacy", "marketing"})
	void missingAgreementDoesNotConsumeProof(String field) {
		String token = proof(VerificationPurpose.SIGNUP);
		consents = new SignupAgreementsRequest(field.equals("age") ? null : true,
				field.equals("service") ? null : true, field.equals("privacy") ? null : true,
				field.equals("marketing") ? null : false);
		expectTerm(TermErrorCode.INVALID_CONSENT_REQUEST, request(token));
	}

	@Test
	void absentAgreementObjectIsRejectedWithoutConsumingProof() {
		String token = proof(VerificationPurpose.SIGNUP);
		consents = null;
		expectTerm(TermErrorCode.INVALID_CONSENT_REQUEST, request(token));
	}

	@ParameterizedTest
	@ValueSource(strings = {"AGE_OVER_14", "TERMS_OF_SERVICE", "PRIVACY_POLICY", "MARKETING_CONSENT"})
	void databaseVersionIsRecordedWithoutSeparateVersionConfiguration(String code) {
		String token = proof(VerificationPurpose.SIGNUP);
		jdbc.update("UPDATE term SET version = 'different-version' WHERE code = ?", code);
		var result = service.signup(request(token));
		assertEquals("different-version", jdbc.queryForObject(
				"SELECT t.version FROM member_term mt JOIN term t ON t.id = mt.term_id WHERE mt.member_id = ? AND t.code = ?",
				String.class, result.memberId(), code));
		assertEquals(NOW, latest().getConsumedAt());
	}

	@Test
	void selectedTermsAreMappedByCodeRatherThanDatabaseOrder() {
		for (Long id : termIds)
			terms.deleteById(id);
		termIds.clear();
		for (TermCode code : List.of(TermCode.MARKETING_CONSENT, TermCode.PRIVACY_POLICY,
				TermCode.TERMS_OF_SERVICE, TermCode.AGE_OVER_14)) {
			termIds.add(
					terms.saveAndFlush(Term.create(code, "test-fixed-v1", code != TermCode.MARKETING_CONSENT)).getId());
		}
		var result = service.signup(request(proof(VerificationPurpose.SIGNUP)));
		var rows = jdbc.queryForList(
				"SELECT t.code,t.version,mt.is_agreed FROM member_term mt JOIN term t ON t.id=mt.term_id WHERE mt.member_id = ?",
				result.memberId());
		assertEquals(4, rows.size());
		for (var row : rows) {
			assertEquals("test-fixed-v1", row.get("version"));
			assertEquals(!row.get("code").equals("MARKETING_CONSENT"), row.get("is_agreed"));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"AGE_OVER_14", "MARKETING_CONSENT"})
	void missingFixedCatalogItemBlocksSignupEvenIfMarketingWasDeclined(String code) {
		String token = proof(VerificationPurpose.SIGNUP);
		jdbc.update("DELETE FROM term WHERE code = ?", code);
		expectTerm(TermErrorCode.SIGNUP_TERMS_INVALID, request(token));
	}

	@Test
	void absentCatalogBlocksSignupInsteadOfSkippingRequiredConsent() {
		String token = proof(VerificationPurpose.SIGNUP);
		for (Long id : termIds)
			terms.deleteById(id);
		expectTerm(TermErrorCode.SIGNUP_TERMS_INVALID, request(token));
	}

	@Test
	void duplicateTermCodesCannotBeLinkedToASingleConsent() {
		String token = proof(VerificationPurpose.SIGNUP);
		var extra = terms.saveAndFlush(Term.create(TermCode.TERMS_OF_SERVICE, "other", true));
		termIds.add(extra.getId());
		expectTerm(TermErrorCode.SIGNUP_TERMS_INVALID, request(token));
	}

	@ParameterizedTest
	@ValueSource(strings = {"AGE_OVER_14", "TERMS_OF_SERVICE", "PRIVACY_POLICY"})
	void coreTermsCannotBeConfiguredAsOptional(String code) {
		String token = proof(VerificationPurpose.SIGNUP);
		jdbc.update("UPDATE term SET is_required = false WHERE code = ?", code);
		expectTerm(TermErrorCode.SIGNUP_TERMS_INVALID, request(token));
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void existingLocalOrGoogleEmailIsRecheckedIgnoringCase(boolean google) {
		String token = proof(VerificationPurpose.SIGNUP);
		var existing = google ? Member.createGoogle(email.toUpperCase(Locale.ROOT), "기존")
				: Member.createLocal(email.toUpperCase(Locale.ROOT), "기존", "test-only-existing-hash");
		members.saveAndFlush(existing);
		expectAuth(AuthErrorCode.EMAIL_ALREADY_REGISTERED, request(token));
		assertNull(latest().getConsumedAt());
		assertEquals(1,
				jdbc.queryForObject("SELECT count(*) FROM member WHERE lower(email) = lower(?)", Integer.class, email));
	}

	@Test
	void invalidPasswordDoesNotConsumeProof() {
		String token = proof(VerificationPurpose.SIGNUP);
		expectAuth(AuthErrorCode.INVALID_SIGNUP_PASSWORD,
				new EmailSignupRequest(email, "사용자", "abcdefghijkl", token, consents));
		assertNotSignedUp();
	}

	@Test
	void wrongTokenOrEmailOrPurposeCannotSignUp() {
		String token = proof(VerificationPurpose.PASSWORD_RESET);
		expectAuth(AuthErrorCode.EMAIL_VERIFICATION_INVALID, request(token));
		token = proof(VerificationPurpose.SIGNUP);
		expectAuth(AuthErrorCode.EMAIL_VERIFICATION_INVALID, request(tokens.generate()));
		expectAuth(AuthErrorCode.EMAIL_VERIFICATION_INVALID,
				new EmailSignupRequest("other@example.invalid", "사용자", PASSWORD, token, consents));
		assertNotSignedUp();
	}

	@Test
	void verificationAndSuccessfulSendAreBothRequired() {
		var row = EmailVerification.request(email, VerificationPurpose.SIGNUP,
				codes.hash(email, VerificationPurpose.SIGNUP, "012345"), NOW.plusSeconds(300));
		verifications.saveAndFlush(row);
		expectAuth(AuthErrorCode.EMAIL_VERIFICATION_INVALID, request(tokens.generate()));
		String token = tokens.generate();
		row.markVerified(tokens.hash(token), NOW, NOW.plusSeconds(600));
		verifications.saveAndFlush(row);
		expectAuth(AuthErrorCode.EMAIL_VERIFICATION_INVALID, request(token));
		assertNotSignedUp();
	}

	@Test
	void expiryBoundaryRejectsProofWhileExpiredCodeDoesNotRejectValidProof() {
		String token = proof(VerificationPurpose.SIGNUP);
		when(clock.instant()).thenReturn(NOW.plusSeconds(600));
		expectAuth(AuthErrorCode.EMAIL_VERIFICATION_TOKEN_EXPIRED, request(token));
		assertNotSignedUp();
		when(clock.instant()).thenReturn(NOW.plusSeconds(599));
		service.signup(request(token));
		assertEquals(NOW.plusSeconds(599), latest().getConsumedAt());
	}

	@Test
	void newerSendInvalidatesOlderProofInDraftSelectionRule() {
		String token = proof(VerificationPurpose.SIGNUP);
		var newer = EmailVerification.request(email, VerificationPurpose.SIGNUP,
				codes.hash(email, VerificationPurpose.SIGNUP, "654321"), NOW.plusSeconds(300));
		newer.markSent(NOW);
		verifications.saveAndFlush(newer);
		expectAuth(AuthErrorCode.EMAIL_VERIFICATION_INVALID, request(token));
		assertNotSignedUp();
	}

	@Test
	void consentPersistenceFailureRollsBackMemberAndConsumedProofAndAllowsRetry() {
		String token = proof(VerificationPurpose.SIGNUP);
		// 동의 일부가 실제 DB에 기록된 뒤 실패하는 상황을 재현합니다.
		doAnswer(invocation -> {
			Iterable<com.bareum.server.domain.term.entity.MemberTerm> rows = invocation.getArgument(0);
			var first = rows.iterator().next();
			jdbc.update(
					"INSERT INTO member_term (member_id, term_id, is_agreed, agreed_at, responded_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
					first.getMember().getId(), first.getTerm().getId(), first.isAgreed(),
					java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW),
					java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
			throw new IllegalStateException("test-only persistence failure");
		}).when(memberTerms).saveAllAndFlush(any());
		assertThrows(IllegalStateException.class, () -> service.signup(request(token)));
		assertNotSignedUp();
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM member_term WHERE term_id = ?", Integer.class,
				termIds.getFirst()));
		reset(memberTerms);
		assertNotNull(service.signup(request(token)).memberId());
	}

	@Test
	void proofExpiringDuringPasswordHashingRollsBackWithoutCreatingMember() {
		String token = proof(VerificationPurpose.SIGNUP);
		doAnswer(invocation -> {
			String hash = (String)invocation.callRealMethod();
			when(clock.instant()).thenReturn(NOW.plusSeconds(600));
			return hash;
		}).when(passwordHashes).hash(PASSWORD);
		expectAuth(AuthErrorCode.EMAIL_VERIFICATION_TOKEN_EXPIRED, request(token));
		assertNotSignedUp();
	}

	@Test
	void databaseUniqueIndexRejectsAccountCreatedAfterTheDuplicatePrecheck() {
		String token = proof(VerificationPurpose.SIGNUP);
		doAnswer(invocation -> {
			String hash = (String)invocation.callRealMethod();
			try (var executor = Executors.newSingleThreadExecutor()) {
				executor.submit(() -> members.saveAndFlush(
						Member.createGoogle(email.toUpperCase(Locale.ROOT), "동시 가입 회원")))
						.get(10, TimeUnit.SECONDS);
			}
			return hash;
		}).when(passwordHashes).hash(PASSWORD);

		expectAuth(AuthErrorCode.EMAIL_ALREADY_REGISTERED, request(token));

		assertNull(latest().getConsumedAt());
		Member competing = members.findByEmailIgnoreCase(email).orElseThrow();
		assertEquals("동시 가입 회원", competing.getName());
		assertEquals(1, jdbc.queryForObject(
				"SELECT count(*) FROM member WHERE upper(email) = upper(?)", Integer.class, email));
		assertEquals(0, jdbc.queryForObject(
				"SELECT count(*) FROM member_term WHERE member_id = ?", Integer.class, competing.getId()));
	}

	@Test
	void unrelatedUniqueViolationDoesNotBecomeAnEmailConflict() {
		String token = proof(VerificationPurpose.SIGNUP);
		String trigger = "signup_other_unique_" + UUID.randomUUID().toString().replace("-", "");
		jdbc.execute("CREATE FUNCTION " + trigger + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
				+ "IF upper(NEW.email) = upper('" + email + "') THEN "
				+ "RAISE unique_violation USING CONSTRAINT = 'test_other_unique', TABLE = 'member', "
				+ "MESSAGE = 'test-only constraint'; END IF; RETURN NEW; END $$");
		jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON member "
				+ "FOR EACH ROW EXECUTE FUNCTION " + trigger + "()");
		try {
			assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
					() -> service.signup(request(token)));
			assertNotSignedUp();
		} finally {
			jdbc.execute("DROP TRIGGER " + trigger + " ON member");
			jdbc.execute("DROP FUNCTION " + trigger + "()");
		}
	}

	@Test
	void concurrentSignupConsumesProofOnlyOnce() throws Exception {
		String token = proof(VerificationPurpose.SIGNUP);
		try (var executor = Executors.newFixedThreadPool(2)) {
			Callable<Boolean> call = () -> {
				try {
					service.signup(request(token));
					return true;
				} catch (AuthException exception) {
					assertSame(AuthErrorCode.EMAIL_VERIFICATION_INVALID, exception.getBaseErrorCode());
					return false;
				}
			};
			int successes = 0;
			for (var result : executor.invokeAll(List.of(call, call)))
				if (result.get())
					successes++;
			assertEquals(1, successes);
		}
		assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM member WHERE email = ?", Integer.class, email));
		assertEquals(4,
				jdbc.queryForObject(
						"SELECT count(*) FROM member_term WHERE member_id IN (SELECT id FROM member WHERE email = ?)",
						Integer.class, email));
		assertNotNull(latest().getConsumedAt());
	}

	@Test
	void sendResendConfirmSignupFlowUsesOnlyNewCodeAndConsumesReturnedProof() {
		when(generator.generate()).thenReturn("012345", "654321");
		var sendRequest = new EmailVerificationRequest(email, VerificationPurpose.SIGNUP);
		var initial = send.send(sendRequest);
		assertEquals("PENDING", initial.status());
		assertEquals(0, initial.expiresIn());
		assertTrue(worker.runOnce());
		Long rowId = latest().getId();
		when(clock.instant()).thenReturn(NOW.plusSeconds(2));
		var delivery = resend.resend(sendRequest);
		assertEquals("PENDING", delivery.status());
		assertEquals(0, delivery.expiresIn());
		assertNotEquals(initial.requestId(), delivery.requestId());
		assertEquals("PENDING", deliveries.status(delivery.requestId()).orElseThrow().status());
		assertSame(AuthErrorCode.EMAIL_VERIFICATION_INVALID,
				assertThrows(AuthException.class, () -> confirm.confirm(
						new EmailVerificationConfirmRequest(email, VerificationPurpose.SIGNUP, "654321")))
						.getBaseErrorCode());
		assertEquals(0, latest().getAttemptCount());
		assertTrue(worker.runOnce());
		assertEquals("SENT", deliveries.status(delivery.requestId()).orElseThrow().status());
		assertEquals("CANCELLED", deliveries.status(initial.requestId()).orElseThrow().status());
		assertEquals(rowId, latest().getId());
		assertSame(AuthErrorCode.EMAIL_VERIFICATION_CODE_MISMATCH,
				assertThrows(AuthException.class, () -> confirm.confirm(
						new EmailVerificationConfirmRequest(email, VerificationPurpose.SIGNUP, "012345")))
						.getBaseErrorCode());
		var confirmation =
				confirm.confirm(new EmailVerificationConfirmRequest(email, VerificationPurpose.SIGNUP, "654321"));
		assertFalse(members.existsByEmailIgnoreCase(email));
		var result = service.signup(request(confirmation.verificationToken()));
		assertNotNull(result.memberId());
		assertNotNull(latest().getConsumedAt());
		verify(mail).sendVerificationCode(email, "012345", VerificationPurpose.SIGNUP);
		verify(mail).sendVerificationCode(email, "654321", VerificationPurpose.SIGNUP);
	}

	@Test
	void signupPreservesVerifiedRecipientCaseInsteadOfRequestSpelling() {
		String token = proof(VerificationPurpose.SIGNUP);
		String verifiedAddress = email.replace("signup-", "SignUp-");
		jdbc.update("UPDATE email_verification SET email = ? WHERE id = ?", verifiedAddress, latest().getId());
		var response = service
				.signup(new EmailSignupRequest(email.toUpperCase(Locale.ROOT), "사용자", PASSWORD, token, consents));
		assertEquals(verifiedAddress, members.findById(response.memberId()).orElseThrow().getEmail());
		assertTrue(members.existsByEmailIgnoreCase(email));
		assertNotNull(latest().getConsumedAt());
	}

}
