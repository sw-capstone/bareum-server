package com.bareum.server.domain.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bareum.server.domain.auth.dto.response.EmailVerificationDeliveryResponse;
import com.bareum.server.domain.auth.repository.EmailOutboxDeliverySnapshot;
import com.bareum.server.domain.auth.repository.EmailOutboxQueryRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class EmailVerificationDeliveryService {
	private final EmailOutboxQueryRepository queries;
	private final Clock emailVerificationClock;

	@Transactional(readOnly = true)
	public Optional<EmailVerificationDeliveryResponse> status(UUID requestId) {
		Instant now = emailVerificationClock.instant();
		return queries.findDelivery(requestId).map(snapshot -> response(snapshot, now));
	}

	private EmailVerificationDeliveryResponse response(EmailOutboxDeliverySnapshot snapshot, Instant now) {
		String state = switch (snapshot.state()) {
			case SENT -> snapshot.invalid() ? "CANCELLED" : "SENT";
			case FAILED, CANCELLED -> snapshot.state().name();
			default -> "PENDING";
		};
		long remaining = 0;
		if ("SENT".equals(state)) {
			Duration duration = Duration.between(now, snapshot.expiresAt());
			if (duration.isPositive()) {
				remaining = duration.getSeconds() + (duration.getNano() > 0 ? 1 : 0);
			}
		}
		return new EmailVerificationDeliveryResponse(snapshot.requestId(), state, remaining);
	}
}
