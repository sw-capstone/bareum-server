package com.bareum.server.domain.auth.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "auth.login.allowed-origins=https://web.example.invalid")
class LogoutIntegrationTests {
    private static final Instant NOW = Instant.parse("2026-10-11T03:00:00Z");
    private static final String PASSWORD = String.join("", "Fixture", "Input", "!");
    private static String storedHash;
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<Long> ids = new ArrayList<>();
    private final List<String> addresses = new ArrayList<>();
    @LocalServerPort private int port;
    @Autowired private MemberRepository members;
    @Autowired private PasswordHashService passwords;
    @Autowired private EmailLoginService login;
    @Autowired private LoginSessionService sessions;
    @Autowired private LoginTokenGenerator tokens;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper mapper;
    @MockitoBean(name = "loginClock") private Clock clock;
    private Member member;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        if (storedHash == null) storedHash = passwords.hash(PASSWORD);
        member = member();
    }

    private Member member() {
        String email = "Logout-" + UUID.randomUUID() + "@example.invalid";
        var result = members.saveAndFlush(Member.createLocal(email, "사용자", storedHash));
        ids.add(result.getId());
        addresses.add(email);
        return result;
    }

    @AfterEach
    void cleanUp() {
        for (long id : ids) {
            jdbc.update("DELETE FROM refresh_token WHERE member_id=?", id);
            jdbc.update("DELETE FROM member WHERE id=?", id);
        }
        for (String email : addresses) jdbc.update("DELETE FROM login_request_window WHERE email_key=?",
                tokens.hash("login-limit", jdbc.queryForObject("SELECT upper(?)", String.class, email)));
    }

    private LoginSessionService.IssuedLogin issue(Member account) {
        return login.login(new EmailLoginRequest(account.getEmail(), PASSWORD, null));
    }

    private LoginSessionService.IssuedLogin issue() { return issue(member); }

    private HttpResponse<String> request(String method, String path, String body, String... headers)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        for (int i = 0; i < headers.length; i += 2) builder.header(headers[i], headers[i + 1]);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) { return mapper.readTree(response.body()); }

    private Csrf csrf() throws Exception {
        var response = request("GET", "/api/v1/auth/csrf", null);
        assertEquals(200, response.statusCode());
        return new Csrf(json(response).path("token").asString(),
                response.headers().allValues("Set-Cookie").stream()
                        .filter(c -> c.startsWith("XSRF-TOKEN="))
                        .findFirst().orElseThrow().split(";", 2)[0]);
    }

    private HttpResponse<String> call(String path, String refresh, Csrf protection, String... extra)
            throws Exception {
        String cookie = protection.cookie();
        if (refresh != null) cookie += "; bareum_refresh=" + refresh;
        var headers = new ArrayList<>(List.of("Cookie", cookie, "X-CSRF-TOKEN", protection.token(),
                "Origin", "https://web.example.invalid"));
        headers.addAll(List.of(extra));
        return request("POST", path, null, headers.toArray(String[]::new));
    }

    private HttpResponse<String> logout(String refresh, Csrf protection, String... extra) throws Exception {
        return call("/api/v1/auth/logout", refresh, protection, extra);
    }

    private String refresh(HttpResponse<String> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith("bareum_refresh="))
                .findFirst().orElseThrow().split(";", 2)[0].substring("bareum_refresh=".length());
    }

    private Timestamp revoked(Member account) {
        return jdbc.queryForObject("SELECT revoked_at FROM refresh_token WHERE member_id=?",
                Timestamp.class, account.getId());
    }

    private void success(HttpResponse<String> response) {
        assertEquals(204, response.statusCode(), response.body());
        assertEquals("", response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("no-cache", response.headers().firstValue("Pragma").orElseThrow());
        var cookies = response.headers().allValues("Set-Cookie");
        for (String name : List.of("bareum_refresh", "XSRF-TOKEN")) {
            String value = cookies.stream().filter(c -> c.startsWith(name + "=;")).findFirst().orElseThrow();
            assertTrue(value.contains("Max-Age=0") && value.contains("Path=/api/v1/auth"));
            assertTrue(value.contains("Secure") && value.contains("HttpOnly") && value.contains("SameSite=None"));
            assertFalse(value.contains("Domain="));
        }
        assertFalse(cookies.stream().anyMatch(c -> c.startsWith("JSESSIONID=")));
    }

    private void problem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/problem+json"));
        assertEquals(code, json(response).path("code").asString());
        assertFalse(response.headers().allValues("Set-Cookie").stream()
                .anyMatch(c -> c.startsWith("bareum_refresh=") || c.contains("Max-Age=0")));
    }

    @Test
    void successfulLogoutRevokesAccessAndRefreshAndExpiresBothCookies() throws Exception {
        var issued = issue();
        var protection = csrf();
        success(logout(issued.refreshToken(), protection));
        assertEquals(NOW, revoked(member).toInstant());
        assertTrue(sessions.authenticate(issued.accessToken()).isEmpty());
        problem(call("/api/v1/auth/token/renew", issued.refreshToken(), protection), 401, "AUTH-0021");
        problem(request("GET", "/api/v1/auth/me", null, "Authorization", "Bearer " + issued.accessToken()),
                401, "AUTH-0017");
    }

    @Test
    void expiredAccessHeaderDoesNotPreventLogout() throws Exception {
        var issued = issue();
        when(clock.instant()).thenReturn(NOW.plusSeconds(901));
        success(logout(issued.refreshToken(), csrf(), "Authorization", "Bearer " + issued.accessToken()));
        assertNotNull(revoked(member));
    }

    @Test
    void repeatedLogoutPreservesTheOriginalRevocationTimestamp() throws Exception {
        var issued = issue();
        var protection = csrf();
        success(logout(issued.refreshToken(), protection));
        when(clock.instant()).thenReturn(NOW.plusSeconds(1));
        success(logout(issued.refreshToken(), protection));
        assertEquals(NOW, revoked(member).toInstant());
        success(logout(null, protection));
    }

    @Test
    void absentCookieAndAccessOrBodyTokenCannotRevokeASession() throws Exception {
        var issued = issue();
        var protection = csrf();
        success(request("POST", "/api/v1/auth/logout", "{\"refreshToken\":\"" + issued.refreshToken() + "\"}",
                "Cookie", protection.cookie(), "X-CSRF-TOKEN", protection.token(),
                "Content-Type", "application/json", "Authorization", "Bearer " + issued.accessToken()));
        assertNull(revoked(member));
        assertTrue(sessions.authenticate(issued.accessToken()).isPresent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "invalid"})
    void malformedCookieOnlyClearsClientCookies(String value) throws Exception {
        var issued = issue();
        success(logout(value, csrf()));
        assertTrue(sessions.authenticate(issued.accessToken()).isPresent());
        assertNull(revoked(member));
    }

    @Test
    void randomUnknownCookieDoesNotRevokeTheAuthorizationSession() throws Exception {
        var issued = issue();
        success(logout(tokens.generate(), csrf(), "Authorization", "Bearer " + issued.accessToken()));
        assertTrue(sessions.authenticate(issued.accessToken()).isPresent());
    }

    @Test
    void exactSessionExpiryStillRevokesKnownStoredSession() throws Exception {
        var issued = issue();
        when(clock.instant()).thenReturn(issued.sessionExpiresAt());
        success(logout(issued.refreshToken(), csrf()));
        assertEquals(issued.sessionExpiresAt(), revoked(member).toInstant());
    }

    @Test
    void missingCsrfCannotLogoutEvenWithValidAccessAndRefresh() throws Exception {
        var issued = issue();
        problem(request("POST", "/api/v1/auth/logout", null,
                "Cookie", "bareum_refresh=" + issued.refreshToken(),
                "Authorization", "Bearer " + issued.accessToken()), 403, "AUTH-0018");
        assertNull(revoked(member));
        assertTrue(sessions.authenticate(issued.accessToken()).isPresent());
    }

    @Test
    void mismatchedCsrfIsRejected() throws Exception {
        var issued = issue();
        var first = csrf();
        var second = csrf();
        problem(logout(issued.refreshToken(), new Csrf(second.token(), first.cookie())), 403, "AUTH-0018");
        assertNull(revoked(member));
    }

    @Test
    void rawCsrfCookieValueAndDuplicateHeadersAreRejected() throws Exception {
        var issued = issue();
        var protection = csrf();
        problem(logout(issued.refreshToken(), new Csrf(protection.cookie().substring("XSRF-TOKEN=".length()),
                protection.cookie())), 403, "AUTH-0018");
        problem(logout(issued.refreshToken(), protection, "X-CSRF-TOKEN", protection.token()), 403, "AUTH-0018");
        assertNull(revoked(member));
    }

    @Test
    void csrfFormParameterDoesNotSubstituteForHeader() throws Exception {
        var issued = issue();
        var protection = csrf();
        problem(request("POST", "/api/v1/auth/logout", "_csrf=" + protection.token(),
                "Cookie", protection.cookie() + "; bareum_refresh=" + issued.refreshToken(),
                "Content-Type", "application/x-www-form-urlencoded"), 403, "AUTH-0018");
        assertNull(revoked(member));
    }

    @Test
    void duplicateRefreshCookiesAreRejectedWithoutRevocation() throws Exception {
        var issued = issue();
        var protection = csrf();
        problem(request("POST", "/api/v1/auth/logout", null,
                "Cookie", protection.cookie() + "; bareum_refresh=" + issued.refreshToken()
                        + "; bareum_refresh=" + issued.refreshToken(),
                "X-CSRF-TOKEN", protection.token()), 401, "AUTH-0021");
        assertNull(revoked(member));
    }

    @Test
    void untrustedOriginCannotRevokeSession() throws Exception {
        var issued = issue();
        var protection = csrf();
        var response = request("POST", "/api/v1/auth/logout", null,
                "Cookie", protection.cookie() + "; bareum_refresh=" + issued.refreshToken(),
                "X-CSRF-TOKEN", protection.token(), "Origin", "https://evil.example.invalid");
        assertEquals(403, response.statusCode());
        assertFalse(response.headers().firstValue("Access-Control-Allow-Origin").isPresent());
        assertNull(revoked(member));
    }

    @Test
    void trustedCorsPreflightAllowsCredentialedLogout() throws Exception {
        var response = request("OPTIONS", "/api/v1/auth/logout", null,
                "Origin", "https://web.example.invalid", "Access-Control-Request-Method", "POST",
                "Access-Control-Request-Headers", "X-CSRF-TOKEN");
        assertEquals(200, response.statusCode());
        assertEquals("true", response.headers().firstValue("Access-Control-Allow-Credentials").orElseThrow());
        assertEquals("https://web.example.invalid", response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUSPENDED", "DELETED"})
    void restrictedAccountCanStillLogout(String status) throws Exception {
        var issued = issue();
        jdbc.update("UPDATE member SET status=? WHERE id=?", status, member.getId());
        success(logout(issued.refreshToken(), csrf()));
        assertNotNull(revoked(member));
    }

    @Test
    void googleAccountUsesTheSameRevocationPathWithoutPasswordValidation() throws Exception {
        var issued = issue();
        jdbc.update("UPDATE member SET signup_method='GOOGLE',password_hash=NULL WHERE id=?", member.getId());
        success(logout(issued.refreshToken(), csrf()));
        assertNotNull(revoked(member));
        assertTrue(sessions.authenticate(issued.accessToken()).isEmpty());
    }

    @Test
    void withdrawnAccountCanStillLogout() throws Exception {
        var issued = issue();
        jdbc.update("UPDATE member SET withdrawn_at=? WHERE id=?", Timestamp.from(NOW), member.getId());
        success(logout(issued.refreshToken(), csrf()));
        assertNotNull(revoked(member));
    }

    @Test
    void logoutUsingConsumedCookieRevokesTheSameFamilyAfterRenewal() throws Exception {
        var issued = issue();
        var protection = csrf();
        var renewed = call("/api/v1/auth/token/renew", issued.refreshToken(), protection);
        assertEquals(200, renewed.statusCode());
        success(logout(issued.refreshToken(), protection));
        assertTrue(sessions.authenticate(json(renewed).path("accessToken").asString()).isEmpty());
        problem(call("/api/v1/auth/token/renew", refresh(renewed), protection), 401, "AUTH-0021");
    }

    @Test
    void previousFamilyCurrentAndConsumedCookiesCannotRevokeANewLogin() throws Exception {
        var issued = issue();
        var protection = csrf();
        var renewed = call("/api/v1/auth/token/renew", issued.refreshToken(), protection);
        var fresh = issue();
        success(logout(issued.refreshToken(), protection));
        success(logout(refresh(renewed), protection));
        assertNull(revoked(member));
        assertTrue(sessions.authenticate(fresh.accessToken()).isPresent());
    }

    @Test
    void logoutDoesNotAffectAnotherMember() throws Exception {
        var issued = issue();
        var other = issue(member());
        success(logout(issued.refreshToken(), csrf()));
        assertTrue(sessions.authenticate(other.accessToken()).isPresent());
    }

    @Test
    void concurrentRenewalAndLogoutAlwaysLeaveTheTargetFamilyRevoked() throws Exception {
        var issued = issue();
        var protection = csrf();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var renewing = pool.submit(() -> call("/api/v1/auth/token/renew", issued.refreshToken(), protection));
            var loggingOut = pool.submit(() -> logout(issued.refreshToken(), protection));
            var renewed = renewing.get();
            success(loggingOut.get());
            assertTrue(renewed.statusCode() == 200 || renewed.statusCode() == 401);
            assertNotNull(revoked(member));
            assertTrue(sessions.authenticate(issued.accessToken()).isEmpty());
            if (renewed.statusCode() == 200) {
                assertTrue(sessions.authenticate(json(renewed).path("accessToken").asString()).isEmpty());
                problem(call("/api/v1/auth/token/renew", refresh(renewed), protection), 401, "AUTH-0021");
            }
        }
    }

    @Test
    void concurrentReloginAndOldCookieLogoutPreserveTheNewFamily() throws Exception {
        var issued = issue();
        var protection = csrf();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var loggingOut = pool.submit(() -> logout(issued.refreshToken(), protection));
            var loggingIn = pool.submit((Callable<LoginSessionService.IssuedLogin>) this::issue);
            success(loggingOut.get());
            var fresh = loggingIn.get();
            assertTrue(sessions.authenticate(fresh.accessToken()).isPresent());
            assertNull(revoked(member));
        }
    }

    @Test
    void concurrentDuplicateLogoutIsIdempotent() throws Exception {
        var issued = issue();
        var protection = csrf();
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<HttpResponse<String>> work = () -> logout(issued.refreshToken(), protection);
            for (var result : pool.invokeAll(List.of(work, work))) success(result.get());
        }
        assertEquals(NOW, revoked(member).toInstant());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void databaseOrCommitFailureRollsBackRevocationAndDoesNotDeleteCookies(boolean deferred) throws Exception {
        var issued = issue();
        String function = "reject_logout_" + member.getId();
        String trigger = "reject_logout_trigger_" + member.getId();
        try {
            jdbc.execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                    + "RAISE EXCEPTION 'private storage failure' USING ERRCODE='23514'; END $$");
            String definition = deferred
                    ? "CREATE CONSTRAINT TRIGGER " + trigger + " AFTER UPDATE ON refresh_token DEFERRABLE INITIALLY DEFERRED "
                    : "CREATE TRIGGER " + trigger + " BEFORE UPDATE OF revoked_at ON refresh_token ";
            jdbc.execute(definition
                    + "FOR EACH ROW WHEN (NEW.member_id=" + member.getId() + ") EXECUTE FUNCTION " + function + "()");
            var response = logout(issued.refreshToken(), csrf());
            problem(response, 503, "AUTH-0019");
            assertFalse(response.body().contains("private storage failure"));
            assertNull(revoked(member));
            assertTrue(sessions.authenticate(issued.accessToken()).isPresent());
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + trigger + " ON refresh_token");
            jdbc.execute("DROP FUNCTION IF EXISTS " + function + "()");
        }
    }

    @Test
    void openApiDocumentsNoBodySuccessCookiesCsrfAndErrors() throws Exception {
        var document = json(request("GET", "/v3/api-docs", null));
        var operation = document.path("paths").path("/api/v1/auth/logout").path("post");
        assertFalse(operation.isMissingNode());
        assertFalse(operation.has("requestBody"));
        assertEquals(2, operation.path("parameters").size());
        assertTrue(operation.path("parameters").toString().contains("bareum_refresh"));
        assertTrue(operation.path("parameters").toString().contains("X-CSRF-TOKEN"));
        for (String code : List.of("204", "401", "403", "503")) assertTrue(operation.path("responses").has(code));
        assertFalse(operation.path("responses").path("204").has("content"));
    }

    private record Csrf(String token, String cookie) {
        @Override public String toString() { return "Csrf[redacted]"; }
    }
}
