package com.bareum.server.domain.auth.dto.request;

import com.bareum.server.domain.term.dto.request.TermConsentRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record EmailSignupRequest(
        @Schema(description = "인증 완료한 이메일", example = "user@example.com")
        @NotBlank @Email @Size(max = 255) String email,
        @Schema(description = "프로필 표시 이름", example = "사용자")
        @NotBlank @Size(max = 255) String name,
        @Schema(description = "최소 12자, 영문·숫자·!@#$%& 각각 포함. 공백 금지",
                accessMode = Schema.AccessMode.WRITE_ONLY)
        @NotBlank @Size(min = 12) String password,
        @Schema(description = "AUTH-006에서 발급한 SIGNUP 목적의 인증 완료 증명",
                accessMode = Schema.AccessMode.WRITE_ONLY)
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String verificationToken,
        @Schema(description = "약관 ID·버전·동의 여부. 필수 약관의 동의가 필요합니다.")
        @NotEmpty List<@NotNull @Valid TermConsentRequest> terms
) {
    @Override
    public String toString() {
        return "EmailSignupRequest[password=REDACTED, verificationToken=REDACTED]";
    }
}
