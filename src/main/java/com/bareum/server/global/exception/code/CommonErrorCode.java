package com.bareum.server.global.exception.code;


import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements BaseErrorCode {

	INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON-0001", "서버 오류가 발생했습니다. 관리자에게 문의해주세요."),
	VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "COMMON-0002", "입력값을 확인해주세요."),
	INVALID_REQUEST_BODY(HttpStatus.BAD_REQUEST, "COMMON-0003", "요청 본문의 형식과 필수 내용을 확인해주세요."),
	MISSING_REQUEST_PARAMETER(HttpStatus.BAD_REQUEST, "COMMON-0004", "필수 요청 값이 누락되었습니다."),
	INVALID_PARAMETER_TYPE(HttpStatus.BAD_REQUEST, "COMMON-0005", "요청 값의 형식이 올바르지 않습니다."),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "COMMON-0006", "지원하지 않는 HTTP 메서드입니다."),
	UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "COMMON-0007", "지원하지 않는 요청 데이터 형식입니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
