package com.bareum.server.domain.auth.controller;

import com.bareum.server.domain.auth.dto.request.PasswordResetRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Auth", description = "인증 API")
public interface PasswordResetControllerDocs {
    @Operation(summary = "비밀번호 재설정", description = "AUTH-010 구현 초안. PASSWORD_RESET 목적의 최신 인증 완료 증명을 검증합니다. "
            + "회원가입과 동일한 비밀번호 정책·해시를 적용하고 비밀번호 변경·증명 사용·해당 회원의 기존 로그인 폐기를 함께 저장합니다. "
            + "이메일 비교는 대소문자를 무시하며 활성 이메일 로그인 계정만 허용합니다. Google 전용 계정은 제외합니다. "
            + "성공 시 204이며 자동 로그인하거나 쿠키를 변경하지 않습니다. 로그인 폐기 범위는 팀 검토가 필요한 임시 정책입니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "재설정 완료. 응답 본문 없음, Cache-Control: no-store"),
        @ApiResponse(responseCode = "400", description = "입력 오류, 인증 증명 무효·만료(AUTH-0007/0013), 비밀번호 조건 위반(AUTH-0012), 재설정 불가 계정(AUTH-0003)",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = "#/components/schemas/BadRequestProblemDetail"))),
        @ApiResponse(responseCode = "404", description = "변경 대상 계정 없음(AUTH-0002)",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = "#/components/schemas/ProblemDetail"))),
        @ApiResponse(responseCode = "500", description = "저장 실패. 비밀번호·증명·세션 변경 전체 롤백",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
    })
    ResponseEntity<Void> reset(PasswordResetRequest request);
}
