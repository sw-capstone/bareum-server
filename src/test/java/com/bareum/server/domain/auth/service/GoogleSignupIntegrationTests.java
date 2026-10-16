package com.bareum.server.domain.auth.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bareum.server.domain.auth.dto.request.*;
import com.bareum.server.domain.auth.entity.EmailVerification;
import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.domain.auth.exception.*;
import com.bareum.server.domain.auth.repository.*;
import com.bareum.server.domain.member.entity.Member;
import com.bareum.server.domain.member.repository.*;
import com.bareum.server.domain.term.dto.request.SignupAgreementsRequest;
import com.bareum.server.domain.term.entity.Term;
import com.bareum.server.domain.term.enums.TermCode;
import com.bareum.server.domain.term.exception.*;
import com.bareum.server.domain.term.repository.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "auth.login.allowed-origins=https://web.example.invalid",
            "auth.google.client-ids=test-client",
            "auth.email-verification.hash-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        })
class GoogleSignupIntegrationTests {
    private static final Instant NOW = Instant.parse("2026-10-11T14:00:00Z");
    @MockitoBean private GoogleIdTokenVerifier verifier;

    @MockitoBean(name = "loginClock")
    private Clock clock;

    @MockitoBean(name = "emailVerificationClock")
    private Clock emailClock;

    @MockitoSpyBean private MemberTermRepository memberTerms;
    @MockitoSpyBean private MemberAuthRepository auths;
    @MockitoSpyBean private LoginSessionService sessions;
    @Autowired private GoogleSignupService service;
    @Autowired private GoogleLoginService login;
    @Autowired private GoogleSignupTransactionService transaction;
    @Autowired private GoogleSignupProofRepository proofs;
    @Autowired private MemberRepository members;
    @Autowired private TermRepository terms;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoginTokenGenerator tokens;
    @Autowired private EmailSignupService emailSignup;
    @Autowired private EmailVerificationRepository verifications;
    @Autowired private EmailVerificationTokenGenerator emailTokens;
    @Autowired private EmailVerificationCodeHasher codes;
    @Autowired private TokenRenewService renew;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private JsonMapper mapper;
    @LocalServerPort private int port;
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<String> subjects = new ArrayList<>(), addresses = new ArrayList<>();
    private final List<Long> termIds = new ArrayList<>();
    private GoogleIdentity identity;
    private String proof;
    private SignupAgreementsRequest agreements;

    @BeforeEach
    void setup() {
        when(clock.instant()).thenReturn(NOW);
        when(emailClock.instant()).thenReturn(NOW);
        identity = newIdentity();
        agreements = new SignupAgreementsRequest(true, true, true, false);
        for (var code : TermCode.values())
            termIds.add(
                    terms.saveAndFlush(
                                    Term.create(
                                            code,
                                            "google-signup-test-v1",
                                            code != TermCode.MARKETING_CONSENT))
                            .getId());
        proof = issue(identity);
    }

    private GoogleIdentity newIdentity() {
        var result =
                new GoogleIdentity(
                        "signup-sub-" + UUID.randomUUID(),
                        "Signup-" + UUID.randomUUID() + "@gmail.com",
                        "Google 이름",
                        null);
        subjects.add(result.subject());
        addresses.add(result.email());
        return result;
    }

    private String issue(GoogleIdentity value) {
        String idToken = "fixture-" + value.subject();
        when(verifier.verify(idToken)).thenReturn(value);
        return login.login(idToken).signupToken();
    }

    @AfterEach
    void cleanup() {
        reset(memberTerms, auths, sessions);
        for (String sub : subjects)
            jdbc.update("DELETE FROM google_signup_proof WHERE google_subject=?", sub);
        for (String email : addresses) {
            var ids =
                    jdbc.query(
                            "SELECT id FROM member WHERE upper(email)=upper(?)",
                            (rs, n) -> rs.getLong(1),
                            email);
            for (long id : ids) {
                jdbc.update("DELETE FROM member_term WHERE member_id=?", id);
                jdbc.update("DELETE FROM refresh_token WHERE member_id=?", id);
                jdbc.update("DELETE FROM member_auth WHERE member_id=?", id);
                jdbc.update("DELETE FROM member WHERE id=?", id);
            }
            jdbc.update("DELETE FROM email_verification WHERE upper(email)=upper(?)", email);
            jdbc.update(
                    "DELETE FROM login_request_window WHERE email_key=?",
                    tokens.hash(
                            "login-limit",
                            jdbc.queryForObject("SELECT upper(?)", String.class, email)));
        }
        for (long id : termIds) jdbc.update("DELETE FROM term WHERE id=?", id);
    }

    private GoogleSignupRequest input() {
        return new GoogleSignupRequest(proof, agreements);
    }

    @Test
    void signupDoesNotValidateTermVersionAndRecordsConsents() {
        jdbc.update("UPDATE term SET version = '' WHERE code = 'TERMS_OF_SERVICE'");
        var result = service.signup(input());
        long memberId = sessions.authenticate(result.accessToken()).orElseThrow().memberId();
        assertEquals("", jdbc.queryForObject(
                "SELECT t.version FROM member_term mt JOIN term t ON t.id = mt.term_id WHERE mt.member_id = ? AND t.code = 'TERMS_OF_SERVICE'",
                String.class, memberId));
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM member_term WHERE member_id = ?",
                Integer.class, memberId));
        assertNotNull(consumed());
    }

    private Object consumed() {
        return jdbc.queryForMap(
                        "SELECT consumed_at FROM google_signup_proof WHERE token_hash=?",
                        tokens.hash("google-signup", proof))
                .get("consumed_at");
    }

    private int memberCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM member WHERE upper(email)=upper(?)",
                Integer.class,
                identity.email());
    }

    private void unchanged() {
        assertEquals(0, memberCount());
        assertNull(consumed());
    }

    private void authError(AuthErrorCode code, GoogleSignupRequest request) {
        assertSame(
                code,
                assertThrows(AuthException.class, () -> service.signup(request))
                        .getBaseErrorCode());
    }

    private HttpResponse<String> request(String method, String path, String body, String... headers)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        for (int i = 0; i < headers.length; i += 2) builder.header(headers[i], headers[i + 1]);
        return client.send(
                builder.method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) {
        return mapper.readTree(response.body());
    }

    private List<String> csrf() throws Exception {
        var result = request("GET", "/api/v1/auth/csrf", null);
        assertEquals(200, result.statusCode());
        return new ArrayList<>(
                List.of(
                        "Cookie",
                        result.headers().allValues("Set-Cookie").stream()
                                .filter(c -> c.startsWith("XSRF-TOKEN="))
                                .findFirst()
                                .orElseThrow()
                                .split(";", 2)[0],
                        "X-CSRF-TOKEN",
                        json(result).path("token").asString(),
                        "Origin",
                        "https://web.example.invalid",
                        "Content-Type",
                        "application/json"));
    }

    private HttpResponse<String> post(String body, String... extra) throws Exception {
        var headers = csrf();
        headers.addAll(List.of(extra));
        return request("POST", "/api/v1/auth/signup/google", body, headers.toArray(String[]::new));
    }

    private String body() {
        return mapper.writeValueAsString(input());
    }

    @Test
    void signupCreatesVerifiedProfileGoogleLinkAllConsentsAndCommonSessionAtomically()
            throws Exception {
        var response = post(body());
        assertEquals(201, response.statusCode(), response.body());
        var data = json(response);
        String access = data.path("accessToken").asString();
        assertEquals("Bearer", data.path("tokenType").asString());
        assertEquals(900, data.path("expiresIn").asInt());
        assertEquals(28800, data.path("sessionExpiresIn").asInt());
        assertFalse(
                data.has("signupToken")
                        || data.has("refreshToken")
                        || data.has("email")
                        || data.has("googleSubject"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        for (String expected :
                List.of(
                        "HttpOnly",
                        "Secure",
                        "SameSite=None",
                        "Path=/api/v1/auth",
                        "Max-Age=28800")) assertTrue(cookie.contains(expected));
        long memberId = sessions.authenticate(access).orElseThrow().memberId();
        var member = members.findById(memberId).orElseThrow();
        assertEquals(identity.email(), member.getEmail());
        assertEquals(identity.name(), member.getName());
        assertNull(member.getPasswordHash());
        assertEquals("GOOGLE", member.getSignupMethod().name());
        var auth = jdbc.queryForMap("SELECT * FROM member_auth WHERE member_id=?", memberId);
        assertEquals(identity.subject(), auth.get("provider_user_id"));
        assertEquals(identity.email(), auth.get("provider_email"));
        assertEquals(Timestamp.from(NOW), auth.get("linked_at"));
        assertEquals(Timestamp.from(NOW), consumed());
        assertEquals(
                4,
                jdbc.queryForObject(
                        "SELECT count(*) FROM member_term WHERE member_id=? AND responded_at=?",
                        Integer.class,
                        memberId,
                        Timestamp.from(NOW)));
        assertEquals(
                3,
                jdbc.queryForObject(
                        "SELECT count(*) FROM member_term WHERE member_id=? AND is_agreed AND"
                            + " agreed_at=?",
                        Integer.class,
                        memberId,
                        Timestamp.from(NOW)));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM member_term WHERE member_id=? AND NOT is_agreed AND"
                            + " agreed_at IS NULL",
                        Integer.class,
                        memberId));
        var me = request("GET", "/api/v1/auth/me", null, "Authorization", "Bearer " + access);
        assertEquals(200, me.statusCode());
        assertEquals(identity.email(), json(me).path("email").asString());
        String refresh = cookie.split(";", 2)[0].substring("bareum_refresh=".length());
        assertFalse(response.body().contains(refresh));
        var rotated = renew.renew(refresh);
        assertTrue(sessions.authenticate(access).isPresent());
        assertTrue(sessions.authenticate(rotated.accessToken()).isPresent());
        var protection = csrf();
        protection.set(1, protection.get(1) + "; bareum_refresh=" + rotated.refreshToken());
        assertEquals(
                204,
                request("POST", "/api/v1/auth/logout", null, protection.toArray(String[]::new))
                        .statusCode());
        assertTrue(sessions.authenticate(rotated.accessToken()).isEmpty());
        var again = login.login("fixture-" + identity.subject());
        assertNotNull(again.login());
        assertNull(again.signupToken());
        assertEquals(
                4,
                jdbc.queryForObject(
                        "SELECT count(*) FROM member_term WHERE member_id=?",
                        Integer.class,
                        memberId));
    }

    @Test
    void usedProofIsRejectedWithoutReplacingExistingSessionOrConsents() {
        var issued = service.signup(input());
        authError(AuthErrorCode.INVALID_GOOGLE_SIGNUP_PROOF, input());
        assertTrue(sessions.authenticate(issued.accessToken()).isPresent());
        assertEquals(1, memberCount());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "not-a-token",
                "gs_short",
                "gs_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "access"
            })
    void invalidProofOrWrongPurposeCannotCreateAccount(String value) {
        if (value.equals("access")) value = tokens.generate();
        authError(
                AuthErrorCode.INVALID_GOOGLE_SIGNUP_PROOF,
                new GoogleSignupRequest(value, agreements));
        unchanged();
    }

    @Test
    void nullProofIsInvalidForServiceCalls() {
        authError(
                AuthErrorCode.INVALID_GOOGLE_SIGNUP_PROOF,
                new GoogleSignupRequest(null, agreements));
        unchanged();
    }

    @Test
    void replacedProofIsRejectedAndLatestProofWorks() {
        String latest = issue(identity);
        authError(AuthErrorCode.INVALID_GOOGLE_SIGNUP_PROOF, input());
        assertNotNull(service.signup(new GoogleSignupRequest(latest, agreements)).accessToken());
        assertEquals(1, memberCount());
    }

    @Test
    void expiryBoundaryRejectsButOneSecondBeforeExpirySucceeds() {
        when(clock.instant()).thenReturn(NOW.plusSeconds(600));
        authError(AuthErrorCode.INVALID_GOOGLE_SIGNUP_PROOF, input());
        unchanged();
        when(clock.instant()).thenReturn(NOW.plusSeconds(599));
        assertNotNull(service.signup(input()).accessToken());
    }

    @ParameterizedTest
    @ValueSource(strings = {"age", "service", "privacy"})
    void requiredDisagreementRollsBackWithoutConsumingProof(String field) {
        agreements =
                new SignupAgreementsRequest(
                        !field.equals("age"),
                        !field.equals("service"),
                        !field.equals("privacy"),
                        false);
        assertSame(
                TermErrorCode.REQUIRED_TERM_NOT_AGREED,
                assertThrows(TermException.class, () -> service.signup(input()))
                        .getBaseErrorCode());
        unchanged();
    }

    @Test
    void marketingAgreementIsStoredWhenSelected() {
        agreements = new SignupAgreementsRequest(true, true, true, true);
        var issued = service.signup(input());
        assertEquals(
                4,
                jdbc.queryForObject(
                        "SELECT count(*) FROM member_term WHERE member_id=? AND is_agreed",
                        Integer.class,
                        sessions.authenticate(issued.accessToken()).orElseThrow().memberId()));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "absent",
                "null-field",
                "required-flag",
                "duplicate-version",
                "missing-term"
            })
    void invalidAgreementsOrCorruptTermDataCannotPartiallyCreateAccount(String mode) {
        switch (mode) {
            case "absent" -> agreements = null;
            case "null-field" -> agreements = new SignupAgreementsRequest(true, true, true, null);
            case "required-flag" ->
                    jdbc.update("UPDATE term SET is_required=false WHERE id=?", termIds.getFirst());
            case "duplicate-version" ->
                    termIds.add(
                            terms.saveAndFlush(
                                            Term.create(
                                                    TermCode.AGE_OVER_14, "another-version", true))
                                    .getId());
            case "missing-term" -> jdbc.update("DELETE FROM term WHERE id=?", termIds.getFirst());
        }
        assertThrows(TermException.class, () -> service.signup(input()));
        unchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"LOCAL", "GOOGLE", "SUSPENDED", "deleted-only", "withdrawn-only"})
    void emailCollisionAddedAfterProofIsCaseInsensitiveAndCannotAutoLink(String state) {
        var member =
                members.saveAndFlush(
                        state.equals("GOOGLE")
                                ? Member.createGoogle(
                                        identity.email().toLowerCase(Locale.ROOT), "이미 가입")
                                : Member.createLocal(
                                        identity.email().toLowerCase(Locale.ROOT),
                                        "이미 가입",
                                        "fixture-hash"));
        if (state.equals("SUSPENDED"))
            jdbc.update("UPDATE member SET status='SUSPENDED' WHERE id=?", member.getId());
        if (state.equals("deleted-only"))
            jdbc.update("UPDATE member SET status='DELETED' WHERE id=?", member.getId());
        if (state.equals("withdrawn-only"))
            jdbc.update(
                    "UPDATE member SET withdrawn_at=? WHERE id=?",
                    Timestamp.from(NOW),
                    member.getId());
        authError(
                state.equals("GOOGLE")
                        ? AuthErrorCode.EMAIL_ALREADY_REGISTERED
                        : AuthErrorCode.EMAIL_LOGIN_REQUIRED,
                input());
        assertNull(consumed());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM member_auth WHERE provider_user_id=?",
                        Integer.class,
                        identity.subject()));
    }

    @Test
    void fullyWithdrawnEmailAllowsNewAccountWithoutInheritingIdentityOrData() {
        var old =
                members.saveAndFlush(Member.createLocal(identity.email(), "이전 회원", "fixture-hash"));
        jdbc.update(
                "UPDATE member SET status='DELETED',withdrawn_at=? WHERE id=?",
                Timestamp.from(NOW),
                old.getId());
        var issued = service.signup(input());
        long id = sessions.authenticate(issued.accessToken()).orElseThrow().memberId();
        assertNotEquals(old.getId(), id);
        assertEquals(2, memberCount());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM member_term WHERE member_id=?",
                        Integer.class,
                        old.getId()));
    }

    @Test
    void alreadyLinkedGoogleSubjectCannotCreateSecondAccountEvenIfEmailChanges() {
        var other = newIdentity();
        var old = members.saveAndFlush(Member.createGoogle(other.email(), "기존"));
        jdbc.update(
                "INSERT INTO member_auth(member_id,provider,provider_user_id,provider_email)"
                    + " VALUES(?,'GOOGLE',?,?)",
                old.getId(),
                identity.subject(),
                other.email());
        authError(AuthErrorCode.INVALID_GOOGLE_SIGNUP_PROOF, input());
        unchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"consent", "auth", "session"})
    void persistenceFailureAfterPartialWritesRollsBackEverythingAndAllowsRetry(String point) {
        if (point.equals("consent"))
            doAnswer(
                            call -> {
                                insertFirstConsent(call.getArgument(0));
                                throw new DataIntegrityViolationException("fixture-private-sql");
                            })
                    .when(memberTerms)
                    .saveAllAndFlush(any());
        if (point.equals("auth"))
            doAnswer(
                            call -> {
                                com.bareum.server.domain.member.entity.MemberAuth auth =
                                        call.getArgument(0);
                                jdbc.update(
                                        "INSERT INTO"
                                            + " member_auth(member_id,provider,provider_user_id,provider_email,linked_at)"
                                            + " VALUES(?,?,?,?,?)",
                                        auth.getMember().getId(),
                                        auth.getProvider().name(),
                                        auth.getProviderUserId(),
                                        auth.getProviderEmail(),
                                        Timestamp.from(auth.getLinkedAt()));
                                throw new DataIntegrityViolationException("fixture-private-sql");
                            })
                    .when(auths)
                    .saveAndFlush(any());
        if (point.equals("session"))
            doAnswer(
                            call -> {
                                call.callRealMethod();
                                throw new DataIntegrityViolationException("fixture-private-sql");
                            })
                    .when(sessions)
                    .issueForLockedMember(anyLong());
        authError(AuthErrorCode.GOOGLE_SIGNUP_UNAVAILABLE, input());
        unchanged();
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM member_term WHERE term_id=?",
                        Integer.class,
                        termIds.getFirst()));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM member_auth WHERE provider_user_id=?",
                        Integer.class,
                        identity.subject()));
        reset(memberTerms, auths, sessions);
        assertNotNull(service.signup(input()).accessToken());
    }

    private com.bareum.server.domain.term.entity.MemberTerm insertFirstConsent(
            Iterable<com.bareum.server.domain.term.entity.MemberTerm> rows) {
        var first = rows.iterator().next();
        jdbc.update(
                "INSERT INTO member_term(member_id,term_id,is_agreed,agreed_at,responded_at)"
                    + " VALUES(?,?,?,?,?)",
                first.getMember().getId(),
                first.getTerm().getId(),
                first.isAgreed(),
                Timestamp.from(first.getAgreedAt()),
                Timestamp.from(first.getRespondedAt()));
        return first;
    }

    @Test
    void proofExpiryDuringPersistenceRollsBackAllWrites() {
        doAnswer(
                        call -> {
                            var first = insertFirstConsent(call.getArgument(0));
                            when(clock.instant()).thenReturn(NOW.plusSeconds(600));
                            return List.of(first);
                        })
                .when(memberTerms)
                .saveAllAndFlush(any());
        authError(AuthErrorCode.INVALID_GOOGLE_SIGNUP_PROOF, input());
        unchanged();
    }

    @Test
    void concurrentRequestsConsumeOneProofAndIssueOneSession() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> task =
                    () -> {
                        start.await();
                        try {
                            service.signup(input());
                            return true;
                        } catch (AuthException e) {
                            assertSame(
                                    AuthErrorCode.INVALID_GOOGLE_SIGNUP_PROOF,
                                    e.getBaseErrorCode());
                            return false;
                        }
                    };
            var a = executor.submit(task);
            var b = executor.submit(task);
            start.countDown();
            assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, memberCount());
        assertNotNull(consumed());
    }

    @Test
    void differentSubjectsWithSameEmailCanCreateOnlyOneAccount() throws Exception {
        var other =
                new GoogleIdentity(
                        "other-" + UUID.randomUUID(),
                        identity.email().toLowerCase(Locale.ROOT),
                        "다른 이름",
                        null);
        subjects.add(other.subject());
        String otherProof = issue(other);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> one = () -> concurrentResult(input(), start);
            Callable<Boolean> two =
                    () -> concurrentResult(new GoogleSignupRequest(otherProof, agreements), start);
            var a = executor.submit(one);
            var b = executor.submit(two);
            start.countDown();
            assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, memberCount());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM google_signup_proof WHERE google_subject IN (?,?) AND"
                            + " consumed_at IS NOT NULL",
                        Integer.class,
                        identity.subject(),
                        other.subject()));
    }

    private boolean concurrentResult(GoogleSignupRequest input, CountDownLatch start)
            throws Exception {
        start.await();
        try {
            service.signup(input);
            return true;
        } catch (AuthException e) {
            assertSame(AuthErrorCode.EMAIL_ALREADY_REGISTERED, e.getBaseErrorCode());
            return false;
        }
    }

    @Test
    void emailAndGoogleSignupShareEmailLockAndOnlyOneMethodWins() throws Exception {
        String emailToken = emailTokens.generate();
        var row =
                EmailVerification.request(
                        identity.email(),
                        VerificationPurpose.SIGNUP,
                        codes.hash(identity.email(), VerificationPurpose.SIGNUP, "012345"),
                        NOW.plusSeconds(300));
        row.markSent(NOW);
        row.markVerified(emailTokens.hash(emailToken), NOW, NOW.plusSeconds(600));
        verifications.saveAndFlush(row);
        var localRequest =
                new EmailSignupRequest(
                        identity.email().toLowerCase(Locale.ROOT),
                        "이메일 이름",
                        String.join("", "Password", "123", "!"),
                        emailToken,
                        agreements);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a =
                    executor.submit(
                            () -> {
                                start.await();
                                try {
                                    service.signup(input());
                                    return true;
                                } catch (AuthException e) {
                                    assertSame(
                                            AuthErrorCode.EMAIL_LOGIN_REQUIRED,
                                            e.getBaseErrorCode());
                                    return false;
                                }
                            });
            var b =
                    executor.submit(
                            () -> {
                                start.await();
                                try {
                                    emailSignup.signup(localRequest);
                                    return true;
                                } catch (AuthException e) {
                                    assertSame(
                                            AuthErrorCode.EMAIL_ALREADY_REGISTERED,
                                            e.getBaseErrorCode());
                                    return false;
                                }
                            });
            start.countDown();
            assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, memberCount());
    }

    @Test
    void loginProofReplacementAndSignupDoNotDeadlockOrCreateDuplicateAccount() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var signup =
                    executor.submit(
                            () -> {
                                start.await();
                                try {
                                    return service.signup(input());
                                } catch (AuthException e) {
                                    assertSame(
                                            AuthErrorCode.INVALID_GOOGLE_SIGNUP_PROOF,
                                            e.getBaseErrorCode());
                                    return null;
                                }
                            });
            var next =
                    executor.submit(
                            () -> {
                                start.await();
                                return login.login("fixture-" + identity.subject());
                            });
            start.countDown();
            var issued = signup.get(10, TimeUnit.SECONDS);
            var outcome = next.get(10, TimeUnit.SECONDS);
            if (issued == null) {
                assertEquals(0, memberCount());
                assertNotNull(outcome.signupToken());
                assertNotNull(
                        service.signup(new GoogleSignupRequest(outcome.signupToken(), agreements)));
            } else {
                assertNotNull(outcome.login());
                assertNull(outcome.signupToken());
            }
        }
        assertEquals(1, memberCount());
    }

    @Test
    void proofExpiryWhileWaitingForSubjectLockCannotBeBypassed() throws Exception {
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var locker =
                    executor.submit(
                            () ->
                                    new TransactionTemplate(manager)
                                            .execute(
                                                    status -> {
                                                        jdbc.execute(
                                                                "SELECT"
                                                                    + " pg_advisory_xact_lock(hashtextextended('bareum:google:"
                                                                        + identity.subject()
                                                                        + "',0))");
                                                        held.countDown();
                                                        try {
                                                            release.await(5, TimeUnit.SECONDS);
                                                        } catch (InterruptedException e) {
                                                            throw new RuntimeException(e);
                                                        }
                                                        return null;
                                                    }));
            assertTrue(held.await(5, TimeUnit.SECONDS));
            var attempt =
                    executor.submit(
                            () -> assertThrows(AuthException.class, () -> service.signup(input())));
            when(clock.instant()).thenReturn(NOW.plusSeconds(600));
            release.countDown();
            locker.get(10, TimeUnit.SECONDS);
            assertSame(
                    AuthErrorCode.INVALID_GOOGLE_SIGNUP_PROOF,
                    attempt.get(10, TimeUnit.SECONDS).getBaseErrorCode());
        } finally {
            release.countDown();
        }
        unchanged();
    }

    @Test
    void dtoAndStoredProofDoNotExposeSecretsViaToString() {
        assertFalse(input().toString().contains(proof));
        var p =
                new TransactionTemplate(manager)
                        .execute(
                                status ->
                                        proofs.lock(tokens.hash("google-signup", proof))
                                                .orElseThrow());
        assertFalse(
                p.toString().contains(identity.subject())
                        || p.toString().contains(identity.email()));
    }

    @Test
    void expiredBearerDoesNotBlockSignupButCsrfRemainsRequired() throws Exception {
        var response = post(body(), "Authorization", "Bearer " + tokens.generate());
        assertEquals(201, response.statusCode(), response.body());
    }

    @Test
    void csrfCannotBeBypassedByBearerOrSignupProof() throws Exception {
        for (var authorization : List.of("", "Bearer " + tokens.generate(), "Bearer " + proof)) {
            var headers = new ArrayList<>(List.of("Content-Type", "application/json"));
            if (!authorization.isEmpty()) headers.addAll(List.of("Authorization", authorization));
            assertEquals(
                    403,
                    request(
                                    "POST",
                                    "/api/v1/auth/signup/google",
                                    body(),
                                    headers.toArray(String[]::new))
                            .statusCode());
        }
        unchanged();
    }

    @Test
    void csrfMismatchAndDisallowedOriginAreRejected() throws Exception {
        var protection = csrf();
        protection.set(3, "wrong");
        assertEquals(
                403,
                request(
                                "POST",
                                "/api/v1/auth/signup/google",
                                body(),
                                protection.toArray(String[]::new))
                        .statusCode());
        protection = csrf();
        protection.set(5, "https://attacker.example.invalid");
        assertEquals(
                403,
                request(
                                "POST",
                                "/api/v1/auth/signup/google",
                                body(),
                                protection.toArray(String[]::new))
                        .statusCode());
        unchanged();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "broken-json",
                "{\"signupToken\":null,\"agreements\":{}}",
                "{\"signupToken\":\"gs_short\",\"agreements\":{}}"
            })
    void invalidJsonAndMissingFieldsAre400WithoutEcho(String body) throws Exception {
        var response = post(body);
        assertEquals(400, response.statusCode());
        assertFalse(response.body().contains(proof));
        unchanged();
    }

    @Test
    void missingAgreementBooleanHasFieldValidationDetail() throws Exception {
        var response =
                post(
                        "{\"signupToken\":\""
                                + proof
                                + "\",\"agreements\":{\"age\":true,\"service\":true,\"privacy\":true}}");
        assertEquals(400, response.statusCode());
        assertEquals("COMMON-0002", json(response).path("code").asString());
        assertTrue(response.body().contains("agreements.marketing"));
        assertFalse(response.body().contains(proof));
        unchanged();
    }

    @Test
    void invalidProofErrorIsSanitizedAndDoesNotEchoToken() throws Exception {
        var foreign = "gs_" + tokens.generate();
        var response = post(body().replace(proof, foreign));
        assertEquals(401, response.statusCode());
        assertEquals("AUTH-0029", json(response).path("code").asString());
        assertFalse(response.body().contains(foreign));
        unchanged();
    }

    @Test
    void storageFailureResponseDoesNotExposeSqlProfileOrProof() throws Exception {
        doThrow(new DataIntegrityViolationException("secret SQL fixture"))
                .when(auths)
                .saveAndFlush(any());
        var response = post(body());
        assertEquals(503, response.statusCode());
        assertEquals("AUTH-0030", json(response).path("code").asString());
        assertFalse(
                response.body().contains("secret SQL")
                        || response.body().contains(proof)
                        || response.body().contains(identity.email()));
        unchanged();
    }

    @Test
    void signupProofNeverAuthenticatesProtectedApi() throws Exception {
        assertEquals(
                401,
                request("GET", "/api/v1/auth/me", null, "Authorization", "Bearer " + proof)
                        .statusCode());
        assertTrue(sessions.authenticate(proof).isEmpty());
        unchanged();
    }

    @Test
    void corsAndOpenApiDescribeSignupWithCsrfAndBadRequestAnyOf() throws Exception {
        var cors =
                request(
                        "OPTIONS",
                        "/api/v1/auth/signup/google",
                        null,
                        "Origin",
                        "https://web.example.invalid",
                        "Access-Control-Request-Method",
                        "POST",
                        "Access-Control-Request-Headers",
                        "content-type,x-csrf-token");
        assertEquals(200, cors.statusCode());
        assertEquals(
                "true",
                cors.headers().firstValue("Access-Control-Allow-Credentials").orElseThrow());
        var document = json(request("GET", "/v3/api-docs", null));
        var op = document.path("paths").path("/api/v1/auth/signup/google").path("post");
        assertEquals("Google 가입 완료", op.path("summary").asString());
        assertEquals(
                "#/components/schemas/EmailLoginResponse",
                op.path("responses")
                        .path("201")
                        .path("content")
                        .path("application/json")
                        .path("schema")
                        .path("$ref")
                        .asString());
        assertEquals(
                "#/components/schemas/BadRequestProblemDetail",
                op.path("responses")
                        .path("400")
                        .path("content")
                        .path("application/problem+json")
                        .path("schema")
                        .path("$ref")
                        .asString());
        assertEquals(
                2,
                document.path("components")
                        .path("schemas")
                        .path("BadRequestProblemDetail")
                        .path("anyOf")
                        .size());
    }
}
