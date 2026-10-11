package com.bareum.server.domain.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GoogleLoginRequest(
        @NotBlank(message = "Google 인증 정보가 필요합니다.")
                @Size(max = 8192, message = "Google 인증 정보의 길이가 너무 깁니다.")
                @Schema(
                        description =
                                "Google Identity Services가 발급한 ID Token. Access Token은 사용하지 않음")
                String idToken) {
    @Override
    public String toString() {
        return "GoogleLoginRequest[redacted]";
    }
}
