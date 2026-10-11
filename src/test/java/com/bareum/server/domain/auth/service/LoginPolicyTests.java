package com.bareum.server.domain.auth.service;

import static org.junit.jupiter.api.Assertions.*;

import com.bareum.server.domain.auth.config.LoginProperties;
import com.bareum.server.domain.auth.dto.request.EmailLoginRequest;
import com.bareum.server.domain.auth.dto.response.EmailLoginResponse;

import jakarta.validation.Validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

class LoginPolicyTests {
    @Test
    void tokensHaveIndependentEntropyAndPurposeBoundHashes() {
        var generator = new LoginTokenGenerator();
        var seen = new HashSet<String>();
        for (int i = 0; i < 100; i++) {
            String raw = generator.generate();
            assertTrue(generator.validFormat(raw));
            assertTrue(seen.add(raw));
            assertNotEquals(generator.hash("access", raw), generator.hash("refresh", raw));
            assertEquals(64, generator.hash("access", raw).length());
        }
        assertFalse(generator.validFormat(null));
        assertFalse(generator.validFormat(" ".repeat(43)));
        assertFalse(generator.validFormat("a".repeat(44)));
    }

    @Test
    void cookieAndLifetimeConfigurationFailsClosedAndCopiesOrigins() {
        var origins = new ArrayList<>(List.of("https://web.example.invalid"));
        var properties = new LoginProperties(900, 28800, true, "None", origins, 10, 60);
        origins.clear();
        assertEquals(1, properties.allowedOrigins().size());
        assertThrows(
                UnsupportedOperationException.class, () -> properties.allowedOrigins().clear());
        assertThrows(
                IllegalArgumentException.class,
                () -> new LoginProperties(900, 28800, false, "None", List.of(), 10, 60));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LoginProperties(901, 28800, true, "None", List.of(), 10, 60));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LoginProperties(900, 28801, true, "None", List.of(), 10, 60));
        assertDoesNotThrow(() -> new LoginProperties(900, 28800, false, "Lax", List.of(), 10, 60));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "*",
                "https://*.example.invalid",
                "null",
                "https://example.invalid/",
                "https://name@example.invalid",
                "https://example.invalid?x=1",
                "file://example.invalid"
            })
    void onlyExactHttpOriginsAreAccepted(String origin) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new LoginProperties(900, 28800, true, "None", List.of(origin), 10, 60));
    }

    @Test
    void legacyPasswordsArePreservedButUnsupportedRememberMeIsRejected() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(
                    validator
                            .validate(new EmailLoginRequest("a@example.invalid", " legacy ", null))
                            .isEmpty());
            assertTrue(
                    validator
                            .validate(new EmailLoginRequest("a@example.invalid", "legacy", false))
                            .isEmpty());
            assertFalse(
                    validator
                            .validate(new EmailLoginRequest("a@example.invalid", "legacy", true))
                            .isEmpty());
            assertFalse(
                    validator
                            .validate(new EmailLoginRequest("a@example.invalid", "", null))
                            .isEmpty());
        }
        assertEquals(
                "EmailLoginRequest[redacted]",
                new EmailLoginRequest("a@example.invalid", "private", null).toString());
        assertEquals(
                "EmailLoginResponse[redacted]",
                new EmailLoginResponse("private", "Bearer", 900, 28800).toString());
        assertEquals(
                "ValidatedMember[redacted]",
                new EmailLoginCredentialService.ValidatedMember(1L, "private").toString());
    }
}
