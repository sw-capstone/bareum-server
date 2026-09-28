package com.bareum.server.domain.term.exception;

import com.bareum.server.global.exception.code.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.slf4j.event.Level;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum TermErrorCode implements BaseErrorCode {
	INVALID_CONSENT_STATE(HttpStatus.BAD_REQUEST, "TERM-0001", "약관 동의 상태와 동의·철회 시각이 일치하지 않습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;

	@Override
	public Level getLogLevel() {
		return httpStatus.is5xxServerError() ? Level.ERROR : Level.INFO;
	}
}
