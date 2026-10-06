package com.bareum.server.domain.auth.exception;

/** 번호 불일치 때 실패 횟수를 커밋하기 위해 다른 업무 예외와 구분합니다. */
public class EmailVerificationCodeMismatchException extends AuthException {

    public EmailVerificationCodeMismatchException() {
        super(AuthErrorCode.EMAIL_VERIFICATION_CODE_MISMATCH);
    }
}
