package com.bareum.server.domain.auth.controller;

import com.bareum.server.domain.auth.dto.request.EmailVerificationConfirmRequest;
import com.bareum.server.domain.auth.dto.response.EmailVerificationConfirmResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Auth", description = "회원가입 및 인증")
public interface EmailVerificationConfirmControllerDocs {

    @Operation(summary = "이메일 인증 코드 확인", description = """
            이메일·목적의 최신 요청만 확인합니다. 성공하면 인증 완료 증명을 반환하며 회원 생성이나 비밀번호 변경은 수행하지 않습니다.
            가입용 증명은 10분간 유효합니다. 최종 사용 시 이메일·목적·유효기간·미사용 상태를 다시 검증해야 합니다.
            실패 횟수 제한 및 목적별 증명 유효기간 설정이 없으면 확인을 차단합니다.
            이미 완료된 요청에는 새 증명을 발급하지 않습니다. 요청·응답 및 오류 매핑은 팀 검토가 필요한 초안입니다.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "인증 완료 증명 발급",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = EmailVerificationConfirmResponse.class))),
            @ApiResponse(responseCode = "400", description = "입력 오류(COMMON-0002/0003), 유효하지 않은 요청(AUTH-0007), 만료(AUTH-0008), 번호 불일치(AUTH-0009)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = "#/components/schemas/BadRequestProblemDetail"))),
            @ApiResponse(responseCode = "409", description = "이미 인증 완료(AUTH-0011)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = "#/components/schemas/ProblemDetail"))),
            @ApiResponse(responseCode = "429", description = "실패 횟수 초과(AUTH-0010)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = "#/components/schemas/ProblemDetail"))),
            @ApiResponse(responseCode = "503", description = "필수 정책 설정 누락(AUTH-0006)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = "#/components/schemas/ProblemDetail"))),
            @ApiResponse(responseCode = "500", description = "서버 오류(COMMON-0001)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
    })
    ResponseEntity<EmailVerificationConfirmResponse> confirm(EmailVerificationConfirmRequest request);
}
