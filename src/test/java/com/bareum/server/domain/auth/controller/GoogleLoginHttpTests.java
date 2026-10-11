package com.bareum.server.domain.auth.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bareum.server.domain.auth.exception.*;
import com.bareum.server.domain.auth.service.*;
import com.bareum.server.global.config.*;
import com.bareum.server.global.exception.handler.GlobalExceptionHandler;
import com.bareum.server.global.security.handler.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.web.bind.annotation.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;

class GoogleLoginHttpTests {
    private static ServletWebServerApplicationContext context;
    private static HttpClient client;
    private static String base;
    private GoogleLoginService service;
    private LoginSessionService sessions;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private static final String TOKEN = "google-http-fixture";

    @BeforeAll
    static void start() {
        context =
                (ServletWebServerApplicationContext)
                        SpringApplication.run(
                                HttpApplication.class,
                                "--server.port=0",
                                "--auth.login.allowed-origins=https://web.example.invalid");
        client = HttpClient.newHttpClient();
        base = "http://127.0.0.1:" + context.getWebServer().getPort();
    }

    @AfterAll
    static void stop() {
        client.close();
        context.close();
    }

    @BeforeEach
    void setup() {
        service = context.getBean(GoogleLoginService.class);
        sessions = context.getBean(LoginSessionService.class);
        reset(service, sessions);
    }

    private HttpResponse<String> request(String method, String path, String body, String... headers)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path));
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

    private List<String> protection() throws Exception {
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

    private HttpResponse<String> post(String body) throws Exception {
        return request(
                "POST", "/api/v1/auth/login/google", body, protection().toArray(String[]::new));
    }

    private String body() {
        return "{\"idToken\":\"" + TOKEN + "\"}";
    }

    @Test
    void loginResponseContainsOnlyAccessTokenAndHttpOnlyRefreshCookie() throws Exception {
        String access = new LoginTokenGenerator().generate(),
                refresh = new LoginTokenGenerator().generate();
        Instant now = Instant.now();
        when(service.login(TOKEN))
                .thenReturn(
                        new GoogleLoginTransactionService.Result(
                                new LoginSessionService.IssuedLogin(
                                        access,
                                        refresh,
                                        now.plusSeconds(900),
                                        now.plusSeconds(28800)),
                                null,
                                null));
        var response = post(body());
        assertEquals(200, response.statusCode(), response.body());
        var data = json(response);
        assertEquals("LOGIN", data.path("status").asString());
        assertEquals(access, data.path("accessToken").asString());
        assertEquals("Bearer", data.path("tokenType").asString());
        assertTrue(data.path("expiresIn").asInt() > 890);
        assertFalse(data.has("signupToken"));
        assertFalse(response.body().contains(refresh));
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(cookie.startsWith("bareum_refresh=" + refresh));
        for (String expected : List.of("HttpOnly", "Secure", "SameSite=None", "Path=/api/v1/auth"))
            assertTrue(cookie.contains(expected));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("no-cache", response.headers().firstValue("Pragma").orElseThrow());
    }

    @Test
    void signupResponseDoesNotIssueLoginCredentials() throws Exception {
        String proof = "gs_" + new LoginTokenGenerator().generate();
        when(service.login(TOKEN))
                .thenReturn(
                        new GoogleLoginTransactionService.Result(
                                null, proof, Instant.now().plusSeconds(600)));
        var response = post(body());
        var data = json(response);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("SIGNUP_REQUIRED", data.path("status").asString());
        assertEquals(proof, data.path("signupToken").asString());
        assertTrue(data.path("signupExpiresIn").asInt() > 590);
        assertFalse(data.has("accessToken"));
        assertFalse(data.has("expiresIn"));
        assertTrue(
                response.headers().allValues("Set-Cookie").stream()
                        .noneMatch(c -> c.startsWith("bareum_refresh=")));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }

    @Test
    void csrfIsRequiredEvenWhenBearerIsPresent() throws Exception {
        for (var headers :
                List.of(
                        List.of("Content-Type", "application/json"),
                        List.of(
                                "Content-Type",
                                "application/json",
                                "Authorization",
                                "Bearer " + new LoginTokenGenerator().generate())))
            assertEquals(
                    403,
                    request(
                                    "POST",
                                    "/api/v1/auth/login/google",
                                    body(),
                                    headers.toArray(String[]::new))
                            .statusCode());
        verifyNoInteractions(service, sessions);
    }

    @Test
    void csrfMismatchAndUnlistedOriginAreRejected() throws Exception {
        var headers = protection();
        headers.set(3, "invalid-csrf");
        assertEquals(
                403,
                request("POST", "/api/v1/auth/login/google", body(), headers.toArray(String[]::new))
                        .statusCode());
        headers = protection();
        headers.set(5, "https://attacker.example.invalid");
        assertEquals(
                403,
                request("POST", "/api/v1/auth/login/google", body(), headers.toArray(String[]::new))
                        .statusCode());
        verifyNoInteractions(service);
    }

    @Test
    void corsPreflightAllowsOnlyConfiguredOriginAndCsrfHeader() throws Exception {
        var response =
                request(
                        "OPTIONS",
                        "/api/v1/auth/login/google",
                        null,
                        "Origin",
                        "https://web.example.invalid",
                        "Access-Control-Request-Method",
                        "POST",
                        "Access-Control-Request-Headers",
                        "content-type,x-csrf-token");
        assertEquals(200, response.statusCode());
        assertEquals(
                "true",
                response.headers().firstValue("Access-Control-Allow-Credentials").orElseThrow());
        assertEquals(
                "https://web.example.invalid",
                response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"idToken\":null}", "{\"idToken\":\"\"}", "broken"})
    void invalidInputCannotReachService(String body) throws Exception {
        assertEquals(400, post(body).statusCode());
        verifyNoInteractions(service);
    }

    @Test
    void oversizedInputIsRejectedWithoutEcho() throws Exception {
        String large = "s".repeat(8193);
        var response = post("{\"idToken\":\"" + large + "\"}");
        assertEquals(400, response.statusCode());
        assertFalse(response.body().contains(large));
        verifyNoInteractions(service);
    }

    @Test
    void allGoogleBusinessErrorsUseProblemDetailsWithoutToken() throws Exception {
        for (var code :
                List.of(
                        AuthErrorCode.INVALID_GOOGLE_AUTHENTICATION,
                        AuthErrorCode.GOOGLE_LOGIN_NOT_CONFIGURED,
                        AuthErrorCode.GOOGLE_AUTHENTICATION_UNAVAILABLE,
                        AuthErrorCode.EMAIL_LOGIN_REQUIRED,
                        AuthErrorCode.GOOGLE_EMAIL_VERIFICATION_REQUIRED,
                        AuthErrorCode.LOGIN_SERVICE_UNAVAILABLE,
                        AuthErrorCode.LOGIN_REQUEST_LIMIT_EXCEEDED,
                        AuthErrorCode.LOGIN_ACCOUNT_RESTRICTED)) {
            when(service.login(TOKEN)).thenThrow(new AuthException(code));
            var response = post(body());
            assertEquals(code.getHttpStatus().value(), response.statusCode());
            assertEquals(code.getCode(), json(response).path("code").asString());
            assertFalse(response.body().contains(TOKEN));
            assertTrue(
                    response.headers()
                            .firstValue("Content-Type")
                            .orElseThrow()
                            .contains("application/problem+json"));
            reset(service);
        }
    }

    @Test
    void signupProofCannotAuthenticateProtectedResource() throws Exception {
        String proof = "gs_" + new LoginTokenGenerator().generate();
        assertEquals(
                401,
                request("GET", "/protected-google-test", null, "Authorization", "Bearer " + proof)
                        .statusCode());
        assertEquals(401, request("GET", "/protected-google-test", null).statusCode());
        verifyNoInteractions(sessions);
    }

    @Test
    void openApiIncludesBothSuccessExamplesAndBadRequestAnyOf() throws Exception {
        var document = json(request("GET", "/v3/api-docs", null));
        var operation = document.path("paths").path("/api/v1/auth/login/google").path("post");
        assertEquals("Google 로그인", operation.path("summary").asString());
        var examples =
                operation
                        .path("responses")
                        .path("200")
                        .path("content")
                        .path("application/json")
                        .path("examples");
        assertTrue(examples.has("기존 회원") && examples.has("신규 회원"));
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

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({
        SecurityConfig.class,
        OpenApiConfig.class,
        GoogleLoginController.class,
        CsrfTokenController.class,
        LoginCookieFactory.class,
        SecurityProblemWriter.class,
        CustomAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class,
        GlobalExceptionHandler.class,
        ProtectedController.class
    })
    static class HttpApplication {
        @Bean
        GoogleLoginService googleLoginService() {
            return mock(GoogleLoginService.class);
        }

        @Bean
        LoginSessionService loginSessionService() {
            return mock(LoginSessionService.class);
        }
    }

    @RestController
    static class ProtectedController {
        @GetMapping("/protected-google-test")
        String protectedResource() {
            return "protected";
        }
    }
}
