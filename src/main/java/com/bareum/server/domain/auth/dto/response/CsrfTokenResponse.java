package com.bareum.server.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record CsrfTokenResponse(
        @Schema(description = "갱신 요청 헤더 이름", example = "X-CSRF-TOKEN") String headerName,
        @Schema(description = "서버 CSRF 쿠키와 함께 전송할 마스킹된 토큰") String token) {
    @Override
    public String toString() {
        return "CsrfTokenResponse[redacted]";
    }
}
