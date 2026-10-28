package com.bareum.server.domain.auth.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PasswordHashServiceTests {

	private final PasswordHashService service = new PasswordHashService();

	@Test
	void randomSaltProducesDifferentHashesWithMatchingPasswords() {
		String password = String.join("", "Password", "123", "!");
		String first = service.hash(password);
		String second = service.hash(password);
		assertNotEquals(first, second);
		assertTrue(first.startsWith("{argon2id-v1}$argon2id$"));
		assertTrue(first.length() <= 255);
		assertFalse(first.contains(password));
		assertTrue(service.matches(password, first));
		assertTrue(service.matches(password, second));
		assertFalse(service.matches("Password124!", first));
	}

	@Test
	void longPasswordsAreComparedWithoutTruncatingTheirSuffix() {
		String prefix = "Password123!" + "a".repeat(100);
		String hash = service.hash(prefix + "X");
		assertTrue(service.matches(prefix + "X", hash));
		assertFalse(service.matches(prefix + "Y", hash));
	}

	@Test
	void malformedOrUnknownEncodingNeverFallsBackToPlainText() {
		assertFalse(service.matches("Password123!", "Password123!"));
		assertFalse(service.matches("Password123!", "{unknown}abc"));
		assertFalse(service.matches("Password123!", "{pbkdf2-sha256-v1}broken"));
		assertFalse(service.matches(null, "encoded"));
		assertFalse(service.matches("", "encoded"));
		assertFalse(service.matches("Password123!", null));
		assertThrows(NullPointerException.class, () -> service.hash(null));
	}

	@Test
	void acceptsExistingArgon2idAndPreviouslyStoredPbkdf2WithoutCreatingNewPbkdf2Hashes() {
		String password = String.join("", "Password", "123", "!");
		var argon = org.springframework.security.crypto.argon2.Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
		assertTrue(service.matches(password, argon.encode(password)));
		var previous = new org.springframework.security.crypto.password.Pbkdf2PasswordEncoder("", 16, 600_000,
				org.springframework.security.crypto.password.Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
		String legacy = "{pbkdf2-sha256-v1}" + previous.encode(password);
		assertTrue(service.matches(password, legacy));
		assertFalse(service.matches(password + "wrong", legacy));
		assertTrue(service.hash(password).startsWith("{argon2id-v1}$argon2id$"));
	}
}
