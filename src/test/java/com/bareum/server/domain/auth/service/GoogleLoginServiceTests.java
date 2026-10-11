package com.bareum.server.domain.auth.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bareum.server.domain.auth.config.GoogleLoginProperties;
import com.bareum.server.domain.auth.exception.*;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

class GoogleLoginServiceTests {
    @Test
    void invalidGoogleTokenCannotReserveRequestsOrReachAccountStore() {
        var verifier = mock(GoogleIdTokenVerifier.class);
        var accounts = mock(GoogleLoginTransactionService.class);
        var limiter = mock(LoginAttemptLimiter.class);
        var service = new GoogleLoginService(verifier, accounts, limiter);
        when(verifier.verify("invalid"))
                .thenThrow(new AuthException(AuthErrorCode.INVALID_GOOGLE_AUTHENTICATION));
        assertThrows(AuthException.class, () -> service.login("invalid"));
        verifyNoInteractions(accounts, limiter);
    }

    @Test
    void validatedIdentityIsRateLimitedBeforeAccountTransaction() {
        var verifier = mock(GoogleIdTokenVerifier.class);
        var accounts = mock(GoogleLoginTransactionService.class);
        var limiter = mock(LoginAttemptLimiter.class);
        var service = new GoogleLoginService(verifier, accounts, limiter);
        var identity = new GoogleIdentity("subject", "member@gmail.com", "회원", null);
        when(verifier.verify("fixture")).thenReturn(identity);
        doThrow(new AuthException(AuthErrorCode.LOGIN_REQUEST_LIMIT_EXCEEDED))
                .when(limiter)
                .reserve(identity.email());
        assertThrows(AuthException.class, () -> service.login("fixture"));
        verifyNoInteractions(accounts);
    }

    @Test
    void databaseFailuresAreSanitizedAndDoNotReturnCredentials() {
        var verifier = mock(GoogleIdTokenVerifier.class);
        var accounts = mock(GoogleLoginTransactionService.class);
        var limiter = mock(LoginAttemptLimiter.class);
        var service = new GoogleLoginService(verifier, accounts, limiter);
        var identity = new GoogleIdentity("subject", "member@gmail.com", "회원", null);
        when(verifier.verify("fixture")).thenReturn(identity);
        for (var cause :
                List.of(
                        new org.springframework.dao.DataAccessResourceFailureException(
                                "sensitive SQL"),
                        new org.springframework.transaction.CannotCreateTransactionException(
                                "sensitive connection"))) {
            when(accounts.resolve(identity)).thenThrow(cause);
            var failure = assertThrows(AuthException.class, () -> service.login("fixture"));
            assertSame(AuthErrorCode.LOGIN_SERVICE_UNAVAILABLE, failure.getBaseErrorCode());
            assertNull(failure.getCause());
            assertFalse(failure.toString().contains("sensitive"));
            reset(accounts);
        }
    }

    @Test
    void configurationDefensivelyCopiesAndBoundsSignupExpiry() {
        var ids = new ArrayList<>(List.of("client"));
        var config = new GoogleLoginProperties(ids, 600);
        ids.clear();
        assertEquals(Set.of("client"), config.allowedClientIds());
        assertThrows(UnsupportedOperationException.class, () -> config.clientIds().clear());
        for (long ttl : new long[] {0, 601})
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new GoogleLoginProperties(List.of("client"), ttl));
    }

    @Test
    void credentialsAreRedactedFromTransferObjects() {
        var tokens = new LoginTokenGenerator();
        String token = "gs_" + tokens.generate();
        var identity = new GoogleIdentity("sensitive-sub", "member@gmail.com", "회원", null);
        var result = new GoogleLoginTransactionService.Result(null, token, Instant.now());
        var response =
                com.bareum.server.domain.auth.dto.response.GoogleLoginResponse.signup(token, 600);
        assertFalse(identity.toString().contains("member@gmail.com"));
        assertFalse(result.toString().contains(token));
        assertFalse(response.toString().contains(token));
    }
}
