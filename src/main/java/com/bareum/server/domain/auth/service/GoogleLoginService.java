package com.bareum.server.domain.auth.service;

import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class GoogleLoginService {
    private final GoogleIdTokenVerifier verifier;
    private final GoogleLoginTransactionService accounts;
    private final LoginAttemptLimiter limiter;

    public GoogleLoginService(
            GoogleIdTokenVerifier verifier,
            GoogleLoginTransactionService accounts,
            LoginAttemptLimiter limiter) {
        this.verifier = verifier;
        this.accounts = accounts;
        this.limiter = limiter;
    }

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
