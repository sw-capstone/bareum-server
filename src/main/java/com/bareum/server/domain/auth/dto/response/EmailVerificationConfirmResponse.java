package com.bareum.server.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record EmailVerificationConfirmResponse(
        @Schema(description = "최종 가입·재설정에 제출할 인증 완료 증명. 로그에 기록하지 않습니다.")
        String verificationToken,
        @Schema(description = "인증 완료 증명의 유효기간(초)", example = "600")
        long expiresIn
) {
    @Override
    public String toString() {
        return "EmailVerificationConfirmResponse[verificationToken=REDACTED, expiresIn=" + expiresIn + "]";
    }
}
