package com.bareum.server.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record EmailSignupResponse(
        @Schema(description = "생성된 회원 ID. 가입 후 별도로 로그인해야 합니다.", example = "1")
        Long memberId
) {
    public static EmailSignupResponse of(Long memberId) {
        return new EmailSignupResponse(memberId);
    }
}
