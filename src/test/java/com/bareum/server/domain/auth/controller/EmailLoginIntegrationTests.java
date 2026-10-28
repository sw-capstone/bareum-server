package com.bareum.server.domain.auth.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.bareum.server.domain.auth.dto.request.EmailLoginRequest;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.service.EmailLoginCredentialService;
import com.bareum.server.domain.auth.service.EmailLoginService;
import com.bareum.server.domain.auth.service.LoginAttemptLimiter;
import com.bareum.server.domain.auth.service.LoginSessionService;
import com.bareum.server.domain.auth.service.LoginTokenGenerator;
import com.bareum.server.domain.auth.service.PasswordHashService;
import com.bareum.server.domain.member.entity.Member;
import com.bareum.server.domain.member.repository.MemberRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = {
		"auth.login.allowed-origins=https://web.example.invalid,https://bareum.site,https://bareum-web-git-develop-numkites-projects.vercel.app",
		"auth.login.max-requests-per-window=5",
		"auth.login.cookie-secure=true",
		"auth.login.cookie-same-site=None"
	})
class EmailLoginIntegrationTests {
	private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");
	private static final String PASSWORD = String.join("", "Legacy", "Input", "!");
	private static String storedHash;
	private final List<Long> memberIds = new ArrayList<>();
	private final List<String> emailKeys = new ArrayList<>();
	private final List<Long> termIds = new ArrayList<>();
	private final List<Long> verificationIds = new ArrayList<>();
	private final HttpClient client = HttpClient.newHttpClient();
	private String email;
	private Member member;
	@LocalServerPort
	private int port;
	@Autowired
	private MemberRepository members;
	@Autowired
	private PasswordHashService passwordHashes;
	@Autowired
	private EmailLoginService loginService;
	@Autowired
	private EmailLoginCredentialService credentials;
	@Autowired
	private LoginSessionService sessions;
	@Autowired
	private LoginAttemptLimiter limiter;
	@Autowired
	private LoginTokenGenerator tokens;
	@Autowired
	private com.bareum.server.domain.auth.service.LoginJwtService jwt;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private com.bareum.server.domain.auth.repository.LoginAccessTokenQueryRepository accessTokens;
	@Autowired
	private com.bareum.server.domain.auth.repository.EmailVerificationRepository verifications;
	@Autowired
	private com.bareum.server.domain.auth.service.EmailVerificationTokenGenerator proofs;
	@Autowired
	private com.bareum.server.domain.term.repository.TermRepository terms;
	@Autowired
	private JsonMapper mapper;

	@MockitoBean(name = "loginClock")
	private Clock clock;

	@BeforeEach
	void setUp() {
		when(clock.instant()).thenReturn(NOW);
		if (storedHash == null)
			storedHash = passwordHashes.hash(PASSWORD);
		email = "Login-" + UUID.randomUUID() + "@example.invalid";
		member = members.saveAndFlush(Member.createLocal(email, "사용자", storedHash));
		memberIds.add(member.getId());
		trackEmail(email);
	}

	private void trackEmail(String address) {
		String normalized = jdbc.queryForObject("SELECT upper(?)", String.class, address);
		emailKeys.add(tokens.hash("login-limit", normalized));
	}

	@AfterEach
	void cleanUp() {
		for (long id : memberIds) {
			jdbc.update("DELETE FROM member_term WHERE member_id=?", id);
			jdbc.update("DELETE FROM refresh_token WHERE member_id=?", id);
			jdbc.update("DELETE FROM member WHERE id=?", id);
		}
		for (long id : verificationIds)
			verifications.deleteById(id);
		for (long id : termIds)
			terms.deleteById(id);
		for (String key : emailKeys)
			jdbc.update("DELETE FROM login_request_window WHERE email_key=?", key);
	}

	private HttpResponse<String> request(String method, String path, String body, String... headers)
			throws Exception {
		var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
		for (int i = 0; i < headers.length; i += 2)
			builder.header(headers[i], headers[i + 1]);
		if (body == null)
			builder.method(method, HttpRequest.BodyPublishers.noBody());
		else
			builder.header("Content-Type", "application/json")
					.method(method, HttpRequest.BodyPublishers.ofString(body));
		return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> login(String address, String password) throws Exception {
		return request(
				"POST",
				"/api/v1/auth/login/email",
				mapper.writeValueAsString(Map.of("email", address, "password", password)));
	}

	private JsonNode json(HttpResponse<String> response) {
		return mapper.readTree(response.body());
	}

	private void problem(HttpResponse<String> response, int status, String code) {
		assertEquals(status, response.statusCode(), response.body());
		assertTrue(
				response.headers()
						.firstValue("Content-Type")
						.orElse("")
						.contains("application/problem+json"));
		assertEquals(code, json(response).path("code").asString());
		assertTrue(json(response).has("instance"));
		assertFalse(response.body().contains(PASSWORD));
		assertTrue(
				response.headers()
						.allValues("Set-Cookie")
						.stream()
						.noneMatch(
								cookie -> cookie.startsWith("bareum_refresh=")
										|| cookie.startsWith("JSESSIONID=")));
	}

	private LoginSessionService.IssuedLogin issue() {
		return loginService.login(new EmailLoginRequest(email, PASSWORD, null));
	}

	@Test
	void signedUpMemberCanLogInAndReadItsOwnProfile() throws Exception {
		for (var code : com.bareum.server.domain.term.enums.TermCode.values()) {
			termIds.add(terms.saveAndFlush(com.bareum.server.domain.term.entity.Term.create(
					code, "login-integration", code != com.bareum.server.domain.term.enums.TermCode.MARKETING_CONSENT))
					.getId());
		}
		String address = "signup-login-" + UUID.randomUUID() + "@example.invalid";
		String password = "Examplepass123!";
		String proof = proofs.generate();
		Instant verifiedAt = Instant.now();
		var verification = com.bareum.server.domain.auth.entity.EmailVerification.request(address,
				com.bareum.server.domain.auth.enums.VerificationPurpose.SIGNUP,
				"test-only-code-hash", verifiedAt.plusSeconds(300));
		verification.markSent(verifiedAt);
		verification.markVerified(proofs.hash(proof), verifiedAt, verifiedAt.plusSeconds(600));
		verificationIds.add(verifications.saveAndFlush(verification).getId());
		trackEmail(address);
		var signup = request("POST", "/api/v1/auth/signup", mapper.writeValueAsString(Map.of(
				"email", address, "name", "가입한 사용자", "password", password, "verificationToken", proof,
				"agreements", Map.of("age", true, "service", true, "privacy", true, "marketing", false))));
		assertEquals(201, signup.statusCode(), signup.body());
		long memberId = json(signup).path("memberId").asLong();
		memberIds.add(memberId);
		assertTrue(signup.headers().allValues("Set-Cookie").isEmpty());
		assertEquals(0,
				jdbc.queryForObject("SELECT count(*) FROM refresh_token WHERE member_id=?", Integer.class, memberId));
		var loggedIn = login(address.toUpperCase(java.util.Locale.ROOT), password);
		assertEquals(200, loggedIn.statusCode(), loggedIn.body());
		var me = request("GET", "/api/v1/auth/me", null, "Authorization",
				"Bearer " + json(loggedIn).path("accessToken").asString());
		assertEquals(200, me.statusCode(), me.body());
		assertEquals(memberId, json(me).path("memberId").asLong());
		assertEquals(address, json(me).path("email").asString());
		assertEquals("가입한 사용자", json(me).path("name").asString());
		assertEquals(4,
				jdbc.queryForObject("SELECT count(*) FROM member_term WHERE member_id=?", Integer.class, memberId));
		assertNotNull(verifications.findById(verification.getId()).orElseThrow().getConsumedAt());
	}

	@Test
	void healthAndExistingPublicApisRemainAvailableWithoutAuthentication() throws Exception {
		assertEquals(200, request("GET", "/health", null).statusCode());
		assertEquals(200, request("GET", "/api/v1/terms", null).statusCode());
		assertEquals(200,
				request("GET", "/api/v1/auth/email/availability?email=public%40example.invalid", null).statusCode());
		problem(request("GET", "/api/v1/auth/me", null), 401, "AUTH-0017");
	}

	@ParameterizedTest
	@ValueSource(strings = {"https://bareum.site", "https://bareum-web-git-develop-numkites-projects.vercel.app"})
	void configuredFrontendOriginsCanRequestCredentialedLogin(String origin) throws Exception {
		var response = request("OPTIONS", "/api/v1/auth/login/email", null, "Origin", origin,
				"Access-Control-Request-Method", "POST", "Access-Control-Request-Headers", "content-type");
		assertEquals(200, response.statusCode(), response.body());
		assertEquals(origin, response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
		assertEquals("true", response.headers().firstValue("Access-Control-Allow-Credentials").orElseThrow());
	}

	@Test
	void mixedCaseLoginIssuesHashOnlyTokensAndCookieAndAuthenticatesMe() throws Exception {
		var response = login(email.toUpperCase(java.util.Locale.ROOT), PASSWORD);
		assertEquals(200, response.statusCode(), response.body());
		JsonNode body = json(response);
		String access = body.path("accessToken").asString();
		assertTrue(jwt.verify(access, "access").isPresent());
		assertTrue(jwt.verify(access, "refresh").isEmpty());
		assertEquals("Bearer", body.path("tokenType").asString());
		assertEquals(900, body.path("expiresIn").asInt());
		assertEquals(28800, body.path("sessionExpiresIn").asInt());
		assertEquals(4, body.size());
		assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
		String cookie = response.headers().firstValue("Set-Cookie").orElseThrow();
		assertTrue(cookie.contains("HttpOnly"));
		assertTrue(cookie.contains("Secure"));
		assertTrue(cookie.contains("SameSite=None"));
		assertTrue(cookie.contains("Path=/api/v1/auth"));
		assertTrue(cookie.contains("Max-Age=28800"));
		assertFalse(cookie.contains("Domain="));
		String refresh = cookie.substring("bareum_refresh=".length(), cookie.indexOf(';'));
		assertFalse(response.body().contains(refresh));
		var row = jdbc.queryForMap("SELECT * FROM refresh_token WHERE member_id=?", member.getId());
		assertEquals(tokens.hash("access", access), row.get("access_token_hash"));
		assertEquals(tokens.hash("refresh", refresh), row.get("refresh_token_hash"));
		assertFalse(row.values().toString().contains(access));
		assertFalse(row.values().toString().contains(refresh));
		assertEquals(NOW.plusSeconds(900), ((Timestamp)row.get("access_expires_at")).toInstant());
		assertEquals(NOW.plusSeconds(28800), ((Timestamp)row.get("expires_at")).toInstant());
		var me = request("GET", "/api/v1/auth/me", null, "Authorization", "Bearer " + access);
		assertEquals(200, me.statusCode(), me.body());
		assertEquals(member.getId().longValue(), json(me).path("memberId").asLong());
		assertEquals(email, json(me).path("email").asString());
		assertEquals("사용자", json(me).path("name").asString());
		assertEquals(3, json(me).size());
		assertEquals("no-store", me.headers().firstValue("Cache-Control").orElseThrow());
		problem(
				request("GET", "/api/v1/auth/me", null, "Cookie", "bareum_refresh=" + refresh),
				401,
				"AUTH-0017");
		problem(
				request("GET", "/api/v1/auth/me", null, "Authorization", "Bearer " + refresh),
				401,
				"AUTH-0017");
	}

	@Test
	void wrongAndMissingAccountsUseSameFailureAndPersistAttemptsWithoutIssuingTokens()
			throws Exception {
		String absent = "missing-" + UUID.randomUUID() + "@example.invalid";
		trackEmail(absent);
		var wrong = login(email, "wrong-input");
		var missing = login(absent, "wrong-input");
		problem(wrong, 401, "AUTH-0014");
		problem(missing, 401, "AUTH-0014");
		assertEquals(json(wrong).path("detail"), json(missing).path("detail"));
		assertFalse(wrong.body().contains(email));
		assertEquals(
				0,
				jdbc.queryForObject(
						"SELECT count(*) FROM refresh_token WHERE member_id=?",
						Integer.class,
						member.getId()));
		assertEquals(
				1,
				jdbc.queryForObject(
						"SELECT request_count FROM login_request_window WHERE email_key=?",
						Integer.class,
						emailKeys.getFirst()));
	}

	@Test
	void googleOnlyAndRestrictedAccountsCannotIssueCredentials() throws Exception {
		String googleEmail = "google-" + UUID.randomUUID() + "@example.invalid";
		Member google = members.saveAndFlush(Member.createGoogle(googleEmail, "구글 사용자"));
		memberIds.add(google.getId());
		trackEmail(googleEmail);
		problem(login(googleEmail, PASSWORD), 403, "AUTH-0015");
		jdbc.update("UPDATE member SET status='SUSPENDED' WHERE id=?", member.getId());
		problem(login(email, PASSWORD), 403, "AUTH-0016");
		problem(login(email, "wrong"), 401, "AUTH-0014");
		jdbc.update(
				"UPDATE member SET status='DELETED',withdrawn_at=? WHERE id=?",
				Timestamp.from(NOW),
				google.getId());
		problem(login(googleEmail, PASSWORD), 401, "AUTH-0014");
	}

	@ParameterizedTest
	@ValueSource(
		strings = {
			"{}",
			"{\"email\":\"invalid\",\"password\":\"a\"}",
			"{\"email\":\"a@example.invalid\",\"password\":\"\"}",
			"{\"email\":\"a@example.invalid\",\"password\":\"a\",\"keepSignedIn\":true}",
			"{broken"
		})
	void invalidRequestsReturnDocumented400WithoutCookie(String body) throws Exception {
		var response = request("POST", "/api/v1/auth/login/email", body);
		assertEquals(400, response.statusCode(), response.body());
		assertTrue(
				response.headers()
						.allValues("Set-Cookie")
						.stream()
						.noneMatch(
								cookie -> cookie.startsWith("bareum_refresh=")
										|| cookie.startsWith("JSESSIONID=")));
		assertTrue(json(response).has("code"));
		assertFalse(response.body().contains("keepSignedIn\":true"));
	}

	@Test
	void rememberedLoginFalseDoesNotExtendLifetimeAndLegacyPasswordIsNotNormalized()
			throws Exception {
		String legacy = " legacy input ";
		jdbc.update(
				"UPDATE member SET password_hash=? WHERE id=?",
				passwordHashes.hash(legacy),
				member.getId());
		var accepted =
				request(
						"POST",
						"/api/v1/auth/login/email",
						mapper.writeValueAsString(
								Map.of("email", email, "password", legacy, "keepSignedIn", false)));
		assertEquals(200, accepted.statusCode(), accepted.body());
		assertEquals(28800, json(accepted).path("sessionExpiresIn").asInt());
		problem(login(email, legacy.trim()), 401, "AUTH-0014");
	}

	@Test
	void reloginReplacesRefreshButPreservesPreviousAccessUntilItsOriginalExpiry() throws Exception {
		var first = issue();
		when(clock.instant()).thenReturn(NOW.plusSeconds(100));
		var second = issue();
		assertNotEquals(first.accessToken(), second.accessToken());
		assertNotEquals(first.refreshToken(), second.refreshToken());
		assertEquals(200, request("GET", "/api/v1/auth/me", null,
				"Authorization", "Bearer " + first.accessToken()).statusCode());
		assertTrue(sessions.authenticate(second.accessToken()).isPresent());
		assertEquals(
				tokens.hash("refresh", second.refreshToken()),
				jdbc.queryForObject(
						"SELECT refresh_token_hash FROM refresh_token WHERE member_id=?",
						String.class,
						member.getId()));
		when(clock.instant()).thenReturn(first.accessExpiresAt());
		assertTrue(sessions.authenticate(first.accessToken()).isEmpty());
		assertTrue(sessions.authenticate(second.accessToken()).isPresent());
		assertEquals(
				1,
				jdbc.queryForObject(
						"SELECT count(*) FROM refresh_token WHERE member_id=?",
						Integer.class,
						member.getId()));
	}

	@Test
	void expirationIsExclusiveAtAccessAndAbsoluteSessionBoundaries() {
		var issued = issue();
		when(clock.instant()).thenReturn(NOW.plusSeconds(899));
		assertTrue(sessions.authenticate(issued.accessToken()).isPresent());
		when(clock.instant()).thenReturn(NOW.plusSeconds(900));
		assertTrue(sessions.authenticate(issued.accessToken()).isEmpty());
		jdbc.update(
				"UPDATE refresh_token SET access_expires_at=expires_at WHERE member_id=?",
				member.getId());
		when(clock.instant()).thenReturn(NOW.plusSeconds(28799));
		assertTrue(sessions.authenticate(issued.accessToken()).isEmpty());
		when(clock.instant()).thenReturn(NOW.plusSeconds(28800));
		assertTrue(sessions.authenticate(issued.accessToken()).isEmpty());
	}

	@ParameterizedTest
	@ValueSource(strings = {"SUSPENDED", "DELETED"})
	void memberStateChangesImmediatelyInvalidateProtectedAccess(String status) throws Exception {
		var issued = issue();
		jdbc.update("UPDATE member SET status=? WHERE id=?", status, member.getId());
		problem(
				request(
						"GET",
						"/api/v1/auth/me",
						null,
						"Authorization",
						"Bearer " + issued.accessToken()),
				401,
				"AUTH-0017");
	}

	@Test
	void withdrawnAndRevokedSessionsAreRejected() {
		var issued = issue();
		jdbc.update(
				"UPDATE refresh_token SET revoked_at=? WHERE member_id=?",
				Timestamp.from(NOW),
				member.getId());
		assertTrue(sessions.authenticate(issued.accessToken()).isEmpty());
		jdbc.update("UPDATE refresh_token SET revoked_at=NULL WHERE member_id=?", member.getId());
		jdbc.update(
				"UPDATE member SET withdrawn_at=? WHERE id=?", Timestamp.from(NOW), member.getId());
		assertTrue(sessions.authenticate(issued.accessToken()).isEmpty());
	}

	@ParameterizedTest
	@ValueSource(strings = {"Basic abc", "Bearer short", "Bearer ", "bearer invalid", "Token abc"})
	void malformedAuthorizationIsACommonProblemResponse(String value) throws Exception {
		problem(request("GET", "/api/v1/auth/me", null, "Authorization", value), 401, "AUTH-0017");
	}

	@Test
	void missingAndDuplicateAuthorizationCannotAuthenticate() throws Exception {
		problem(request("GET", "/api/v1/auth/me", null), 401, "AUTH-0017");
		var issued = issue();
		problem(
				request(
						"GET",
						"/api/v1/auth/me",
						null,
						"Authorization",
						"Bearer " + issued.accessToken(),
						"Authorization",
						"Bearer " + issued.accessToken()),
				401,
				"AUTH-0017");
	}

	@Test
	void corsAcceptsOnlyConfiguredOriginWithCredentialsAndRejectsBeforeLogin() throws Exception {
		var preflight =
				request(
						"OPTIONS",
						"/api/v1/auth/login/email",
						null,
						"Origin",
						"https://web.example.invalid",
						"Access-Control-Request-Method",
						"POST",
						"Access-Control-Request-Headers",
						"Content-Type,Authorization");
		assertEquals(200, preflight.statusCode(), preflight.body());
		assertEquals(
				"https://web.example.invalid",
				preflight.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
		assertEquals(
				"true",
				preflight.headers().firstValue("Access-Control-Allow-Credentials").orElseThrow());
		var blocked =
				request(
						"POST",
						"/api/v1/auth/login/email",
						mapper.writeValueAsString(Map.of("email", email, "password", PASSWORD)),
						"Origin",
						"https://evil.example.invalid");
		assertEquals(403, blocked.statusCode());
		assertEquals(
				0,
				jdbc.queryForObject(
						"SELECT count(*) FROM refresh_token WHERE member_id=?",
						Integer.class,
						member.getId()));
		var allowed =
				request(
						"POST",
						"/api/v1/auth/login/email",
						mapper.writeValueAsString(Map.of("email", email, "password", PASSWORD)),
						"Origin",
						"https://web.example.invalid");
		assertEquals(200, allowed.statusCode(), allowed.body());
		assertEquals(
				"https://web.example.invalid",
				allowed.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
		assertTrue(allowed.headers().allValues("Set-Cookie").size() == 1);
	}

	@Test
	void stalePasswordOrAccountSnapshotCannotIssueSession() {
		var validated = credentials.validateMember(email, PASSWORD);
		jdbc.update(
				"UPDATE member SET password_hash=? WHERE id=?",
				passwordHashes.hash("changed-input"),
				member.getId());
		assertSame(
				AuthErrorCode.INVALID_LOGIN_CREDENTIALS,
				assertThrows(AuthException.class, () -> sessions.issue(validated))
						.getBaseErrorCode());
		jdbc.update("UPDATE member SET status='SUSPENDED' WHERE id=?", member.getId());
		assertSame(
				AuthErrorCode.LOGIN_ACCOUNT_RESTRICTED,
				assertThrows(AuthException.class, () -> sessions.issue(validated))
						.getBaseErrorCode());
		assertEquals(
				0,
				jdbc.queryForObject(
						"SELECT count(*) FROM refresh_token WHERE member_id=?",
						Integer.class,
						member.getId()));
	}

	@Test
	void tokenStorageFailureRollsBackSessionReplacement() {
		var previous = issue();
		String otherEmail = "other-" + UUID.randomUUID() + "@example.invalid";
		var other = members.saveAndFlush(Member.createLocal(otherEmail, "다른 사용자", storedHash));
		memberIds.add(other.getId());
		var collisionJwt = mock(com.bareum.server.domain.auth.service.LoginJwtService.class);
		when(collisionJwt.issue(eq("access"), anyLong(), any(), any(), any()))
				.thenReturn(previous.accessToken());
		when(collisionJwt.issue(eq("refresh"), anyLong(), any(), any(), any()))
				.thenReturn(previous.refreshToken());
		var props =
				new com.bareum.server.domain.auth.config.LoginProperties(
						900, 28800, true, "None", List.of(), 5, 60);
		var failing = new LoginSessionService(jdbc, tokens, collisionJwt, props, clock, accessTokens);
		var manager =
				new org.springframework.jdbc.datasource.DataSourceTransactionManager(
						jdbc.getDataSource());
		var transaction = new org.springframework.transaction.support.TransactionTemplate(manager);
		assertThrows(
				org.springframework.dao.DataIntegrityViolationException.class,
				() -> transaction.execute(
						status -> failing.issue(
								new EmailLoginCredentialService.ValidatedMember(
										other.getId(), storedHash))));
		assertTrue(sessions.authenticate(previous.accessToken()).isPresent());
		assertEquals(
				0,
				jdbc.queryForObject(
						"SELECT count(*) FROM refresh_token WHERE member_id=?",
						Integer.class,
						other.getId()));
	}

	@Test
	void concurrentSessionIssuanceKeepsExactlyOneCurrentSession() throws Exception {
		var validated = credentials.validateMember(email, PASSWORD);
		try (var executor = Executors.newFixedThreadPool(4)) {
			List<Callable<LoginSessionService.IssuedLogin>> tasks = new ArrayList<>();
			for (int i = 0; i < 4; i++)
				tasks.add(() -> sessions.issue(validated));
			var results = executor.invokeAll(tasks);
			int valid = 0;
			for (var result : results)
				if (sessions.authenticate(result.get().accessToken()).isPresent())
					valid++;
			assertEquals(4, valid);
		}
		assertEquals(
				1,
				jdbc.queryForObject(
						"SELECT count(*) FROM refresh_token WHERE member_id=?",
						Integer.class,
						member.getId()));
	}

	@Test
	void parallelRequestLimitUsesDatabaseEmailComparisonAndResetsOnlyAfterWindow()
			throws Exception {
		try (var executor = Executors.newFixedThreadPool(8)) {
			List<Callable<Boolean>> tasks = new ArrayList<>();
			for (int i = 0; i < 8; i++) {
				String address = i % 2 == 0 ? email : email.toUpperCase(java.util.Locale.ROOT);
				tasks.add(
						() -> {
							try {
								limiter.reserve(address);
								return true;
							} catch (AuthException e) {
								assertSame(
										AuthErrorCode.LOGIN_REQUEST_LIMIT_EXCEEDED,
										e.getBaseErrorCode());
								return false;
							}
						});
			}
			int accepted = 0;
			for (var result : executor.invokeAll(tasks))
				if (result.get())
					accepted++;
			assertEquals(5, accepted);
		}
		assertThrows(AuthException.class, () -> limiter.reserve(email));
		when(clock.instant()).thenReturn(NOW.plusSeconds(59));
		assertThrows(AuthException.class, () -> limiter.reserve(email));
		when(clock.instant()).thenReturn(NOW.plusSeconds(60));
		assertDoesNotThrow(() -> limiter.reserve(email));
		assertEquals(
				1,
				jdbc.queryForObject(
						"SELECT request_count FROM login_request_window WHERE email_key=?",
						Integer.class,
						emailKeys.getFirst()));
	}

	@Test
	void repeatedWrongCredentialsReturn429AndDoNotRollBackAttemptCount() throws Exception {
		for (int i = 0; i < 5; i++)
			problem(login(email, "wrong"), 401, "AUTH-0014");
		problem(login(email, PASSWORD), 429, "AUTH-0020");
		assertEquals(
				5,
				jdbc.queryForObject(
						"SELECT request_count FROM login_request_window WHERE email_key=?",
						Integer.class,
						emailKeys.getFirst()));
	}

	@Test
	void counterCleanupRetainsCurrentWindows() {
		limiter.reserve(email);
		when(clock.instant()).thenReturn(NOW.plusSeconds(86399));
		limiter.purgeExpiredWindows();
		assertEquals(
				1,
				jdbc.queryForObject(
						"SELECT count(*) FROM login_request_window WHERE email_key=?",
						Integer.class,
						emailKeys.getFirst()));
		when(clock.instant()).thenReturn(NOW.plusSeconds(86401));
		limiter.purgeExpiredWindows();
		assertEquals(
				0,
				jdbc.queryForObject(
						"SELECT count(*) FROM login_request_window WHERE email_key=?",
						Integer.class,
						emailKeys.getFirst()));
	}

	@Test
	void legacyRefreshMetadataRemainsCompatibleButCannotAuthorize() {
		jdbc.update(
				"INSERT INTO refresh_token(member_id,jti,expires_at) VALUES(?,?,?)",
				member.getId(),
				UUID.randomUUID(),
				Timestamp.from(NOW.plusSeconds(28800)));
		assertEquals(
				1,
				jdbc.queryForObject(
						"SELECT count(*) FROM refresh_token WHERE member_id=? AND access_token_hash"
								+ " IS NULL",
						Integer.class,
						member.getId()));
		assertTrue(sessions.authenticate("A".repeat(43)).isEmpty());
		var issued = issue();
		assertTrue(sessions.authenticate(issued.accessToken()).isPresent());
	}

	@Test
	void cookieAuthenticatedUnsafeEndpointsKeepCsrfProtection() throws Exception {
		var issued = issue();
		problem(
				request(
						"POST",
						"/api/v1/auth/token/renew",
						"{}",
						"Cookie",
						"bareum_refresh=" + issued.refreshToken()),
				403,
				"AUTH-0018");
		problem(
				request(
						"POST",
						"/api/v1/auth/logout",
						"{}",
						"Authorization",
						"Bearer " + issued.accessToken()),
				403,
				"AUTH-0018");
	}

	@Test
	void openApiDescribesLoginContractAndProtectedUserWithoutPrincipalParameter() throws Exception {
		var response = request("GET", "/v3/api-docs", null);
		assertEquals(200, response.statusCode(), response.body());
		var docs = json(response);
		assertEquals("bearer", docs.at("/components/securitySchemes/bearerAuth/scheme").asString());
		var paths = docs.path("paths");
		var login = paths.path("/api/v1/auth/login/email").path("post");
		assertTrue(login.path("responses").has("200"));
		for (String code : List.of("400", "401", "403", "429", "500"))
			assertTrue(login.path("responses").has(code));
		assertEquals(
				"#/components/schemas/BadRequestProblemDetail",
				login.at("/responses/400/content/application~1problem+json/schema/$ref")
						.asString());
		var me = paths.path("/api/v1/auth/me").path("get");
		assertTrue(me.path("security").get(0).has("bearerAuth"));
		assertTrue(me.path("parameters").isMissingNode() || me.path("parameters").isEmpty());
		assertTrue(
				docs.at("/components/schemas/EmailLoginRequest/properties/password/writeOnly")
						.asBoolean());
		assertFalse(
				docs.at("/components/schemas/EmailLoginResponse/properties").has("refreshToken"));
		var availability = request("GET", "/api/v1/auth/email/availability?email=" + email, null);
		assertEquals(200, availability.statusCode(), availability.body());
	}
}
