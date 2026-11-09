package com.bareum.server.domain.auth.service;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;

@lombok.RequiredArgsConstructor
@Service
public class GoogleLoginService {
	private final GoogleIdTokenVerifier verifier;
	private final GoogleLoginTransactionService accounts;
	private final LoginAttemptLimiter limiter;

	public GoogleLoginTransactionService.Result login(String idToken) {
		var identity = verifier.verify(idToken);
		try {
			limiter.reserve(identity.email());
			return accounts.resolve(identity);
		} catch (DataAccessException | TransactionException failure) {
			throw new AuthException(AuthErrorCode.LOGIN_SERVICE_UNAVAILABLE);
		}
	}
}
