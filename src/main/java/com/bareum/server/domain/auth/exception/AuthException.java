package com.bareum.server.domain.auth.exception;

import com.bareum.server.global.exception.BusinessException;
import com.bareum.server.global.exception.Domain;

public class AuthException extends BusinessException {

	public AuthException(AuthErrorCode errorCode) {
		super(Domain.AUTH, errorCode);
	}

	public AuthException(AuthErrorCode errorCode, Throwable cause) {
		super(Domain.AUTH, errorCode, cause);
	}
}
