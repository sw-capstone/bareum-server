package com.bareum.server.domain.auth.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.bareum.server.domain.auth.config.EmailOutboxProperties;
import com.bareum.server.domain.auth.exception.AuthException;

class EmailOutboxCipherTests {
	private final EmailOutboxProperties properties = new EmailOutboxProperties(true, 3, 5, 120, 300, 10, "v1",
			"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
	private final EmailOutboxCipher cipher = new EmailOutboxCipher(properties);
	private final UUID id = UUID.randomUUID();

	@Test
	void authenticatedEncryptionHasUniqueNonceAndRoundTrips() {
		var first = cipher.encrypt(id, 1, "hash", "012345");
		var second = cipher.encrypt(id, 1, "hash", "012345");
		assertFalse(Arrays.equals(first, second));
		assertEquals("012345", cipher.decrypt(id, 1, "hash", "v1", first));
		assertFalse(properties.toString().contains(properties.encryptionKey()));
	}

	@Test
	void modifiedOrSwappedPayloadAndWrongKeyIdAreRejected() {
		var encrypted = cipher.encrypt(id, 1, "hash", "123456");
		assertThrows(IllegalStateException.class, () -> cipher.decrypt(UUID.randomUUID(), 1, "hash", "v1", encrypted));
		assertThrows(IllegalStateException.class, () -> cipher.decrypt(id, 2, "hash", "v1", encrypted));
		assertThrows(IllegalStateException.class, () -> cipher.decrypt(id, 1, "other", "v1", encrypted));
		assertThrows(IllegalStateException.class, () -> cipher.decrypt(id, 1, "hash", "v2", encrypted));
		encrypted[encrypted.length - 1] ^= 1;
		assertThrows(IllegalStateException.class, () -> cipher.decrypt(id, 1, "hash", "v1", encrypted));
	}

	@Test
	void missingOrInvalidKeyFailsClosed() {
		for (String key : new String[] {null, "", "not-base64", "AAAA"}) {
			assertThrows(AuthException.class,
					() -> new EmailOutboxCipher(
							new EmailOutboxProperties(true, 3, 5, 120, 300, 10, "v1", key))
							.requireConfigured());
		}
	}
}
