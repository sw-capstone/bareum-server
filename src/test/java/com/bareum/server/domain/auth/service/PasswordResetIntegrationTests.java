package com.bareum.server.domain.auth.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bareum.server.domain.auth.dto.request.*;
import com.bareum.server.domain.auth.entity.EmailVerification;
import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.repository.EmailVerificationRepository;
import com.bareum.server.domain.member.entity.Member;
import com.bareum.server.domain.member.repository.MemberRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "auth.login.allowed-origins=https://web.example.invalid",
        "auth.email-verification.hash-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "auth.email-verification.max-verification-attempts=3",
        "auth.email-verification.minimum-send-interval-seconds=1",
        "auth.email-verification.max-sends-per-hour=20",
        "auth.email-verification.max-sends-per-day=30",
        "auth.email-verification.password-reset-code-ttl-seconds=300",
        "auth.email-verification.password-reset-verification-ttl-seconds=600",
        "auth.email-verification.outbox.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "auth.email-verification.outbox.poll-delay-ms=3600000"
})
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class PasswordResetIntegrationTests {
    private static final Instant NOW = Instant.parse("2026-10-12T08:00:00Z");
    private static final String OLD = String.join("", "OldPassword", "123", "!");
    private static final String NEW = String.join("", "NewPassword", "456", "&");
    private final AtomicReference<Instant> time = new AtomicReference<>(NOW);
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<String> emails = new ArrayList<>();
    @LocalServerPort private int port;
    @Autowired private PasswordResetService service;
    @Autowired private MemberRepository members;
    @Autowired private EmailVerificationRepository verifications;
    @Autowired private EmailVerificationTokenGenerator tokens;
    @Autowired private EmailVerificationCodeHasher codes;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper mapper;
    @Autowired private LoginSessionService sessions;
    @Autowired private TokenRenewService renew;
    @Autowired private EmailLoginCredentialService credentials;
    @Autowired private EmailVerificationSendService send;
    @Autowired private EmailVerificationConfirmService confirm;
    @Autowired private EmailOutboxWorker worker;
    @MockitoSpyBean private PasswordHashService passwords;
    @MockitoBean(name = "emailVerificationClock") private Clock clock;
    @MockitoBean(name = "loginClock") private Clock loginClock;
    @MockitoBean private EmailVerificationMailSender mail;
    @MockitoBean private EmailVerificationCodeGenerator generator;
    private Member member;
    private String email;
    private String oldHash;

    @BeforeEach
    void setUp() {
        time.set(NOW);
        when(clock.instant()).thenAnswer(invocation -> time.get());
        when(loginClock.instant()).thenAnswer(invocation -> time.get());
        email = "Reset-" + UUID.randomUUID() + "@Example.invalid";
        member = account(email);
        oldHash = member.getPasswordHash();
    }

    private Member account(String address) {
        emails.add(address);
        Member result = members.saveAndFlush(Member.createLocal(address, "사용자", passwords.hash(OLD)));
        jdbc.update("UPDATE member SET created_at=? WHERE id=?", Timestamp.from(NOW.minusSeconds(172800)), result.getId());
        return result;
    }

    @AfterEach
    void cleanUp() {
        removeFailure();
        for (String address : emails) {
            jdbc.update("DELETE FROM used_refresh_token WHERE session_id IN (SELECT r.id FROM refresh_token r JOIN member m ON m.id=r.member_id WHERE upper(m.email)=upper(?))", address);
            jdbc.update("DELETE FROM refresh_token WHERE member_id IN (SELECT id FROM member WHERE upper(email)=upper(?))", address);
            jdbc.update("DELETE FROM member WHERE upper(email)=upper(?)", address);
            jdbc.update("DELETE FROM email_verification_send_log WHERE upper(email)=upper(?)", address);
            jdbc.update("DELETE FROM email_verification_outbox WHERE upper(email)=upper(?)", address);
            jdbc.update("DELETE FROM email_verification WHERE upper(email)=upper(?)", address);
        }
    }

    private String proof(VerificationPurpose purpose) {
        String token = tokens.generate();
        var row = EmailVerification.request(email, purpose, codes.hash(email, purpose, "012345"), NOW.plusSeconds(300));
        row.markSent(NOW);
        row.markVerified(tokens.hash(token), NOW, NOW.plusSeconds(600));
        verifications.saveAndFlush(row);
        return token;
    }

    private EmailVerification latest() {
        return verifications.findFirstByEmailIgnoreCaseAndPurposeOrderByIdDesc(email, VerificationPurpose.PASSWORD_RESET).orElseThrow();
    }

    private String passwordHash() {
        return jdbc.queryForObject("SELECT password_hash FROM member WHERE id=?", String.class, member.getId());
    }

    private LoginSessionService.IssuedLogin login(Member target) {
        return sessions.issue(new EmailLoginCredentialService.ValidatedMember(target.getId(), target.getPasswordHash()));
    }

    private HttpResponse<String> request(String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/password/reset"))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private PasswordResetRequest body(String token) { return new PasswordResetRequest(email, token, NEW); }

    @Test
    void resetChangesHashConsumesProofRevokesTargetAndPreservesOtherMember() throws Exception {
        Member other = account("other-" + UUID.randomUUID() + "@example.invalid");
        var targetLogin = login(member);
        var otherLogin = login(other);
        String token = proof(VerificationPurpose.PASSWORD_RESET);
        var response = request(mapper.writeValueAsString(new PasswordResetRequest(email.toUpperCase(Locale.ROOT), token, NEW)));
        assertEquals(204, response.statusCode());
        assertEquals("", response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().allValues("Set-Cookie").isEmpty());
        assertNotEquals(oldHash, passwordHash());
        assertTrue(passwords.matches(NEW, passwordHash()));
        assertFalse(passwords.matches(OLD, passwordHash()));
        assertEquals(NOW, latest().getConsumedAt());
        assertEquals(email, members.findById(member.getId()).orElseThrow().getEmail());
        assertTrue(sessions.authenticate(targetLogin.accessToken()).isEmpty());
        assertThrows(AuthException.class, () -> renew.renew(targetLogin.refreshToken()));
        assertTrue(sessions.authenticate(otherLogin.accessToken()).isPresent());
        assertThrows(AuthException.class, () -> credentials.validateMember(email, OLD));
        assertTrue(sessions.authenticate(sessions.issue(credentials.validateMember(email, NEW)).accessToken()).isPresent());
        assertThrows(AuthException.class, () -> service.reset(body(token)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unverified", "pending", "consumed", "wrong-token", "expired", "exact-expiry", "missing-hash", "missing-expiry", "newer-request"})
    void invalidProofCannotChangePasswordOrSession(String scenario) {
        var active = login(member);
        String token = proof(VerificationPurpose.PASSWORD_RESET);
        long id = latest().getId();
        switch (scenario) {
            case "unverified" -> jdbc.update("UPDATE email_verification SET verified_at=NULL WHERE id=?", id);
            case "pending" -> jdbc.update("UPDATE email_verification SET last_sent_at=NULL WHERE id=?", id);
            case "consumed" -> jdbc.update("UPDATE email_verification SET consumed_at=? WHERE id=?", Timestamp.from(NOW), id);
            case "wrong-token" -> token = tokens.generate();
            case "expired" -> time.set(NOW.plusSeconds(601));
            case "exact-expiry" -> time.set(NOW.plusSeconds(600));
            case "missing-hash" -> jdbc.update("UPDATE email_verification SET verification_token_hash=NULL WHERE id=?", id);
            case "missing-expiry" -> jdbc.update("UPDATE email_verification SET verification_expires_at=NULL WHERE id=?", id);
            case "newer-request" -> proof(VerificationPurpose.PASSWORD_RESET);
            default -> fail();
        }
        String finalToken = token;
        var exception = assertThrows(AuthException.class, () -> service.reset(body(finalToken)));
        assertSame(scenario.equals("exact-expiry") || scenario.equals("expired") ? AuthErrorCode.EMAIL_VERIFICATION_TOKEN_EXPIRED : AuthErrorCode.EMAIL_VERIFICATION_INVALID,
                exception.getBaseErrorCode());
        assertEquals(oldHash, passwordHash());
        assertTrue(sessions.authenticate(active.accessToken()).isPresent());
    }

    @Test
    void signupProofCannotResetPassword() {
        String token = proof(VerificationPurpose.SIGNUP);
        assertThrows(AuthException.class, () -> service.reset(body(token)));
        assertEquals(oldHash, passwordHash());
    }

    @ParameterizedTest
    @ValueSource(strings = {"google", "missing-password", "suspended", "deleted", "withdrawn"})
    void accountEligibilityIsRechecked(String scenario) {
        String token = proof(VerificationPurpose.PASSWORD_RESET);
        switch (scenario) {
            case "google" -> jdbc.update("UPDATE member SET signup_method='GOOGLE',password_hash=NULL WHERE id=?", member.getId());
            case "missing-password" -> jdbc.update("UPDATE member SET password_hash=NULL WHERE id=?", member.getId());
            case "suspended" -> jdbc.update("UPDATE member SET status='SUSPENDED' WHERE id=?", member.getId());
            case "deleted" -> jdbc.update("UPDATE member SET status='DELETED' WHERE id=?", member.getId());
            case "withdrawn" -> jdbc.update("UPDATE member SET withdrawn_at=? WHERE id=?", Timestamp.from(NOW), member.getId());
            default -> fail();
        }
        String before = passwordHash();
        assertSame(AuthErrorCode.PASSWORD_RESET_NOT_ALLOWED, assertThrows(AuthException.class, () -> service.reset(body(token))).getBaseErrorCode());
        assertEquals(before, passwordHash());
        assertNull(latest().getConsumedAt());
    }

    @Test
    void oldAccountProofCannotBeUsedAfterEmailReRegistration() {
        String token = proof(VerificationPurpose.PASSWORD_RESET);
        jdbc.update("UPDATE member SET status='DELETED',withdrawn_at=? WHERE id=?", Timestamp.from(NOW), member.getId());
        Member replacement = account(email);
        jdbc.update("UPDATE member SET created_at=? WHERE id=?", Timestamp.from(latest().getCreatedAt().plusSeconds(1)), replacement.getId());
        String before = replacement.getPasswordHash();
        assertSame(AuthErrorCode.EMAIL_VERIFICATION_INVALID, assertThrows(AuthException.class, () -> service.reset(body(token))).getBaseErrorCode());
        assertEquals(before, members.findById(replacement.getId()).orElseThrow().getPasswordHash());
        assertNull(latest().getConsumedAt());
    }

    @Test
    void missingAccountAndWrongEmailDoNotConsumeProof() {
        String token = proof(VerificationPurpose.PASSWORD_RESET);
        assertThrows(AuthException.class, () -> service.reset(new PasswordResetRequest("absent@example.invalid", token, NEW)));
        jdbc.update("DELETE FROM member WHERE id=?", member.getId());
        assertSame(AuthErrorCode.EMAIL_NOT_REGISTERED, assertThrows(AuthException.class, () -> service.reset(body(token))).getBaseErrorCode());
        assertNull(latest().getConsumedAt());
    }

    @Test
    void proofExpiringDuringHashingRollsBackEverything() {
        var active = login(member);
        String token = proof(VerificationPurpose.PASSWORD_RESET);
        doAnswer(invocation -> { Object hash = invocation.callRealMethod(); time.set(NOW.plusSeconds(600)); return hash; }).when(passwords).hash(NEW);
        assertSame(AuthErrorCode.EMAIL_VERIFICATION_TOKEN_EXPIRED, assertThrows(AuthException.class, () -> service.reset(body(token))).getBaseErrorCode());
        assertEquals(oldHash, passwordHash());
        assertNull(latest().getConsumedAt());
        assertTrue(sessions.authenticate(active.accessToken()).isPresent());
    }

    static Stream<Arguments> failures() {
        return Stream.of("member", "email_verification", "refresh_token")
                .flatMap(table -> Stream.of(Arguments.of(table, false), Arguments.of(table, true)));
    }

    private void removeFailure() {
        for (String table : List.of("member", "email_verification", "refresh_token"))
            jdbc.execute("DROP TRIGGER IF EXISTS password_reset_test_trigger ON " + table);
        jdbc.execute("DROP FUNCTION IF EXISTS password_reset_test_failure()");
    }

    @ParameterizedTest
    @MethodSource("failures")
    void databaseFailureRollsBackPasswordProofAndSessionAndAllowsRetry(String table, boolean deferred) throws Exception {
        var active = login(member);
        String token = proof(VerificationPurpose.PASSWORD_RESET);
        jdbc.execute("CREATE FUNCTION password_reset_test_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test failure'; END $$");
        jdbc.execute("CREATE " + (deferred ? "CONSTRAINT " : "") + "TRIGGER password_reset_test_trigger AFTER UPDATE ON " + table
                + (deferred ? " DEFERRABLE INITIALLY DEFERRED" : "") + " FOR EACH ROW EXECUTE FUNCTION password_reset_test_failure()");
        assertEquals(500, request(mapper.writeValueAsString(body(token))).statusCode());
        assertEquals(oldHash, passwordHash());
        assertNull(latest().getConsumedAt());
        assertTrue(sessions.authenticate(active.accessToken()).isPresent());
        removeFailure();
        assertEquals(204, request(mapper.writeValueAsString(body(token))).statusCode());
    }

    @Test
    void simultaneousProofUseChangesPasswordExactlyOnce() throws Exception {
        String token = proof(VerificationPurpose.PASSWORD_RESET);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> attempt = () -> { start.await(); try { service.reset(body(token)); return true; } catch (AuthException expected) { return false; } };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(attempt); var second = pool.submit(attempt); start.countDown();
            assertNotEquals(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        }
        assertNotNull(latest().getConsumedAt());
    }

    @Test
    void concurrentRenewalCannotKeepTheOldSessionAlive() throws Exception {
        var active = login(member);
        String token = proof(VerificationPurpose.PASSWORD_RESET);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var reset = pool.submit(() -> { start.await(); service.reset(body(token)); return true; });
            var rotated = pool.submit(() -> { start.await(); try { return renew.renew(active.refreshToken()); } catch (AuthException expected) { return null; } });
            start.countDown(); assertTrue(reset.get(30, TimeUnit.SECONDS));
            var result = rotated.get(30, TimeUnit.SECONDS);
            assertTrue(sessions.authenticate(active.accessToken()).isEmpty());
            if (result != null) assertTrue(sessions.authenticate(result.accessToken()).isEmpty());
            assertThrows(AuthException.class, () -> renew.renew(active.refreshToken()));
        }
    }

    @Test
    void previouslyValidatedOldPasswordCannotCreateSessionAfterReset() {
        var validated = credentials.validateMember(email, OLD);
        service.reset(body(proof(VerificationPurpose.PASSWORD_RESET)));
        assertSame(AuthErrorCode.INVALID_LOGIN_CREDENTIALS, assertThrows(AuthException.class, () -> sessions.issue(validated)).getBaseErrorCode());
    }

    @Test
    void outboxConfirmAndResetPreserveRegisteredRecipient() throws Exception {
        when(generator.generate()).thenReturn("012345");
        var delivery = send.send(new EmailVerificationRequest(email.toUpperCase(Locale.ROOT), VerificationPurpose.PASSWORD_RESET));
        assertEquals("PENDING", delivery.status());
        assertEquals(0, delivery.expiresIn());
        assertThrows(AuthException.class, () -> confirm.confirm(new EmailVerificationConfirmRequest(email, VerificationPurpose.PASSWORD_RESET, "012345")));
        worker.runOnce();
        verify(mail).sendVerificationCode(email, "012345", VerificationPurpose.PASSWORD_RESET);
        var verified = confirm.confirm(new EmailVerificationConfirmRequest(email.toUpperCase(Locale.ROOT), VerificationPurpose.PASSWORD_RESET, "012345"));
        assertEquals(600, verified.expiresIn());
        assertEquals(204, request(mapper.writeValueAsString(body(verified.verificationToken()))).statusCode());
        assertEquals(email, latest().getEmail());
        assertTrue(passwords.matches(NEW, passwordHash()));
    }

    @Test
    void staleAuthorizationHeaderDoesNotBlockProofBasedReset() throws Exception {
        String token = proof(VerificationPurpose.PASSWORD_RESET);
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/password/reset"))
                .header("Content-Type", "application/json").header("Authorization", "Bearer expired")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body(token)))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(204, response.statusCode());
        assertNotNull(latest().getConsumedAt());
    }

    @Test
    void loginCookieAndAccessTokenDoNotReplaceResetProof() throws Exception {
        var active = login(member);
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/password/reset"))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + active.accessToken())
                .header("Cookie", "bareum_refresh=" + active.refreshToken())
                .method("PATCH", HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(new PasswordResetRequest(email, null, NEW)))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode());
        assertEquals(oldHash, passwordHash());
        assertTrue(sessions.authenticate(active.accessToken()).isPresent());
    }

    @Test
    void untrustedOriginCannotResetWithValidProof() throws Exception {
        String token = proof(VerificationPurpose.PASSWORD_RESET);
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/password/reset"))
                .header("Content-Type", "application/json").header("Origin", "https://untrusted.example.invalid")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body(token)))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, response.statusCode());
        assertEquals(oldHash, passwordHash());
        assertNull(latest().getConsumedAt());
    }

    static Stream<Arguments> invalidInputs() {
        return Stream.of(
                Arguments.of("{}"), Arguments.of("[]"), Arguments.of("null"), Arguments.of("{invalid"),
                Arguments.of("{\"email\":\"invalid\",\"verificationToken\":\"" + "A".repeat(43) + "\",\"password\":\"" + NEW + "\"}"),
                Arguments.of("{\"email\":\"a@example.invalid\",\"verificationToken\":null,\"password\":\"" + NEW + "\"}"),
                Arguments.of("{\"email\":\"a@example.invalid\",\"verificationToken\":\"short\",\"password\":\"" + NEW + "\"}"),
                Arguments.of("{\"email\":null,\"verificationToken\":\"" + "A".repeat(43) + "\",\"password\":\"" + NEW + "\"}"),
                Arguments.of("{\"email\":\"a@example.invalid\",\"verificationToken\":\"" + "A".repeat(43) + "\",\"password\":\"short\"}"));
    }

    @ParameterizedTest
    @MethodSource("invalidInputs")
    void inputErrorsReturnProblemWithoutEchoingSecrets(String invalid) throws Exception {
        var response = request(invalid);
        assertEquals(400, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/problem+json"));
        assertFalse(response.body().contains(NEW));
        assertEquals(oldHash, passwordHash());
    }

    @ParameterizedTest
    @ValueSource(strings = {"OnlyLettersLong", "123456789012", "Password12345", "Password123! ", "Password123^"})
    void usesTheSamePasswordPolicyAsSignup(String invalid) throws Exception {
        String token = proof(VerificationPurpose.PASSWORD_RESET);
        var response = request(mapper.writeValueAsString(new PasswordResetRequest(email, token, invalid)));
        assertEquals(400, response.statusCode());
        assertEquals("AUTH-0012", mapper.readTree(response.body()).get("code").asText());
        assertEquals(oldHash, passwordHash());
        assertNull(latest().getConsumedAt());
    }

    @Test
    void acceptsSpecifiedPasswordRequirementsWithOtherNonWhitespaceCharacters() throws Exception {
        String token = proof(VerificationPurpose.PASSWORD_RESET);
        String password = String.join("", "한글", "Password", "123", "!*");
        assertEquals(204, request(mapper.writeValueAsString(
                new PasswordResetRequest(email, token, password))).statusCode());
        assertTrue(passwords.matches(password, passwordHash()));
        assertTrue(passwordHash().startsWith("{argon2id-v1}$argon2id$"));
    }

    @Test
    void requestToStringAndOpenApiDoNotExposeSecretsAndDocumentNoBodySuccess() throws Exception {
        String token = tokens.generate();
        assertFalse(body(token).toString().contains(token));
        assertFalse(body(token).toString().contains(NEW));
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        var doc = mapper.readTree(response.body());
        var operation = doc.get("paths").get("/api/v1/auth/password/reset").get("patch");
        assertNotNull(operation.get("responses").get("204"));
        assertEquals("#/components/schemas/BadRequestProblemDetail", operation.get("responses").get("400").get("content").get("application/problem+json").get("schema").get("$ref").asText());
        assertEquals(2, doc.get("components").get("schemas").get("BadRequestProblemDetail").get("anyOf").size());
        var props = doc.get("components").get("schemas").get("PasswordResetRequest").get("properties");
        assertTrue(props.get("password").get("writeOnly").asBoolean());
        assertTrue(props.get("verificationToken").get("writeOnly").asBoolean());
    }
}
