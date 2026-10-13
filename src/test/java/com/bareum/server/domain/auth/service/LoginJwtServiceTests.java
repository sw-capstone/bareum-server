package com.bareum.server.domain.auth.service;

import static org.junit.jupiter.api.Assertions.*;
import com.bareum.server.domain.auth.config.LoginJwtProperties;
import com.bareum.server.domain.auth.exception.AuthException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LoginJwtServiceTests {
    private static final Instant NOW = Instant.parse("2026-10-13T00:00:00Z");
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private final UUID family = UUID.randomUUID();
    private final LoginJwtService service = new LoginJwtService(new LoginJwtProperties(KEY), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void signedTokensSeparatePurposesAndContainOnlyIdentityAndLifetimeClaims() throws Exception {
        String access = service.issue("access", 7, family, NOW, NOW.plusSeconds(900));
        String refresh = service.issue("refresh", 7, family, NOW, NOW.plusSeconds(28800));
        assertEquals(7, service.verify(access, "access").orElseThrow().memberId());
        assertEquals(family, service.verify(refresh, "refresh").orElseThrow().family());
        assertTrue(service.verify(access, "refresh").isEmpty());
        assertTrue(service.verify(refresh, "access").isEmpty());
        assertTrue(service.verify(new LoginTokenGenerator().generate(), "access").isEmpty());
        assertFalse(SignedJWT.parse(access).getJWTClaimsSet().getClaims().containsKey("email"));
        assertNotEquals(access, service.issue("access", 7, family, NOW, NOW.plusSeconds(900)));
    }

    @Test
    void expiryBoundaryTamperingAndDifferentSigningKeyAreRejected() {
        String token = service.issue("access", 7, family, NOW, NOW.plusSeconds(900));
        var expired = new LoginJwtService(new LoginJwtProperties(KEY), Clock.fixed(NOW.plusSeconds(900), ZoneOffset.UTC));
        assertTrue(expired.verify(token, "access").isEmpty());
        int position = token.lastIndexOf('.') + 1;
        String changed = token.substring(0, position) + (token.charAt(position) == 'A' ? 'B' : 'A') + token.substring(position + 1);
        assertTrue(service.verify(changed, "access").isEmpty());
        byte[] other = new byte[32]; other[0] = 1;
        var foreign = new LoginJwtService(new LoginJwtProperties(Base64.getEncoder().encodeToString(other)), Clock.fixed(NOW, ZoneOffset.UTC));
        assertTrue(foreign.verify(token, "access").isEmpty());
    }

    @Test
    void missingAndInvalidKeysFailClosedWithoutIncludingTheirValue() {
        for (String key : new String[]{"", "invalid", Base64.getEncoder().encodeToString(new byte[16])}) {
            var unavailable = new LoginJwtService(new LoginJwtProperties(key), Clock.fixed(NOW, ZoneOffset.UTC));
            assertThrows(AuthException.class, () -> unavailable.issue("access", 7, family, NOW, NOW.plusSeconds(900)));
            assertFalse(new LoginJwtProperties(key).toString().contains("signingKey="));
        }
    }

    @Test
    void correctlySignedTokensWithWrongIssuerAudienceFutureTimeOrFamilyAreRejected() throws Exception {
        var original = SignedJWT.parse(service.issue("access", 7, family, NOW, NOW.plusSeconds(900))).getJWTClaimsSet();
        for (var invalid : new JWTClaimsSet[]{
                new JWTClaimsSet.Builder(original).issuer("other").build(),
                new JWTClaimsSet.Builder(original).audience("other").build(),
                new JWTClaimsSet.Builder(original).issueTime(Date.from(NOW.plusSeconds(1))).build(),
                new JWTClaimsSet.Builder(original).notBeforeTime(Date.from(NOW.plusSeconds(1))).build(),
                new JWTClaimsSet.Builder(original).claim("family", "invalid").build(),
                new JWTClaimsSet.Builder(original).subject("0").build()}) {
            var signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(), invalid);
            signed.sign(new MACSigner(Base64.getDecoder().decode(KEY)));
            assertTrue(service.verify(signed.serialize(), "access").isEmpty());
        }
    }
}
