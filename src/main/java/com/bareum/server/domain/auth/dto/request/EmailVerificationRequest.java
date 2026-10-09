package com.bareum.server.domain.auth.dto.request;

import com.bareum.server.domain.auth.enums.VerificationPurpose;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record EmailVerificationRequest(
        @Schema(description = "인증번호를 받을 이메일", example = "user@example.com")
        @NotBlank
        @Email
        @Size(max = 255)
        String email,

        @Schema(description = "인증 목적: 회원가입 또는 비밀번호 재설정", example = "SIGNUP")
        @NotNull
        VerificationPurpose purpose
) {
}
