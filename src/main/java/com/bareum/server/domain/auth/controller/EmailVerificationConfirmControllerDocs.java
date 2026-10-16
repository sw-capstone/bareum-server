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
            가입·재설정 완료 증명은 기본 10분간 유효합니다. 최종 사용 시 이메일·목적·유효기간·미사용 상태를 다시 검증합니다.
            기본 5회 실패 시 해당 이메일·목적의 번호 확인과 새 발송을 15분간 제한합니다. 기존 로그인 계정은 정지하지 않습니다.
            제한 시간이 지나면 새 인증번호를 요청해야 하며, 재발송 자체로 실패 횟수를 초기화하지 않습니다.
            완료 증명을 잃으면 재발송 후 다시 인증합니다. 이전 증명은 재발송 접수와 함께 무효화됩니다.
            수치·복구 방식은 임시 구현 기준이며 환경변수로 기간·횟수를 변경할 수 있습니다.
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
