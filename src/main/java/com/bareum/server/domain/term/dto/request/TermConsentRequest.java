package com.bareum.server.domain.term.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record TermConsentRequest(
        @Schema(description = "약관 목록에서 받은 ID", example = "1")
        @NotNull @Positive Long termId,
        @Schema(description = "동의 화면에 표시한 약관 버전", example = "1.0")
        @NotBlank @Size(max = 30) String version,
        @Schema(description = "동의 여부. 필수 약관은 true여야 합니다.", example = "true")
        @NotNull Boolean agreed
) {
}
