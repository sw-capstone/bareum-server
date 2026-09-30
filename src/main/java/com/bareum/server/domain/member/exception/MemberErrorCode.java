package com.bareum.server.domain.member.exception;

import com.bareum.server.global.exception.code.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.slf4j.event.Level;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum MemberErrorCode implements BaseErrorCode {
	PASSWORD_HASH_REQUIRED(HttpStatus.BAD_REQUEST, "MEMBER-0001", "로컬 가입에는 비밀번호가 필요합니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;

	@Override
	public Level getLogLevel() {
		return httpStatus.is5xxServerError() ? Level.ERROR : Level.INFO;
	}
}
