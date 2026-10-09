package com.bareum.server.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record EmailVerificationSendResponse(
        @Schema(description = "인증번호 유효시간(초)", example = "300")
        long expiresIn
) {

    public static EmailVerificationSendResponse of(long expiresIn) {
        return new EmailVerificationSendResponse(expiresIn);
    }
}
