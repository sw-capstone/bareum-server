package com.bareum.server.global.exception;

import com.bareum.server.global.exception.code.BaseErrorCode;


import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.springframework.util.StringUtils;

/**
 * 의도적으로 발생시키는 비즈니스 예외의 공통 부모 클래스로, 도메인별 예외가 상속
 */
@Getter
public abstract class BusinessException extends RuntimeException {

	private final Domain domain;
	private final BaseErrorCode baseErrorCode;

	protected BusinessException(Domain domain, BaseErrorCode baseErrorCode) {
		this(domain, baseErrorCode, null, null);
	}

	protected BusinessException(Domain domain, BaseErrorCode baseErrorCode, String message) {
		this(domain, baseErrorCode, message, null);
	}

	protected BusinessException(Domain domain, BaseErrorCode baseErrorCode, Throwable cause) {
		this(domain, baseErrorCode, null, cause);
	}

	protected BusinessException(
			Domain domain, BaseErrorCode baseErrorCode, @Nullable String message, @Nullable Throwable cause
	) {
		super(StringUtils.hasText(message) ? message : baseErrorCode.getMessage(), cause);
		this.domain = domain;
		this.baseErrorCode = baseErrorCode;
	}
}
