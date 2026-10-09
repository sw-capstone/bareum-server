package com.bareum.server.domain.auth.controller;

import com.bareum.server.domain.auth.dto.request.EmailSignupRequest;
import com.bareum.server.domain.auth.dto.response.EmailSignupResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Auth", description = "인증 API")
public interface EmailSignupControllerDocs {

    @Operation(summary = "이메일 회원가입", description = "AUTH-001 구현 초안. SIGNUP 인증 완료 증명과 필수 약관 동의를 확인하고 "
            + "회원·동의 이력 저장과 증명 사용을 함께 처리합니다. 비밀번호는 최소 12자로 영문·숫자·!@#$%& 각각 포함, "
            + "공백 금지입니다. 선택 약관 미동의 또는 미제출도 가입 가능합니다. 가입 후 자동 로그인하지 않습니다. "
            + "약관 ID·버전은 GET /api/v1/terms 조회값을 사용합니다. 약관 데이터가 없거나 같은 코드에 여러 버전이 있으면 "
            + "가입을 차단합니다. 요청·응답·오류와 적용 약관 선택 기준은 팀 검토가 필요합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "가입 완료. 회원 ID만 반환",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = EmailSignupResponse.class))),
            @ApiResponse(responseCode = "400", description = "입력 오류, 인증 증명 무효·만료(AUTH-0007/0013), 비밀번호 조건 위반(AUTH-0012), "
                    + "약관 정보 오류·필수 미동의(TERM-0002/0003)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = "#/components/schemas/BadRequestProblemDetail"))),
            @ApiResponse(responseCode = "409", description = "이미 가입된 이메일(AUTH-0001)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = "#/components/schemas/ProblemDetail"))),
            @ApiResponse(responseCode = "503", description = "가입 약관 데이터 또는 적용 버전 미확정(TERM-0004)",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = "#/components/schemas/ProblemDetail"))),
            @ApiResponse(responseCode = "500", description = "서버 오류",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
    })
    ResponseEntity<EmailSignupResponse> signup(EmailSignupRequest request);
}
