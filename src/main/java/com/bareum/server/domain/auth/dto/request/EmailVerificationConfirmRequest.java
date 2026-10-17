package com.bareum.server.domain.auth.dto.request;

import com.bareum.server.domain.auth.enums.VerificationPurpose;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record EmailVerificationConfirmRequest(
        @Schema(description = "인증 요청 이메일", example = "user@example.com")
        @NotBlank @Email @Size(max = 255) String email,
        @Schema(description = "인증 목적", example = "SIGNUP")
        @NotNull VerificationPurpose purpose,
        @Schema(description = "메일로 받은 6자리 숫자 인증번호")
        @NotBlank @Pattern(regexp = "[0-9]{6}") String code
) {
    @Override
    public String toString() {
        return "EmailVerificationConfirmRequest[code=REDACTED]";
    }
}
