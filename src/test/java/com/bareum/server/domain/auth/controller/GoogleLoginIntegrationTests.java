package com.bareum.server.domain.auth.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bareum.server.domain.auth.dto.request.GoogleLoginRequest;
import com.bareum.server.domain.auth.exception.*;
import com.bareum.server.domain.auth.service.*;
import com.bareum.server.domain.member.entity.Member;
import com.bareum.server.domain.member.repository.MemberRepository;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
            "auth.google.client-ids=test-client"
        })
class GoogleLoginIntegrationTests {
    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static final String ID_TOKEN = "signed-google-fixture";
    @MockitoBean private GoogleIdTokenVerifier verifier;

    @MockitoBean(name = "loginClock")
    private Clock clock;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private MemberRepository members;
    @Autowired private GoogleLoginService service;
    @Autowired private LoginSessionService sessions;
    @Autowired private TokenRenewService renew;
    @Autowired private LoginTokenGenerator tokens;
    @Autowired private GoogleLoginTransactionService accounts;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private JsonMapper mapper;
    @LocalServerPort private int port;
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<Long> ids = new ArrayList<>();
    private final List<String> addresses = new ArrayList<>();
    private final List<String> subjects = new ArrayList<>();
    private GoogleIdentity identity;

    @BeforeEach
    void setup() {
        when(clock.instant()).thenReturn(NOW);
        identity = identity("@gmail.com", null);
        when(verifier.verify(ID_TOKEN)).thenReturn(identity);
    }

    private GoogleIdentity identity(String suffix, String hd) {
        var result =
                new GoogleIdentity(
                        "subject-" + UUID.randomUUID(),
                        "Google-" + UUID.randomUUID() + suffix,
                        "회원",
                        hd);
        subjects.add(result.subject());
        addresses.add(result.email());
        return result;
    }

    private Member member(String email, boolean google) {
        var account =
                members.saveAndFlush(
                        google
                                ? Member.createGoogle(email, "등록 이름")
                                : Member.createLocal(email, "등록 이름", "fixture-hash"));
        ids.add(account.getId());
        return account;
    }

    private Member linked() {
        var account = member(identity.email(), true);
        jdbc.update(
                "INSERT INTO member_auth(member_id,provider,provider_user_id,provider_email)"
                        + " VALUES(?,'GOOGLE',?,?)",
                account.getId(),
                identity.subject(),
                identity.email());
        return account;
    }

    @AfterEach
    void cleanup() {
        for (String sub : subjects)
            jdbc.update("DELETE FROM google_signup_proof WHERE google_subject=?", sub);
        for (long id : ids) {
            jdbc.update("DELETE FROM member_term WHERE member_id=?", id);
            jdbc.update("DELETE FROM refresh_token WHERE member_id=?", id);
            jdbc.update("DELETE FROM member_auth WHERE member_id=?", id);
            jdbc.update("DELETE FROM member WHERE id=?", id);
        }
        for (String address : addresses)
            jdbc.update(
                    "DELETE FROM login_request_window WHERE email_key=?",
                    tokens.hash(
                            "login-limit",
                            jdbc.queryForObject("SELECT upper(?)", String.class, address)));
    }

    private HttpResponse<String> request(String method, String path, String body, String... headers)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        for (int i = 0; i < headers.length; i += 2) builder.header(headers[i], headers[i + 1]);
        builder.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) {
        return mapper.readTree(response.body());
    }

    private List<String> csrf() throws Exception {
        var response = request("GET", "/api/v1/auth/csrf", null);
        assertEquals(200, response.statusCode());
        String cookie =
                response.headers().allValues("Set-Cookie").stream()
                        .filter(c -> c.startsWith("XSRF-TOKEN="))
                        .findFirst()
                        .orElseThrow()
                        .split(";", 2)[0];
        return new ArrayList<>(
                List.of(
                        "Cookie",
                        cookie,
                        "X-CSRF-TOKEN",
                        json(response).path("token").asString(),
                        "Origin",
                        "https://web.example.invalid",
                        "Content-Type",
                        "application/json"));
    }

    private HttpResponse<String> post(String body, String... extra) throws Exception {
        var headers = csrf();
        headers.addAll(List.of(extra));
        return request("POST", "/api/v1/auth/login/google", body, headers.toArray(String[]::new));
    }

    private HttpResponse<String> login() throws Exception {
        return post("{\"idToken\":\"" + ID_TOKEN + "\"}");
    }

    private String refresh(HttpResponse<String> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith("bareum_refresh="))
                .findFirst()
                .orElseThrow()
                .split(";", 2)[0]
                .substring(15);
    }

    @Test
    void existingGoogleAccountUsesCommonSessionMeRenewAndLogout() throws Exception {
        var account = linked();
        var response = login();
        assertEquals(200, response.statusCode(), response.body());
        var data = json(response);
        assertEquals("LOGIN", data.path("status").asString());
        assertEquals(900, data.path("expiresIn").asInt());
        assertEquals(28800, data.path("sessionExpiresIn").asInt());
        assertFalse(data.has("signupToken"));
        assertFalse(data.has("refreshToken"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(
                cookie.contains("HttpOnly")
                        && cookie.contains("Secure")
                        && cookie.contains("SameSite=None"));
        String access = data.path("accessToken").asString(), refresh = refresh(response);
        assertEquals(account.getId(), sessions.authenticate(access).orElseThrow().memberId());
        var me = request("GET", "/api/v1/auth/me", null, "Authorization", "Bearer " + access);
        assertEquals(200, me.statusCode());
        assertEquals("등록 이름", json(me).path("name").asString());
        var rotated = renew.renew(refresh);
        assertTrue(sessions.authenticate(access).isEmpty());
        var protection = csrf();
        protection.set(1, protection.get(1) + "; bareum_refresh=" + rotated.refreshToken());
        var logout =
                request("POST", "/api/v1/auth/logout", null, protection.toArray(String[]::new));
        assertEquals(204, logout.statusCode());
        assertTrue(sessions.authenticate(rotated.accessToken()).isEmpty());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM member_term WHERE member_id=?",
                        Integer.class,
                        account.getId()));
    }

    @Test
    void existingSubjectRemainsIdentityWhenGoogleEmailChanges() {
        var account = linked();
        var changed = identity("@example.invalid", null);
        var sameSubject = new GoogleIdentity(identity.subject(), changed.email(), "변경된 이름", null);
        when(verifier.verify(ID_TOKEN)).thenReturn(sameSubject);
        var result = service.login(ID_TOKEN);
        assertEquals(
                account.getId(),
                sessions.authenticate(result.login().accessToken()).orElseThrow().memberId());
        assertEquals(
                identity.email(),
                jdbc.queryForObject(
                        "SELECT email FROM member WHERE id=?", String.class, account.getId()));
    }

    @Test
    void newUserReceivesOnlyHashedScopedExpiringProofAndNoAccount() throws Exception {
        var response = login();
        assertEquals(200, response.statusCode(), response.body());
        var data = json(response);
        assertEquals("SIGNUP_REQUIRED", data.path("status").asString());
        assertEquals(600, data.path("signupExpiresIn").asInt());
        assertFalse(data.has("accessToken"));
        assertTrue(
                response.headers().allValues("Set-Cookie").stream()
                        .noneMatch(c -> c.startsWith("bareum_refresh=")));
        String proof = data.path("signupToken").asString();
        assertTrue(proof.matches("gs_[A-Za-z0-9_-]{43}"));
        var row =
                jdbc.queryForMap(
                        "SELECT * FROM google_signup_proof WHERE google_subject=?",
                        identity.subject());
        assertEquals(tokens.hash("google-signup", proof), row.get("token_hash"));
        assertEquals(identity.email(), row.get("email"));
        assertNull(row.get("consumed_at"));
        assertEquals(NOW.plusSeconds(600), ((Timestamp) row.get("expires_at")).toInstant());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM member WHERE email=?",
                        Integer.class,
                        identity.email()));
        assertTrue(sessions.authenticate(proof).isEmpty());
        assertEquals(
                401,
                request("GET", "/api/v1/auth/me", null, "Authorization", "Bearer " + proof)
                        .statusCode());
        assertThrows(AuthException.class, () -> renew.renew(proof));
        assertFalse(new GoogleLoginRequest(ID_TOKEN).toString().contains(ID_TOKEN));
        assertFalse(response.body().contains(identity.subject()));
    }

    @Test
    void secondNewUserRequestReplacesEarlierProof() {
        var first = service.login(ID_TOKEN);
        var second = service.login(ID_TOKEN);
        assertNotEquals(first.signupToken(), second.signupToken());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM google_signup_proof WHERE google_subject=?",
                        Integer.class,
                        identity.subject()));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM google_signup_proof WHERE token_hash=?",
                        Integer.class,
                        tokens.hash("google-signup", first.signupToken())));
    }

    @Test
    void expiredProofsArePurgedAtBoundary() {
        service.login(ID_TOKEN);
        when(clock.instant()).thenReturn(NOW.plusSeconds(600));
        accounts.purgeExpiredProofs();
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM google_signup_proof WHERE google_subject=?",
                        Integer.class,
                        identity.subject()));
    }

    @Test
    void localEmailCollisionIsCaseInsensitiveAndNeverLinks() throws Exception {
        member(identity.email().toLowerCase(Locale.ROOT), false);
        var response = login();
        assertEquals(409, response.statusCode());
        assertEquals("AUTH-0027", json(response).path("code").asString());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM member_auth WHERE provider_user_id=?",
                        Integer.class,
                        identity.subject()));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM google_signup_proof WHERE google_subject=?",
                        Integer.class,
                        identity.subject()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"deleted-only", "withdrawn-only", "fully-withdrawn"})
    void emailCollisionIsExcludedOnlyAfterWithdrawalIsComplete(String state) {
        var account = member(identity.email().toLowerCase(Locale.ROOT), false);
        if (!state.equals("withdrawn-only"))
            jdbc.update("UPDATE member SET status='DELETED' WHERE id=?", account.getId());
        if (!state.equals("deleted-only"))
            jdbc.update(
                    "UPDATE member SET withdrawn_at=? WHERE id=?",
                    Timestamp.from(NOW),
                    account.getId());
        if (state.equals("fully-withdrawn")) {
            assertNotNull(service.login(ID_TOKEN).signupToken());
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM member_auth WHERE provider_user_id=?",
                            Integer.class,
                            identity.subject()));
        } else {
            assertSame(
                    AuthErrorCode.EMAIL_LOGIN_REQUIRED,
                    assertThrows(AuthException.class, () -> service.login(ID_TOKEN))
                            .getBaseErrorCode());
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM google_signup_proof WHERE google_subject=?",
                            Integer.class,
                            identity.subject()));
        }
    }

    @Test
    void matchingEmailDoesNotAuthenticateDifferentGoogleSubject() throws Exception {
        var account = member(identity.email(), true);
        jdbc.update(
                "INSERT INTO member_auth(member_id,provider,provider_user_id) VALUES(?,'GOOGLE',?)",
                account.getId(),
                "other-" + identity.subject());
        assertEquals(401, login().statusCode());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM refresh_token WHERE member_id=?",
                        Integer.class,
                        account.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"suspended", "deleted", "withdrawn", "unlinked", "wrong-method"})
    void restrictedOrChangedAccountCannotIssueSession(String state) throws Exception {
        var account = linked();
        switch (state) {
            case "suspended" ->
                    jdbc.update("UPDATE member SET status='SUSPENDED' WHERE id=?", account.getId());
            case "deleted" ->
                    jdbc.update("UPDATE member SET status='DELETED' WHERE id=?", account.getId());
            case "withdrawn" ->
                    jdbc.update(
                            "UPDATE member SET withdrawn_at=? WHERE id=?",
                            Timestamp.from(NOW),
                            account.getId());
            case "unlinked" ->
                    jdbc.update(
                            "UPDATE member_auth SET unlinked_at=? WHERE member_id=?",
                            Timestamp.from(NOW),
                            account.getId());
            case "wrong-method" ->
                    jdbc.update(
                            "UPDATE member SET signup_method='LOCAL' WHERE id=?", account.getId());
        }
        var response = login();
        assertEquals(403, response.statusCode());
        assertEquals("AUTH-0016", json(response).path("code").asString());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM refresh_token WHERE member_id=?",
                        Integer.class,
                        account.getId()));
    }

    @Test
    void nonAuthoritativeThirdPartyEmailRequiresAdditionalVerificationForNewUser()
            throws Exception {
        var thirdParty = identity("@example.invalid", null);
        when(verifier.verify(ID_TOKEN)).thenReturn(thirdParty);
        var response = login();
        assertEquals(403, response.statusCode());
        assertEquals("AUTH-0028", json(response).path("code").asString());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM google_signup_proof WHERE google_subject=?",
                        Integer.class,
                        thirdParty.subject()));
    }

    @Test
    void workspaceVerifiedEmailCanGetSignupProof() {
        var workspace = identity("@example.invalid", "example.invalid");
        when(verifier.verify(ID_TOKEN)).thenReturn(workspace);
        assertNotNull(service.login(ID_TOKEN).signupToken());
    }

    @Test
    void googleLoginRequiresCsrfEvenWithBearerHeader() throws Exception {
        for (var extra :
                List.of(
                        List.<String>of(),
                        List.of("Authorization", "Bearer " + tokens.generate()))) {
            var headers = new ArrayList<>(List.of("Content-Type", "application/json"));
            headers.addAll(extra);
            assertEquals(
                    403,
                    request(
                                    "POST",
                                    "/api/v1/auth/login/google",
                                    "{\"idToken\":\"" + ID_TOKEN + "\"}",
                                    headers.toArray(String[]::new))
                            .statusCode());
        }
        verifyNoInteractions(verifier);
    }

    @Test
    void csrfMismatchAndDisallowedOriginAreRejectedBeforeTokenVerification() throws Exception {
        var protection = csrf();
        protection.set(3, "wrong");
        assertEquals(
                403,
                request(
                                "POST",
                                "/api/v1/auth/login/google",
                                "{}",
                                protection.toArray(String[]::new))
                        .statusCode());
        protection = csrf();
        protection.set(5, "https://attacker.example.invalid");
        assertEquals(
                403,
                request(
                                "POST",
                                "/api/v1/auth/login/google",
                                "{}",
                                protection.toArray(String[]::new))
                        .statusCode());
        verifyNoInteractions(verifier);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"idToken\":\"\"}", "{\"idToken\":null}", "broken-json"})
    void malformedInputReturns400WithoutVerification(String body) throws Exception {
        var response = post(body);
        assertEquals(400, response.statusCode());
        verifyNoInteractions(verifier);
    }

    @Test
    void oversizedTokenIsRejectedWithoutEcho() throws Exception {
        String large = "s".repeat(8193);
        var response = post("{\"idToken\":\"" + large + "\"}");
        assertEquals(400, response.statusCode());
        assertFalse(response.body().contains(large));
        verifyNoInteractions(verifier);
    }

    @Test
    void verifierFailureDoesNotQueryOrCreateAccount() throws Exception {
        when(verifier.verify(ID_TOKEN))
                .thenThrow(new AuthException(AuthErrorCode.INVALID_GOOGLE_AUTHENTICATION));
        var response = login();
        assertEquals(401, response.statusCode());
        assertFalse(response.body().contains(ID_TOKEN));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM google_signup_proof WHERE google_subject=?",
                        Integer.class,
                        identity.subject()));
    }

    @Test
    void rollbackDoesNotLeaveSessionOrSignupProof() {
        var transaction = new TransactionTemplate(manager);
        transaction.executeWithoutResult(
                status -> {
                    assertNotNull(accounts.resolve(identity).signupToken());
                    status.setRollbackOnly();
                });
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM google_signup_proof WHERE google_subject=?",
                        Integer.class,
                        identity.subject()));
        var account = linked();
        transaction.executeWithoutResult(
                status -> {
                    assertNotNull(accounts.resolve(identity).login());
                    status.setRollbackOnly();
                });
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM refresh_token WHERE member_id=?",
                        Integer.class,
                        account.getId()));
    }

    @Test
    void concurrentLoginsKeepOneCurrentSession() throws Exception {
        linked();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures =
                    executor.invokeAll(
                            List.<Callable<GoogleLoginTransactionService.Result>>of(
                                    () -> service.login(ID_TOKEN), () -> service.login(ID_TOKEN)));
            long active = 0;
            for (var future : futures)
                if (sessions.authenticate(future.get().login().accessToken()).isPresent()) active++;
            assertEquals(1, active);
        }
    }

    @Test
    void concurrentSignupRequestsKeepOnlyOneProof() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures =
                    executor.invokeAll(
                            List.<Callable<GoogleLoginTransactionService.Result>>of(
                                    () -> service.login(ID_TOKEN), () -> service.login(ID_TOKEN)));
            assertNotEquals(futures.get(0).get().signupToken(), futures.get(1).get().signupToken());
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM google_signup_proof WHERE google_subject=?",
                            Integer.class,
                            identity.subject()));
        }
    }

    @Test
    void loginLimiterIsCommittedOnAccountConflict() {
        member(identity.email(), false);
        for (int i = 0; i < 10; i++)
            assertSame(
                    AuthErrorCode.EMAIL_LOGIN_REQUIRED,
                    assertThrows(AuthException.class, () -> service.login(ID_TOKEN))
                            .getBaseErrorCode());
        assertSame(
                AuthErrorCode.LOGIN_REQUEST_LIMIT_EXCEEDED,
                assertThrows(AuthException.class, () -> service.login(ID_TOKEN))
                        .getBaseErrorCode());
    }

    @Test
    void openApiDocumentsBothOutcomesAndErrorSchemas() throws Exception {
        var document = json(request("GET", "/v3/api-docs", null));
        var operation = document.path("paths").path("/api/v1/auth/login/google").path("post");
        assertEquals("Google 로그인", operation.path("summary").asString());
        assertTrue(
                operation
                        .path("responses")
                        .path("200")
                        .path("content")
                        .path("application/json")
                        .path("examples")
                        .has("신규 회원"));
        assertEquals(
                "#/components/schemas/BadRequestProblemDetail",
                operation
                        .path("responses")
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
