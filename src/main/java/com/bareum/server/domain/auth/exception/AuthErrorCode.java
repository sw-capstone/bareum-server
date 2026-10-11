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
    EMAIL_VERIFICATION_TOKEN_EXPIRED(HttpStatus.BAD_REQUEST, "AUTH-0013", "이메일 인증 완료 증명이 만료되었습니다."),
    INVALID_LOGIN_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH-0014", "이메일 또는 비밀번호를 확인해 주세요."),
    GOOGLE_LOGIN_REQUIRED(HttpStatus.FORBIDDEN, "AUTH-0015", "Google 로그인을 이용해 주세요."),
    LOGIN_ACCOUNT_RESTRICTED(HttpStatus.FORBIDDEN, "AUTH-0016", "로그인할 수 없는 계정 상태입니다."),
    INVALID_ACCESS_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH-0017", "유효한 로그인 인증이 필요합니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "AUTH-0018", "접근 권한이 없습니다."),
    LOGIN_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AUTH-0019", "로그인 인증 서비스를 일시적으로 사용할 수 없습니다."),
    LOGIN_REQUEST_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "AUTH-0020", "로그인 요청이 많습니다. 잠시 후 다시 시도해 주세요."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED,"AUTH-0021","로그인이 만료되었거나 유효하지 않습니다. 다시 로그인해 주세요."),
    REFRESH_TOKEN_REUSED(HttpStatus.UNAUTHORIZED,"AUTH-0022","이미 사용된 갱신 정보입니다. 다시 로그인해 주세요."),
    TOKEN_RENEW_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS,"AUTH-0023","토큰 갱신 요청이 많습니다. 잠시 후 다시 시도해 주세요."),
    INVALID_GOOGLE_AUTHENTICATION(HttpStatus.UNAUTHORIZED,"AUTH-0024","유효하지 않은 Google 인증입니다."),
    GOOGLE_LOGIN_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE,"AUTH-0025","Google 로그인 서비스가 아직 준비되지 않았습니다."),
    GOOGLE_AUTHENTICATION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE,"AUTH-0026","Google 인증을 일시적으로 확인할 수 없습니다."),
    EMAIL_LOGIN_REQUIRED(HttpStatus.CONFLICT,"AUTH-0027","이미 이메일로 가입된 계정입니다. 이메일 로그인을 이용해 주세요."),
    GOOGLE_EMAIL_VERIFICATION_REQUIRED(HttpStatus.FORBIDDEN,"AUTH-0028","이 Google 계정의 이메일은 추가 확인이 필요합니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    @Override
    public Level getLogLevel() {
        return httpStatus.is5xxServerError() ? Level.ERROR : Level.INFO;
    }
}
