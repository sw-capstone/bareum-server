package com.bareum.server.domain.auth.service;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.bareum.server.domain.auth.config.LoginProperties;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.repository.LoginAttemptRepository;

@Service
public class LoginAttemptLimiter {
	private final LoginAttemptRepository repository;
	private final LoginProperties properties;
	private final LoginTokenGenerator tokens;
	private final Clock clock;
	private final TransactionTemplate transaction;

	public LoginAttemptLimiter(
		LoginAttemptRepository repository,
		LoginProperties properties,
		LoginTokenGenerator tokens,
		@Qualifier("loginClock") Clock clock,
		PlatformTransactionManager manager
	) {
		this.repository = repository;
		this.properties = properties;
		this.tokens = tokens;
		this.clock = clock;
		transaction = new TransactionTemplate(manager);
		transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@org.springframework.scheduling.annotation.Scheduled(
		fixedDelay = 3600000,
		initialDelay = 3600000)
	public void purgeExpiredWindows() {
		repository.deleteBefore(clock.instant().minusSeconds(86400));
	}

	public void reserve(String email) {
		var now = clock.instant();
		Boolean reserved =
				transaction.execute(
						status -> {
							String normalized = repository.comparisonKey(email);
							String key = tokens.hash("login-limit", normalized);
							return repository.reserve(key, now, properties.requestWindowSeconds(),
									properties.maxRequestsPerWindow());
						});
		if (!Boolean.TRUE.equals(reserved))
			throw new AuthException(AuthErrorCode.LOGIN_REQUEST_LIMIT_EXCEEDED);
	}
}
