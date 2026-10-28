package com.bareum.server.domain.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;

@ConfigurationProperties(prefix = "auth.email-verification.outbox")
public record EmailOutboxProperties(
	@DefaultValue("true") boolean enabled,
	@DefaultValue("3") int maxAttempts,
	@DefaultValue("5") long retryDelaySeconds,
	@DefaultValue("120") long leaseSeconds,
	@DefaultValue("300") long maxQueueAgeSeconds,
	@DefaultValue("10") int batchSize,
	@DefaultValue("v1") String keyId,
	String encryptionKey
) {
	public void requireConfigured() {
		if (!enabled || maxAttempts < 1 || retryDelaySeconds < 1 || leaseSeconds < 1
				|| maxQueueAgeSeconds < leaseSeconds || batchSize < 1
				|| keyId == null
				|| !keyId.matches("[A-Za-z0-9_-]{1,32}")) {
			throw new AuthException(AuthErrorCode.EMAIL_VERIFICATION_NOT_CONFIGURED);
		}
	}

	@Override
	public String toString() {
		return "EmailOutboxProperties[credentials=REDACTED]";
	}
}
