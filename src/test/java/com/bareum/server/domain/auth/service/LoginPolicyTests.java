package com.bareum.server.domain.auth.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.bareum.server.domain.auth.config.LoginProperties;
import com.bareum.server.domain.auth.dto.request.EmailLoginRequest;
import com.bareum.server.domain.auth.dto.response.EmailLoginResponse;

import jakarta.validation.Validation;

class LoginPolicyTests {
	@Test
	void tokenHashesArePurposeBoundAndDoNotStoreTheirInput() {
		var generator = new LoginTokenGenerator();
		String raw = "test-only-token-input";
		assertNotEquals(generator.hash("access", raw), generator.hash("refresh", raw));
		assertNotEquals(raw, generator.hash("access", raw));
		assertEquals(64, generator.hash("access", raw).length());
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
