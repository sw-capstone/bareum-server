package com.bareum.server.domain.auth.support;

import java.time.Duration;
import java.time.Instant;

public final class TokenExpiry {
	private TokenExpiry() {
	}

	public static long remainingSeconds(Instant now, Instant expiresAt) {
		Duration remaining = Duration.between(now, expiresAt);
		return remaining.isPositive() ? remaining.getSeconds() + (remaining.getNano() > 0 ? 1 : 0) : 0;
	}
}
