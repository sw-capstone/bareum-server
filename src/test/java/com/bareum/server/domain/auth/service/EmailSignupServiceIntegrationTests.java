package com.bareum.server.domain.auth.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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
import com.bareum.server.domain.term.dto.request.TermConsentRequest;
import com.bareum.server.domain.term.entity.Term;
import com.bareum.server.domain.term.enums.TermCode;
import com.bareum.server.domain.term.exception.TermErrorCode;
import com.bareum.server.domain.term.exception.TermException;
import com.bareum.server.domain.term.repository.MemberTermRepository;
import com.bareum.server.domain.term.repository.TermRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
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

@SpringBootTest(properties = {
        // 테스트 전용 제한 값이며 운영 정책이 아닙니다.
        "auth.email-verification.minimum-send-interval-seconds=1",
        "auth.email-verification.max-sends-per-hour=20",
        "auth.email-verification.max-sends-per-day=30",
        "auth.email-verification.max-verification-attempts=3",
        "auth.email-verification.hash-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class EmailSignupServiceIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");
    private static final String PASSWORD = String.join("", "Password", "123", "!");
    private final String email = "signup-" + UUID.randomUUID() + "@example.invalid";
    private final List<Long> termIds = new ArrayList<>();
    private List<TermConsentRequest> consents;

    @Autowired private EmailSignupService service;
    @Autowired private EmailVerificationConfirmService confirm;
    @Autowired private EmailVerificationSendService send;
    @Autowired private EmailVerificationResendService resend;
    @Autowired private EmailVerificationRepository verifications;
    @Autowired private MemberRepository members;
    @Autowired private TermRepository terms;
    @Autowired private EmailVerificationCodeHasher codes;
    @Autowired private EmailVerificationTokenGenerator tokens;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private MemberTermRepository memberTerms;
    @MockitoSpyBean private PasswordHashService passwordHashes;
    @MockitoBean(name = "emailVerificationClock") private Clock clock;
    @MockitoBean private EmailVerificationMailSender mail;
    @MockitoBean private EmailVerificationCodeGenerator generator;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        consents = new ArrayList<>();
        for (TermCode code : TermCode.values()) {
            Term term = terms.saveAndFlush(Term.create(code, "test-" + UUID.randomUUID().toString().substring(0, 8),
                    code != TermCode.MARKETING_CONSENT));
            termIds.add(term.getId());
            consents.add(new TermConsentRequest(term.getId(), term.getVersion(), code != TermCode.MARKETING_CONSENT));
        }
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM member_term WHERE member_id IN (SELECT id FROM member WHERE lower(email) = lower(?))", email);
        jdbc.update("DELETE FROM member WHERE lower(email) = lower(?)", email);
        jdbc.update("DELETE FROM email_verification_send_log WHERE lower(email) = lower(?)", email);
        jdbc.update("DELETE FROM email_verification WHERE lower(email) = lower(?)", email);
        for (Long id : termIds) jdbc.update("DELETE FROM term WHERE id = ?", id);
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
        return new EmailSignupRequest(email, "사용자", PASSWORD, token, List.copyOf(consents));
    }

    private EmailVerification latest() {
        return verifications.findFirstByEmailIgnoreCaseAndPurposeOrderByIdDesc(email, VerificationPurpose.SIGNUP).orElseThrow();
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
        var response = service.signup(new EmailSignupRequest(email.toUpperCase(Locale.ROOT), "사용자", PASSWORD, token, consents));
        var member = members.findById(response.memberId()).orElseThrow();
        assertEquals(email, member.getEmail());
        assertEquals(SignupMethod.LOCAL, member.getSignupMethod());
        assertTrue(passwordHashes.matches(PASSWORD, member.getPasswordHash()));
        assertNotEquals(PASSWORD, member.getPasswordHash());
        assertEquals(NOW, latest().getConsumedAt());
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM member_term WHERE member_id = ?", Integer.class, member.getId()));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM member_term WHERE member_id = ? AND is_agreed = true AND agreed_at = ? AND responded_at = ? AND withdrawn_at IS NULL",
                Integer.class, member.getId(), java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW)));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM member_term WHERE member_id = ? AND is_agreed = false AND agreed_at IS NULL AND responded_at = ? AND withdrawn_at IS NULL",
                Integer.class, member.getId(), java.sql.Timestamp.from(NOW)));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM member_auth WHERE member_id = ?", Integer.class, member.getId()));
        expectAuth(AuthErrorCode.EMAIL_VERIFICATION_INVALID, request(token));
    }

    @Test
    void optionalConsentCanBeOmitted() {
        String token = proof(VerificationPurpose.SIGNUP);
        consents.removeIf(c -> !c.agreed());
        var response = service.signup(request(token));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM member_term WHERE member_id = ?", Integer.class, response.memberId()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingOrRefusedRequiredConsentDoesNotConsumeProof(boolean missing) {
        String token = proof(VerificationPurpose.SIGNUP);
        var first = consents.removeFirst();
        if (!missing) consents.add(new TermConsentRequest(first.termId(), first.version(), false));
        expectTerm(TermErrorCode.REQUIRED_TERM_NOT_AGREED, request(token));
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate", "unknown", "version"})
    void invalidConsentIdentityOrVersionIsRejected(String type) {
        String token = proof(VerificationPurpose.SIGNUP);
        var first = consents.getFirst();
        switch (type) {
            case "duplicate" -> consents.add(first);
            case "unknown" -> consents.set(0, new TermConsentRequest(Long.MAX_VALUE, first.version(), true));
            case "version" -> consents.set(0, new TermConsentRequest(first.termId(), "stale", true));
        }
        expectTerm(TermErrorCode.INVALID_CONSENT_REQUEST, request(token));
    }

    @Test
    void absentCatalogBlocksSignupInsteadOfSkippingRequiredConsent() {
        String token = proof(VerificationPurpose.SIGNUP);
        for (Long id : termIds) terms.deleteById(id);
        expectTerm(TermErrorCode.SIGNUP_TERMS_NOT_CONFIGURED, request(token));
    }

    @Test
    void ambiguousCurrentTermVersionBlocksSignup() {
        String token = proof(VerificationPurpose.SIGNUP);
        var extra = terms.saveAndFlush(Term.create(TermCode.TERMS_OF_SERVICE, "other", true));
        termIds.add(extra.getId());
        expectTerm(TermErrorCode.SIGNUP_TERMS_NOT_CONFIGURED, request(token));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TERMS_OF_SERVICE", "PRIVACY_POLICY"})
    void coreTermsCannotBeConfiguredAsOptional(String code) {
        String token = proof(VerificationPurpose.SIGNUP);
        jdbc.update("UPDATE term SET is_required = false WHERE code = ?", code);
        expectTerm(TermErrorCode.SIGNUP_TERMS_NOT_CONFIGURED, request(token));
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
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM member WHERE lower(email) = lower(?)", Integer.class, email));
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
            jdbc.update("INSERT INTO member_term (member_id, term_id, is_agreed, agreed_at, responded_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    first.getMember().getId(), first.getTerm().getId(), first.isAgreed(),
                    java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW),
                    java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
            throw new IllegalStateException("test-only persistence failure");
        }).when(memberTerms).saveAllAndFlush(any());
        assertThrows(IllegalStateException.class, () -> service.signup(request(token)));
        assertNotSignedUp();
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM member_term WHERE term_id = ?", Integer.class, consents.getFirst().termId()));
        reset(memberTerms);
        assertNotNull(service.signup(request(token)).memberId());
    }

    @Test
    void proofExpiringDuringPasswordHashingRollsBackWithoutCreatingMember() {
        String token = proof(VerificationPurpose.SIGNUP);
        doAnswer(invocation -> {
            String hash = (String) invocation.callRealMethod();
            when(clock.instant()).thenReturn(NOW.plusSeconds(600));
            return hash;
        }).when(passwordHashes).hash(PASSWORD);
        expectAuth(AuthErrorCode.EMAIL_VERIFICATION_TOKEN_EXPIRED, request(token));
        assertNotSignedUp();
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
            for (var result : executor.invokeAll(List.of(call, call))) if (result.get()) successes++;
            assertEquals(1, successes);
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM member WHERE email = ?", Integer.class, email));
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM member_term WHERE member_id IN (SELECT id FROM member WHERE email = ?)", Integer.class, email));
        assertNotNull(latest().getConsumedAt());
    }

    @Test
    void sendResendConfirmSignupFlowUsesOnlyNewCodeAndConsumesReturnedProof() {
        when(generator.generate()).thenReturn("012345", "654321");
        var sendRequest = new EmailVerificationRequest(email, VerificationPurpose.SIGNUP);
        send.send(sendRequest);
        Long rowId = latest().getId();
        when(clock.instant()).thenReturn(NOW.plusSeconds(2));
        resend.resend(sendRequest);
        assertEquals(rowId, latest().getId());
        assertSame(AuthErrorCode.EMAIL_VERIFICATION_CODE_MISMATCH,
                assertThrows(AuthException.class, () -> confirm.confirm(
                        new EmailVerificationConfirmRequest(email, VerificationPurpose.SIGNUP, "012345"))).getBaseErrorCode());
        var confirmation = confirm.confirm(new EmailVerificationConfirmRequest(email, VerificationPurpose.SIGNUP, "654321"));
        assertFalse(members.existsByEmailIgnoreCase(email));
        var result = service.signup(request(confirmation.verificationToken()));
        assertNotNull(result.memberId());
        assertNotNull(latest().getConsumedAt());
        verify(mail).sendVerificationCode(email, "012345", VerificationPurpose.SIGNUP);
        verify(mail).sendVerificationCode(email, "654321", VerificationPurpose.SIGNUP);
    }
}
