package com.bareum.server.domain.auth.dto.request;

import com.bareum.server.domain.term.dto.request.SignupAgreementsRequest;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Schema(description = "AUTH-004의 가입용 증명과 서비스 약관 동의")
public record GoogleSignupRequest(
        @Schema(description = "AUTH-004의 signupToken. 일반 로그인 토큰으로 사용할 수 없습니다.")
                @NotBlank
                @Pattern(regexp = "gs_[A-Za-z0-9_-]{43}")
                String signupToken,
        @Valid @NotNull SignupAgreementsRequest agreements) {
    @Override
    public String toString() {
        return "GoogleSignupRequest[redacted]";
    }
}
