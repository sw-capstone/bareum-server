package com.bareum.server.domain.auth.exception;

import com.bareum.server.global.exception.code.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.slf4j.event.Level;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements BaseErrorCode {

    EMAIL_ALREADY_REGISTERED(
            HttpStatus.CONFLICT,
            "AUTH-0001",
            "이미 가입된 이메일입니다."
    ),
    EMAIL_NOT_REGISTERED(
            HttpStatus.NOT_FOUND,
            "AUTH-0002",
            "가입되어 있지 않은 이메일입니다."
    ),
    PASSWORD_RESET_NOT_ALLOWED(
            HttpStatus.BAD_REQUEST,
            "AUTH-0003",
            "이메일·비밀번호로 로그인할 수 있는 계정만 비밀번호를 재설정할 수 있습니다."
    ),
    EMAIL_SEND_LIMIT_EXCEEDED(
            HttpStatus.TOO_MANY_REQUESTS,
            "AUTH-0004",
            "인증 메일 발송 제한을 초과했습니다. 잠시 후 다시 시도해 주세요."
    ),
    EMAIL_SEND_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "AUTH-0005",
            "인증 메일을 발송하지 못했습니다. 잠시 후 다시 시도해 주세요."
    ),
    EMAIL_VERIFICATION_NOT_CONFIGURED(
            HttpStatus.SERVICE_UNAVAILABLE,
            "AUTH-0006",
            "이메일 인증 서비스가 아직 준비되지 않았습니다. 잠시 후 다시 시도해 주세요."
    ),
    EMAIL_VERIFICATION_INVALID(
            HttpStatus.BAD_REQUEST,
            "AUTH-0007",
            "유효하지 않은 이메일 인증 요청입니다."
    ),
    EMAIL_VERIFICATION_EXPIRED(HttpStatus.BAD_REQUEST, "AUTH-0008", "이메일 인증번호가 만료되었습니다."),
    EMAIL_VERIFICATION_CODE_MISMATCH(HttpStatus.BAD_REQUEST, "AUTH-0009", "이메일 인증번호가 일치하지 않습니다."),
    EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "AUTH-0010", "이메일 인증번호 확인 횟수를 초과했습니다."),
    EMAIL_VERIFICATION_ALREADY_VERIFIED(HttpStatus.CONFLICT, "AUTH-0011", "이미 이메일 인증이 완료된 요청입니다."),
    INVALID_SIGNUP_PASSWORD(HttpStatus.BAD_REQUEST, "AUTH-0012", "비밀번호 조건을 다시 확인해 주세요."),
    EMAIL_VERIFICATION_TOKEN_EXPIRED(HttpStatus.BAD_REQUEST, "AUTH-0013", "이메일 인증 완료 증명이 만료되었습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    @Override
    public Level getLogLevel() {
        return httpStatus.is5xxServerError() ? Level.ERROR : Level.INFO;
    }
}
