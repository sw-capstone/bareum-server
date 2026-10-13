package com.bareum.server.domain.auth.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bareum.server.domain.auth.config.LoginProperties;
import com.bareum.server.domain.auth.config.TokenRenewProperties;
import com.bareum.server.domain.auth.dto.request.EmailLoginRequest;
import com.bareum.server.domain.auth.service.*;
import com.bareum.server.domain.member.entity.Member;
import com.bareum.server.domain.member.repository.MemberRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "auth.login.allowed-origins=https://web.example.invalid",
            "auth.token-renew.max-requests-per-window=2",
            "auth.token-renew.request-window-seconds=60"
        })
class TokenRenewIntegrationTests {
    private static final Instant NOW = Instant.parse("2026-10-11T03:00:00Z");
    private static final String PASSWORD = String.join("", "Fixture", "Input", "!");
    private static String storedHash;
    private final List<Long> ids = new ArrayList<>();
    private final List<String> emailKeys = new ArrayList<>();
    private final HttpClient client = HttpClient.newHttpClient();
    @LocalServerPort private int port;
    @Autowired private MemberRepository members;
    @Autowired private PasswordHashService passwords;
    @Autowired private EmailLoginService login;
    @Autowired private LoginSessionService sessions;
    @Autowired private LoginTokenGenerator tokens;
    @Autowired private TokenRenewService renewal;
    @Autowired private LoginProperties loginPolicy;
    @Autowired private TokenRenewProperties renewPolicy;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper mapper;

    @MockitoBean(name = "loginClock")
    private Clock clock;

    private Member member;
    private String email;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        if (storedHash == null) storedHash = passwords.hash(PASSWORD);
        email = "Renew-" + UUID.randomUUID() + "@example.invalid";
        member = member(email);
    }

    private Member member(String address) {
        var result = members.saveAndFlush(Member.createLocal(address, "사용자", storedHash));
        ids.add(result.getId());
        emailKeys.add(
                tokens.hash(
                        "login-limit",
                        jdbc.queryForObject("SELECT upper(?)", String.class, address)));
        return result;
    }

    @AfterEach
    void cleanUp() {
        for (long id : ids) {
            jdbc.update("DELETE FROM refresh_token WHERE member_id=?", id);
            jdbc.update("DELETE FROM member WHERE id=?", id);
        }
        for (String key : emailKeys)
            jdbc.update("DELETE FROM login_request_window WHERE email_key=?", key);
    }

    private LoginSessionService.IssuedLogin issue() {
        return login.login(new EmailLoginRequest(email, PASSWORD, null));
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

    private Csrf csrf() throws Exception {
        var response =
                request("GET", "/api/v1/auth/csrf", null, "Origin", "https://web.example.invalid");
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("X-CSRF-TOKEN", json(response).path("headerName").asString());
        return new Csrf(
                json(response).path("token").asString(),
                response.headers().allValues("Set-Cookie").stream()
                        .filter(c -> c.startsWith("XSRF-TOKEN="))
                        .findFirst()
                        .orElseThrow()
                        .split(";", 2)[0]);
    }

    private HttpResponse<String> renew(String refresh, Csrf csrf, String... extra)
            throws Exception {
        var headers =
                new ArrayList<>(
                        List.of(
                                "Cookie",
                                "bareum_refresh=" + refresh + "; " + csrf.cookie(),
                                "X-CSRF-TOKEN",
                                csrf.token(),
                                "Origin",
                                "https://web.example.invalid"));
        headers.addAll(List.of(extra));
        return request("POST", "/api/v1/auth/token/renew", null, headers.toArray(String[]::new));
    }

    private String refresh(HttpResponse<String> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith("bareum_refresh="))
                .findFirst()
                .orElseThrow()
                .split(";", 2)[0]
                .substring("bareum_refresh=".length());
    }

    private void problem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertTrue(
                response.headers()
                        .firstValue("Content-Type")
                        .orElse("")
                        .contains("application/problem+json"));
        assertEquals(code, json(response).path("code").asString());
        assertFalse(response.body().contains(PASSWORD));
        assertFalse(
                response.headers().allValues("Set-Cookie").stream()
                        .anyMatch(
                                c ->
                                        c.startsWith("bareum_refresh=")
                                                || c.startsWith("JSESSIONID=")));
    }

    private Instant revoked() {
        var timestamp =
                jdbc.queryForObject(
                        "SELECT revoked_at FROM refresh_token WHERE member_id=?",
                        Timestamp.class,
                        member.getId());
        return timestamp == null ? null : timestamp.toInstant();
    }

    @Test
    void successfulRenewalRotatesBothTokensStoresOnlyHashesAndPreservesAbsoluteExpiry()
            throws Exception {
        var original = issue();
        when(clock.instant()).thenReturn(NOW.plusSeconds(901));
        var response = renew(original.refreshToken(), csrf());
        assertEquals(200, response.statusCode(), response.body());
        String access = json(response).path("accessToken").asString();
        String nextRefresh = refresh(response);
        assertNotEquals(original.accessToken(), access);
        assertNotEquals(original.refreshToken(), nextRefresh);
        assertEquals(4, json(response).size());
        assertEquals(900, json(response).path("expiresIn").asLong());
        assertEquals(28800 - 901, json(response).path("sessionExpiresIn").asLong());
        assertEquals("Bearer", json(response).path("tokenType").asString());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("no-cache", response.headers().firstValue("Pragma").orElseThrow());
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(
                cookie.contains("HttpOnly")
                        && cookie.contains("Secure")
                        && cookie.contains("SameSite=None"));
        assertTrue(cookie.contains("Path=/api/v1/auth") && cookie.contains("Max-Age=27899"));
        assertFalse(cookie.contains("Domain="));
        var row = jdbc.queryForMap("SELECT * FROM refresh_token WHERE member_id=?", member.getId());
        assertEquals(tokens.hash("access", access), row.get("access_token_hash"));
        assertEquals(tokens.hash("refresh", nextRefresh), row.get("refresh_token_hash"));
        assertEquals(original.sessionExpiresAt(), ((Timestamp) row.get("expires_at")).toInstant());
        assertEquals(NOW, ((Timestamp) row.get("created_at")).toInstant());
        assertFalse(
                row.values().toString().contains(access)
                        || row.values().toString().contains(nextRefresh));
        assertTrue(sessions.authenticate(original.accessToken()).isEmpty());
        assertTrue(sessions.authenticate(access).isPresent());
        var used =
                jdbc.queryForMap(
                        "SELECT * FROM used_refresh_token WHERE session_id=?", row.get("id"));
        assertEquals(tokens.hash("refresh", original.refreshToken()), used.get("token_hash"));
        assertEquals(row.get("jti"), used.get("family_id"));
        assertFalse(used.values().toString().contains(original.refreshToken()));
        assertEquals(
                200,
                request("GET", "/api/v1/auth/me", null, "Authorization", "Bearer " + access)
                        .statusCode());
    }

    @Test
    void csrfBootstrapIsPublicHttpOnlyMaskedAndDoesNotCreateServerSession() throws Exception {
        var response = request("GET", "/api/v1/auth/csrf", null, "Authorization", "Bearer expired");
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(2, json(response).size());
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(
                cookie.startsWith("XSRF-TOKEN=")
                        && cookie.contains("HttpOnly")
                        && cookie.contains("Secure"));
        assertTrue(cookie.contains("SameSite=None") && cookie.contains("Path=/api/v1/auth"));
        assertFalse(cookie.contains("JSESSIONID") || cookie.contains("Domain="));
        assertNotEquals(
                cookie.split(";", 2)[0].substring("XSRF-TOKEN=".length()),
                json(response).path("token").asString());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }

    @Test
    void expiredAccessHeaderDoesNotBlockCookieBasedRenewal() throws Exception {
        var original = issue();
        when(clock.instant()).thenReturn(NOW.plusSeconds(1000));
        assertEquals(
                200,
                renew(
                                original.refreshToken(),
                                csrf(),
                                "Authorization",
                                "Bearer " + original.accessToken())
                        .statusCode());
    }

    @Test
    void renewalSucceedsWithoutAuthorizationHeader() throws Exception {
        assertEquals(200, renew(issue().refreshToken(), csrf()).statusCode());
    }

    @Test
    void accessExpiryIsCappedByTheOriginalEightHourBoundary() throws Exception {
        var original = issue();
        when(clock.instant()).thenReturn(original.sessionExpiresAt().minusSeconds(30));
        var response = renew(original.refreshToken(), csrf());
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(30, json(response).path("expiresIn").asLong());
        assertEquals(30, json(response).path("sessionExpiresIn").asLong());
        assertTrue(
                response.headers().firstValue("Set-Cookie").orElseThrow().contains("Max-Age=30"));
        when(clock.instant()).thenReturn(original.sessionExpiresAt());
        assertTrue(sessions.authenticate(json(response).path("accessToken").asString()).isEmpty());
    }

    @Test
    void exactMaximumExpiryCannotBeRenewed() throws Exception {
        var original = issue();
        when(clock.instant()).thenReturn(original.sessionExpiresAt());
        problem(renew(original.refreshToken(), csrf()), 401, "AUTH-0021");
    }

    @Test
    void reusedRefreshCommitsSessionRevocationEvenWithAnErrorResponse() throws Exception {
        var original = issue();
        var protection = csrf();
        var first = renew(original.refreshToken(), protection);
        assertEquals(200, first.statusCode());
        problem(renew(original.refreshToken(), protection), 401, "AUTH-0022");
        assertEquals(NOW, revoked());
        assertTrue(sessions.authenticate(json(first).path("accessToken").asString()).isEmpty());
        problem(renew(refresh(first), protection), 401, "AUTH-0021");
    }

    @Test
    void twoConcurrentRequestsIssueOnceAndReuseRevokesTheAffectedSession() throws Exception {
        var original = issue();
        var protection = csrf();
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<HttpResponse<String>> work = () -> renew(original.refreshToken(), protection);
            var results = pool.invokeAll(List.of(work, work));
            var statuses = new ArrayList<Integer>();
            for (var result : results) statuses.add(result.get().statusCode());
            statuses.sort(Integer::compareTo);
            assertEquals(List.of(200, 401), statuses);
        }
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM used_refresh_token WHERE session_id=(SELECT id FROM"
                            + " refresh_token WHERE member_id=?)",
                        Integer.class,
                        member.getId()));
        assertNotNull(revoked());
    }

    @Test
    void concurrentLoginAndRenewalDoNotInvalidateTheSeparateNewLogin() throws Exception {
        var original = issue();
        var protection = csrf();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var renewalFuture = pool.submit(() -> renew(original.refreshToken(), protection));
            var loginFuture = pool.submit(this::issue);
            var renewed = renewalFuture.get();
            var fresh = loginFuture.get();
            assertTrue(renewed.statusCode() == 200 || renewed.statusCode() == 401, renewed.body());
            assertTrue(sessions.authenticate(fresh.accessToken()).isPresent());
            assertNull(revoked());
            if (renewed.statusCode() == 200)
                assertTrue(
                        sessions.authenticate(json(renewed).path("accessToken").asString())
                                .isPresent());
        }
    }

    @Test
    void oldFamilyReuseCannotRevokeASeparateNewLogin() throws Exception {
        var old = issue();
        var protection = csrf();
        var first = renew(old.refreshToken(), protection);
        assertEquals(200, first.statusCode());
        var fresh = issue();
        problem(renew(old.refreshToken(), protection), 401, "AUTH-0021");
        problem(renew(refresh(first), protection), 401, "AUTH-0021");
        assertNull(revoked());
        assertTrue(sessions.authenticate(fresh.accessToken()).isPresent());
        assertEquals(200, renew(fresh.refreshToken(), protection).statusCode());
    }

    @Test
    void unusedOldRefreshAfterReloginIsRejectedWithoutRevokingNewLogin() throws Exception {
        var old = issue();
        var fresh = issue();
        problem(renew(old.refreshToken(), csrf()), 401, "AUTH-0021");
        assertNull(revoked());
        assertTrue(sessions.authenticate(fresh.accessToken()).isPresent());
    }

    @Test
    void nonexistentRefreshCannotRevokeKnownSession() throws Exception {
        var original = issue();
        problem(renew(tokens.generate(), csrf()), 401, "AUTH-0021");
        assertNull(revoked());
        assertTrue(sessions.authenticate(original.accessToken()).isPresent());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"short", "invalid token", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA!"})
    void malformedRefreshIsRejected(String refresh) throws Exception {
        issue();
        problem(renew(refresh, csrf()), 401, "AUTH-0021");
        assertNull(revoked());
    }

    @Test
    void accessTokenCannotBeUsedAsRefreshToken() throws Exception {
        var original = issue();
        problem(renew(original.accessToken(), csrf()), 401, "AUTH-0021");
        assertTrue(sessions.authenticate(original.accessToken()).isPresent());
    }

    @Test
    void missingRefreshCookieIsRejectedAfterValidCsrf() throws Exception {
        var protection = csrf();
        problem(
                request(
                        "POST",
                        "/api/v1/auth/token/renew",
                        null,
                        "Cookie",
                        protection.cookie(),
                        "X-CSRF-TOKEN",
                        protection.token()),
                401,
                "AUTH-0021");
    }

    @Test
    void duplicateRefreshCookiesAreRejected() throws Exception {
        var original = issue();
        var protection = csrf();
        problem(
                request(
                        "POST",
                        "/api/v1/auth/token/renew",
                        null,
                        "Cookie",
                        "bareum_refresh="
                                + original.refreshToken()
                                + "; bareum_refresh="
                                + original.refreshToken()
                                + "; "
                                + protection.cookie(),
                        "X-CSRF-TOKEN",
                        protection.token()),
                401,
                "AUTH-0021");
    }

    @Test
    void requestBodyAndAuthorizationDoNotSubstituteForRefreshCookie() throws Exception {
        var original = issue();
        var protection = csrf();
        problem(
                request(
                        "POST",
                        "/api/v1/auth/token/renew",
                        "{\"refreshToken\":\"" + original.refreshToken() + "\"}",
                        "Cookie",
                        protection.cookie(),
                        "X-CSRF-TOKEN",
                        protection.token(),
                        "Authorization",
                        "Bearer " + original.accessToken(),
                        "Content-Type",
                        "application/json"),
                401,
                "AUTH-0021");
    }

    @Test
    void missingCsrfCannotRotateTokensEvenWithValidAccessAndRefresh() throws Exception {
        var original = issue();
        problem(
                request(
                        "POST",
                        "/api/v1/auth/token/renew",
                        null,
                        "Cookie",
                        "bareum_refresh=" + original.refreshToken(),
                        "Authorization",
                        "Bearer " + original.accessToken()),
                403,
                "AUTH-0018");
        assertTrue(sessions.authenticate(original.accessToken()).isPresent());
    }

    @Test
    void aCsrfTokenForAnotherCookieIsRejected() throws Exception {
        var original = issue();
        var first = csrf();
        var other = csrf();
        problem(
                renew(original.refreshToken(), new Csrf(other.token(), first.cookie())),
                403,
                "AUTH-0018");
    }

    @Test
    void rawCookieCsrfValueIsNotAcceptedAsMaskedHeader() throws Exception {
        var original = issue();
        var protection = csrf();
        problem(
                renew(
                        original.refreshToken(),
                        new Csrf(
                                protection.cookie().substring("XSRF-TOKEN=".length()),
                                protection.cookie())),
                403,
                "AUTH-0018");
    }

    @Test
    void csrfRequestParameterCannotSubstituteForExplicitHeader() throws Exception {
        var original = issue();
        var protection = csrf();
        problem(
                request(
                        "POST",
                        "/api/v1/auth/token/renew",
                        "_csrf=" + protection.token(),
                        "Cookie",
                        "bareum_refresh=" + original.refreshToken() + "; " + protection.cookie(),
                        "Content-Type",
                        "application/x-www-form-urlencoded"),
                403,
                "AUTH-0018");
    }

    @Test
    void duplicateCsrfHeadersAreRejected() throws Exception {
        var original = issue();
        var protection = csrf();
        problem(
                renew(original.refreshToken(), protection, "X-CSRF-TOKEN", protection.token()),
                403,
                "AUTH-0018");
    }

    @Test
    void unauthorizedOriginCannotRotateTokens() throws Exception {
        var original = issue();
        var protection = csrf();
        var response =
                request(
                        "POST",
                        "/api/v1/auth/token/renew",
                        null,
                        "Cookie",
                        "bareum_refresh=" + original.refreshToken() + "; " + protection.cookie(),
                        "X-CSRF-TOKEN",
                        protection.token(),
                        "Origin",
                        "https://evil.example.invalid");
        assertEquals(403, response.statusCode());
        assertFalse(response.headers().firstValue("Access-Control-Allow-Origin").isPresent());
        assertTrue(sessions.authenticate(original.accessToken()).isPresent());
    }

    @Test
    void corsPreflightAllowsExplicitCsrfHeaderAndCredentials() throws Exception {
        var response =
                request(
                        "OPTIONS",
                        "/api/v1/auth/token/renew",
                        null,
                        "Origin",
                        "https://web.example.invalid",
                        "Access-Control-Request-Method",
                        "POST",
                        "Access-Control-Request-Headers",
                        "X-CSRF-TOKEN");
        assertEquals(200, response.statusCode());
        assertEquals(
                "https://web.example.invalid",
                response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertEquals(
                "true",
                response.headers().firstValue("Access-Control-Allow-Credentials").orElseThrow());
        assertTrue(
                response.headers()
                        .firstValue("Access-Control-Allow-Headers")
                        .orElseThrow()
                        .toLowerCase()
                        .contains("x-csrf-token"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUSPENDED", "DELETED"})
    void limitedAccountCannotRenew(String status) throws Exception {
        var original = issue();
        jdbc.update("UPDATE member SET status=? WHERE id=?", status, member.getId());
        problem(renew(original.refreshToken(), csrf()), 403, "AUTH-0016");
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM used_refresh_token WHERE session_id=(SELECT id FROM"
                            + " refresh_token WHERE member_id=?)",
                        Integer.class,
                        member.getId()));
    }

    @Test
    void withdrawnTimestampCannotRenewEvenWhenStatusRemainsActive() throws Exception {
        var original = issue();
        jdbc.update(
                "UPDATE member SET withdrawn_at=? WHERE id=?", Timestamp.from(NOW), member.getId());
        problem(renew(original.refreshToken(), csrf()), 403, "AUTH-0016");
    }

    @Test
    void revokedSessionCannotRenew() throws Exception {
        var original = issue();
        jdbc.update(
                "UPDATE refresh_token SET revoked_at=? WHERE member_id=?",
                Timestamp.from(NOW),
                member.getId());
        problem(renew(original.refreshToken(), csrf()), 401, "AUTH-0021");
    }

    @Test
    void successfulRenewalsAreLimitedAndWindowBoundaryResetsWithoutExtendingSession()
            throws Exception {
        var original = issue();
        var protection = csrf();
        var first = renew(original.refreshToken(), protection);
        var second = renew(refresh(first), protection);
        assertEquals(200, second.statusCode());
        problem(renew(refresh(second), protection), 429, "AUTH-0023");
        assertTrue(sessions.authenticate(json(second).path("accessToken").asString()).isPresent());
        assertNull(revoked());
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        var third = renew(refresh(second), protection);
        assertEquals(200, third.statusCode());
        assertEquals(28800 - 60, json(third).path("sessionExpiresIn").asLong());
    }

    @Test
    void renewalFailureRollsBackConsumedHashCounterAndPreviousTokens() {
        var original = issue();
        var other = member("Other-" + UUID.randomUUID() + "@example.invalid");
        var existing = login.login(new EmailLoginRequest(other.getEmail(), PASSWORD, null));
        var generator = mock(LoginTokenGenerator.class);
        when(generator.validFormat(original.refreshToken())).thenReturn(true);
        when(generator.hash("refresh", original.refreshToken()))
                .thenReturn(tokens.hash("refresh", original.refreshToken()));
        var collisionJwt = mock(com.bareum.server.domain.auth.service.LoginJwtService.class);
        var tokenJwt = new com.bareum.server.domain.auth.service.LoginJwtService(
                new com.bareum.server.domain.auth.config.LoginJwtProperties(
                        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="), clock);
        var verified = tokenJwt.verify(original.refreshToken(), "refresh");
        when(collisionJwt.verify(original.refreshToken(), "refresh")).thenReturn(verified);
        when(collisionJwt.issue(eq("access"), anyLong(), any(), any(), any()))
                .thenReturn(existing.accessToken());
        when(collisionJwt.issue(eq("refresh"), anyLong(), any(), any(), any()))
                .thenReturn(tokens.generate());
        when(generator.hash(eq("access"), anyString()))
                .thenAnswer(i -> tokens.hash("access", i.getArgument(1)));
        when(generator.hash(eq("refresh"), anyString()))
                .thenAnswer(i -> tokens.hash("refresh", i.getArgument(1)));
        var isolated =
                new TokenRenewService(
                        jdbc,
                        generator,
                        collisionJwt,
                        loginPolicy,
                        renewPolicy,
                        Clock.fixed(NOW, java.time.ZoneOffset.UTC));
        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () ->
                        new TransactionTemplate(transactions)
                                .execute(status -> isolated.renew(original.refreshToken())));
        assertTrue(sessions.authenticate(original.accessToken()).isPresent());
        assertTrue(sessions.authenticate(existing.accessToken()).isPresent());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM used_refresh_token WHERE token_hash=?",
                        Integer.class,
                        tokens.hash("refresh", original.refreshToken())));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM token_renew_window WHERE session_id=(SELECT id FROM"
                            + " refresh_token WHERE member_id=?)",
                        Integer.class,
                        member.getId()));
    }

    @Test
    void expiredUsedTokensAndInactiveCountersArePurged() throws Exception {
        var original = issue();
        assertEquals(200, renew(original.refreshToken(), csrf()).statusCode());
        when(clock.instant()).thenReturn(NOW.plusSeconds(90000));
        renewal.purgeExpiredTokens();
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM used_refresh_token WHERE token_hash=?",
                        Integer.class,
                        tokens.hash("refresh", original.refreshToken())));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM token_renew_window WHERE session_id=(SELECT id FROM"
                            + " refresh_token WHERE member_id=?)",
                        Integer.class,
                        member.getId()));
    }

    @Test
    void openApiDocumentsCookieHeaderResponseAndCsrfBootstrap() throws Exception {
        var document = json(request("GET", "/v3/api-docs", null));
        var operation = document.path("paths").path("/api/v1/auth/token/renew").path("post");
        assertFalse(operation.isMissingNode());
        assertTrue(operation.path("description").asString().contains("8시간"));
        assertEquals(2, operation.path("parameters").size());
        assertTrue(operation.path("parameters").toString().contains("X-CSRF-TOKEN"));
        assertTrue(operation.path("parameters").toString().contains("bareum_refresh"));
        assertFalse(operation.has("requestBody"));
        for (String status : List.of("200", "401", "403", "429", "503"))
            assertTrue(operation.path("responses").has(status));
        var bootstrap = document.path("paths").path("/api/v1/auth/csrf").path("get");
        assertFalse(bootstrap.isMissingNode());
        assertFalse(bootstrap.has("parameters"));
    }

    private record Csrf(String token, String cookie) {
        @Override
        public String toString() {
            return "Csrf[redacted]";
        }
    }
}
