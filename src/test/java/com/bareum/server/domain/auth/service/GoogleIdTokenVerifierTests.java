package com.bareum.server.domain.auth.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bareum.server.domain.auth.config.GoogleLoginProperties;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.PlainHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

class GoogleIdTokenVerifierTests {

    private static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");
    private static final String CLIENT = "test-only-client.apps.googleusercontent.com";
    private static final String SUBJECT = "test-only-google-subject";
    private static final String EMAIL = "verified@example.invalid";
    private static final RSAKey KEY = rsaKey();
    private final GoogleLoginProperties properties = properties();
    private final GoogleIdTokenVerifier verifier = verifier(properties);

    private static RSAKey rsaKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-key").generate();
        } catch (JOSEException exception) {
            throw new AssertionError(exception);
        }
    }

    private static GoogleLoginProperties properties() {
        return new GoogleLoginProperties(List.of(CLIENT), 600);
    }

    private static GoogleIdTokenVerifier verifier(GoogleLoginProperties properties) {
        try {
            return new GoogleIdTokenVerifier(
                    properties,
                    NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build(),
                    Clock.fixed(NOW, ZoneOffset.UTC));
        } catch (JOSEException exception) {
            throw new AssertionError(exception);
        }
    }

    private JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder()
                .issuer("https://accounts.google.com")
                .audience(CLIENT)
                .subject(SUBJECT)
                .issueTime(Date.from(NOW.minusSeconds(30)))
                .expirationTime(Date.from(NOW.plusSeconds(300)))
                .claim("email", EMAIL)
                .claim("email_verified", true)
                .claim("name", "테스트 회원");
    }

    private String signed(Consumer<JWTClaimsSet.Builder> edit) throws JOSEException {
        var builder = claims();
        edit.accept(builder);
        var jwt =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(),
                        builder.build());
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }

    private void expect(AuthErrorCode code, String token) {
        AuthException exception = assertThrows(AuthException.class, () -> verifier.verify(token));
        assertSame(code, exception.getBaseErrorCode());
        assertNull(exception.getCause());
        if (token != null && !token.isBlank()) assertFalse(exception.getMessage().contains(token));
    }

    @ParameterizedTest
    @ValueSource(strings = {"accounts.google.com", "https://accounts.google.com"})
    void bothGoogleIssuersAreAcceptedAndOnlyRequiredProfileIsReturned(String issuer)
            throws Exception {
        String token =
                signed(
                        c ->
                                c.issuer(issuer)
                                        .claim(
                                                "picture",
                                                "https://example.invalid/private-profile.png"));
        GoogleIdentity identity = verifier.verify(token);
        assertEquals(SUBJECT, identity.subject());
        assertEquals(EMAIL, identity.email());
        assertEquals("테스트 회원", identity.name());
        assertNull(identity.hostedDomain());
        assertFalse(identity.toString().contains(EMAIL));
        assertFalse(identity.toString().contains(SUBJECT));
        assertFalse(identity.toString().contains("private-profile.png"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "issuer",
                "audience",
                "missing-audience",
                "expiry",
                "expiry-boundary",
                "missing-expiry",
                "future-issued",
                "missing-issued",
                "future-not-before",
                "missing-subject",
                "blank-subject",
                "long-subject",
                "missing-email",
                "long-email",
                "unverified-email",
                "missing-verified",
                "string-verified",
                "bad-name",
                "bad-domain",
                "bad-email",
                "issued-after-expiry",
                "other-authorized-party",
                "multi-audience-without-authorized-party"
            })
    void validSignatureDoesNotBypassInvalidClaims(String invalid) throws Exception {
        String token =
                signed(
                        c -> {
                            switch (invalid) {
                                case "issuer" -> c.issuer("https://attacker.example.invalid");
                                case "audience" -> c.audience("another-app");
                                case "missing-audience" -> c.audience((String) null);
                                case "expiry" -> c.expirationTime(Date.from(NOW.minusSeconds(1)));
                                case "expiry-boundary" -> c.expirationTime(Date.from(NOW));
                                case "missing-expiry" -> c.expirationTime(null);
                                case "future-issued" -> c.issueTime(Date.from(NOW.plusSeconds(1)));
                                case "missing-issued" -> c.issueTime(null);
                                case "future-not-before" ->
                                        c.notBeforeTime(Date.from(NOW.plusSeconds(1)));
                                case "missing-subject" -> c.subject(null);
                                case "blank-subject" -> c.subject(" ");
                                case "long-subject" -> c.subject("s".repeat(256));
                                case "missing-email" -> c.claim("email", null);
                                case "long-email" -> c.claim("email", "e".repeat(256));
                                case "unverified-email" -> c.claim("email_verified", false);
                                case "missing-verified" -> c.claim("email_verified", null);
                                case "string-verified" -> c.claim("email_verified", "true");
                                case "bad-email" -> c.claim("email", "not-an-email");
                                case "issued-after-expiry" ->
                                        c.issueTime(Date.from(NOW.plusSeconds(500)));
                                case "bad-name" -> c.claim("name", Map.of("value", "name"));
                                case "bad-domain" -> c.claim("hd", " ");
                                case "other-authorized-party" -> c.claim("azp", "another-app");
                                case "multi-audience-without-authorized-party" ->
                                        c.audience(List.of(CLIENT, "another-app"));
                                default -> throw new AssertionError(invalid);
                            }
                        });
        expect(AuthErrorCode.INVALID_GOOGLE_AUTHENTICATION, token);
    }

    @Test
    void allowedAuthorizedPartyAndWorkspaceDomainAreAccepted() throws Exception {
        var identity =
                verifier.verify(
                        signed(
                                c ->
                                        c.audience(List.of(CLIENT, "other-app"))
                                                .claim("azp", CLIENT)
                                                .claim("hd", "workspace.example.invalid")));
        assertEquals("workspace.example.invalid", identity.hostedDomain());
    }

    @Test
    void anyExplicitlyAllowedClientCanBeUsed() throws Exception {
        var otherVerifier =
                verifier(new GoogleLoginProperties(List.of("other-client", CLIENT), 600));
        assertEquals(SUBJECT, otherVerifier.verify(signed(c -> {})).subject());
    }

    @Test
    void noSignatureOrDifferentSignerOrAlgorithmIsRejected() throws Exception {
        expect(
                AuthErrorCode.INVALID_GOOGLE_AUTHENTICATION,
                new PlainJWT(new PlainHeader(), claims().build()).serialize());
        var other = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims().build());
        other.sign(new RSASSASigner(rsaKey()));
        expect(AuthErrorCode.INVALID_GOOGLE_AUTHENTICATION, other.serialize());
        var algorithm = new SignedJWT(new JWSHeader(JWSAlgorithm.RS512), claims().build());
        algorithm.sign(new RSASSASigner(KEY));
        expect(AuthErrorCode.INVALID_GOOGLE_AUTHENTICATION, algorithm.serialize());
        var good = signed(c -> {});
        String[] parts = good.split("\\.");
        String alteredPayload =
                com.nimbusds.jose.util.Base64URL.encode("{\"sub\":\"changed\"}").toString();
        expect(
                AuthErrorCode.INVALID_GOOGLE_AUTHENTICATION,
                parts[0] + "." + alteredPayload + "." + parts[2]);
    }

    @Test
    void malformedOrMissingTokenIsRejectedWithoutLeakingIt() {
        expect(AuthErrorCode.INVALID_GOOGLE_AUTHENTICATION, "not-a-jwt");
        expect(AuthErrorCode.INVALID_GOOGLE_AUTHENTICATION, " ");
        expect(AuthErrorCode.INVALID_GOOGLE_AUTHENTICATION, null);
    }

    @Test
    void missingOrPartiallyBlankConfigurationBlocksBeforeDecode() {
        var decoder = mock(NimbusJwtDecoder.class);
        for (var ids : List.of(List.<String>of(), List.of(CLIENT, " "))) {
            var blocked =
                    new GoogleIdTokenVerifier(
                            new GoogleLoginProperties(ids, 600),
                            decoder,
                            Clock.fixed(NOW, ZoneOffset.UTC));
            assertSame(
                    AuthErrorCode.GOOGLE_LOGIN_NOT_CONFIGURED,
                    assertThrows(AuthException.class, () -> blocked.verify("test-token"))
                            .getBaseErrorCode());
        }
        verify(decoder, never()).decode(any());
    }

    @Test
    void keyRetrievalFailureDoesNotCreateIdentityOrExposeUnderlyingException() {
        var decoder = mock(NimbusJwtDecoder.class);
        when(decoder.decode("test-token"))
                .thenThrow(new JwtException("test-only retrieval failure"));
        var unavailable =
                new GoogleIdTokenVerifier(properties, decoder, Clock.fixed(NOW, ZoneOffset.UTC));
        var exception = assertThrows(AuthException.class, () -> unavailable.verify("test-token"));
        assertSame(AuthErrorCode.GOOGLE_AUTHENTICATION_UNAVAILABLE, exception.getBaseErrorCode());
        assertNull(exception.getCause());
        assertFalse(exception.getMessage().contains("retrieval failure"));
    }

    @Test
    void jwkEndpointCachesKeysAndRefreshesWhenSigningKidChanges() throws Exception {
        var server =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var keySet =
                new java.util.concurrent.atomic.AtomicReference<>(
                        new com.nimbusds.jose.jwk.JWKSet(KEY.toPublicJWK()).toString());
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext(
                "/keys",
                exchange -> {
                    requests.incrementAndGet();
                    byte[] body = keySet.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.getResponseHeaders().set("Cache-Control", "public, max-age=300");
                    exchange.sendResponseHeaders(200, body.length);
                    try (var response = exchange.getResponseBody()) {
                        response.write(body);
                    }
                });
        server.start();
        try {
            var decoder =
                    NimbusJwtDecoder.withJwkSetUri(
                                    "http://127.0.0.1:" + server.getAddress().getPort() + "/keys")
                            .build();
            var remoteVerifier =
                    new GoogleIdTokenVerifier(
                            properties, decoder, Clock.fixed(NOW, ZoneOffset.UTC));
            String token = signed(c -> {});
            assertEquals(SUBJECT, remoteVerifier.verify(token).subject());
            int firstRequests = requests.get();
            assertEquals(SUBJECT, remoteVerifier.verify(token).subject());
            assertEquals(firstRequests, requests.get());

            RSAKey rotated = new RSAKeyGenerator(2048).keyID("rotated-test-key").generate();
            keySet.set(new com.nimbusds.jose.jwk.JWKSet(rotated.toPublicJWK()).toString());
            var rotatedToken =
                    new SignedJWT(
                            new JWSHeader.Builder(JWSAlgorithm.RS256)
                                    .keyID(rotated.getKeyID())
                                    .build(),
                            claims().build());
            rotatedToken.sign(new RSASSASigner(rotated));
            assertEquals(SUBJECT, remoteVerifier.verify(rotatedToken.serialize()).subject());
            assertTrue(requests.get() > firstRequests);

            var unknownToken =
                    new SignedJWT(
                            new JWSHeader.Builder(JWSAlgorithm.RS256)
                                    .keyID("unknown-test-key")
                                    .build(),
                            claims().build());
            unknownToken.sign(new RSASSASigner(rotated));
            assertSame(
                    AuthErrorCode.INVALID_GOOGLE_AUTHENTICATION,
                    assertThrows(
                                    AuthException.class,
                                    () -> remoteVerifier.verify(unknownToken.serialize()))
                            .getBaseErrorCode());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void realKeyEndpointFailureReturnsUnavailableWithoutAcceptingToken() throws Exception {
        var server =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/keys",
                exchange -> {
                    exchange.sendResponseHeaders(503, -1);
                    exchange.close();
                });
        server.start();
        try {
            var decoder =
                    NimbusJwtDecoder.withJwkSetUri(
                                    "http://127.0.0.1:" + server.getAddress().getPort() + "/keys")
                            .build();
            var remoteVerifier =
                    new GoogleIdTokenVerifier(
                            properties, decoder, Clock.fixed(NOW, ZoneOffset.UTC));
            String token = signed(c -> {});
            var exception = assertThrows(AuthException.class, () -> remoteVerifier.verify(token));
            assertSame(
                    AuthErrorCode.GOOGLE_AUTHENTICATION_UNAVAILABLE, exception.getBaseErrorCode());
            assertNull(exception.getCause());
            assertFalse(exception.getMessage().contains(token));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void tokenHeaderJwkAddressCannotChooseVerificationKeys() throws Exception {
        var server =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var attackerRequests = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext(
                "/attacker-keys",
                exchange -> {
                    attackerRequests.incrementAndGet();
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                });
        server.start();
        try {
            RSAKey attacker = rsaKey();
            var token =
                    new SignedJWT(
                            new JWSHeader.Builder(JWSAlgorithm.RS256)
                                    .jwkURL(
                                            java.net.URI.create(
                                                    "http://127.0.0.1:"
                                                            + server.getAddress().getPort()
                                                            + "/attacker-keys"))
                                    .jwk(attacker.toPublicJWK())
                                    .build(),
                            claims().build());
            token.sign(new RSASSASigner(attacker));
            expect(AuthErrorCode.INVALID_GOOGLE_AUTHENTICATION, token.serialize());
            assertEquals(0, attackerRequests.get());
        } finally {
            server.stop(0);
        }
    }
}
