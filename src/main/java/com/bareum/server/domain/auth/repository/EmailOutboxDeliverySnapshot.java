package com.bareum.server.domain.auth.repository;

import java.time.Instant;
import java.util.UUID;

import com.bareum.server.domain.auth.enums.EmailOutboxState;

public record EmailOutboxDeliverySnapshot(
	UUID requestId,
	EmailOutboxState state,
	Instant expiresAt,
	boolean invalid
) {
}
