package com.bareum.server.domain.auth.service;

import com.bareum.server.domain.auth.dto.request.GoogleSignupRequest;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class GoogleSignupService {
    private final GoogleSignupTransactionService transaction;

    public GoogleSignupService(GoogleSignupTransactionService transaction) {
        this.transaction = transaction;
    }

    public LoginSessionService.IssuedLogin signup(GoogleSignupRequest request) {
        try {
            return transaction.signup(request);
        } catch (DataIntegrityViolationException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause
                        instanceof org.hibernate.exception.ConstraintViolationException violation) {
                    if ("member_email_key".equals(violation.getConstraintName()))
                        throw new AuthException(AuthErrorCode.EMAIL_ALREADY_REGISTERED);
                    if ("member_auth_provider_provider_user_id_key"
                            .equals(violation.getConstraintName()))
                        throw new AuthException(AuthErrorCode.INVALID_GOOGLE_SIGNUP_PROOF);
                }
            }
            throw new AuthException(AuthErrorCode.GOOGLE_SIGNUP_UNAVAILABLE);
        } catch (DataAccessException | TransactionException failure) {
            throw new AuthException(AuthErrorCode.GOOGLE_SIGNUP_UNAVAILABLE);
        }
    }
}
