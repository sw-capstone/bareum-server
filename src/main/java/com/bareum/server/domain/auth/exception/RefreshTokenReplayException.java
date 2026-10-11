package com.bareum.server.domain.auth.exception;

/** Reuse rejection must commit the revocation of the affected login session. */
public class RefreshTokenReplayException extends AuthException {
    public RefreshTokenReplayException() {
        super(AuthErrorCode.REFRESH_TOKEN_REUSED);
    }
}
