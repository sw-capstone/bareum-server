package com.bareum.server.domain.term.dto.request;

import com.bareum.server.domain.term.enums.TermCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "프론트의 고정 약관 항목별 동의 여부. ID·버전은 서버에서 연결합니다.")
public record SignupAgreementsRequest(
        @Schema(description = "만 14세 이상 가입 동의", example = "true") @NotNull Boolean age,
        @Schema(description = "서비스 이용약관 동의", example = "true") @NotNull Boolean service,
        @Schema(description = "개인정보 처리방침 동의", example = "true") @NotNull Boolean privacy,
        @Schema(description = "마케팅 정보 수신 동의. false도 가입 가능", example = "false") @NotNull Boolean marketing
) {
    public Boolean agreed(TermCode code) {
        return switch (code) {
            case AGE_OVER_14 -> age;
            case TERMS_OF_SERVICE -> service;
            case PRIVACY_POLICY -> privacy;
            case MARKETING_CONSENT -> marketing;
        };
    }
}
