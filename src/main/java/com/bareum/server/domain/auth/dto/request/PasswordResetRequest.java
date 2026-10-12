package com.bareum.server.domain.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PasswordResetRequest(
        @Schema(description = "등록 이메일", example = "user@example.com")
        @NotBlank @Email @Size(max = 255) String email,
        @Schema(description = "PASSWORD_RESET 목적의 인증 완료 증명", accessMode = Schema.AccessMode.WRITE_ONLY)
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String verificationToken,
        @Schema(description = "회원가입과 동일한 비밀번호 정책: 최소 12자, 영문·숫자·!@#$%& 포함, 공백 금지",
                accessMode = Schema.AccessMode.WRITE_ONLY)
        @NotBlank @Size(min = 12) String password) {
    @Override
    public String toString() {
        return "PasswordResetRequest[verificationToken=REDACTED, password=REDACTED]";
    }
}
