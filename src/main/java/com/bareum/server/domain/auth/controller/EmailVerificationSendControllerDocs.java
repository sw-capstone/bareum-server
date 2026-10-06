package com.bareum.server.domain.auth.controller;

import com.bareum.server.domain.auth.dto.request.EmailVerificationRequest;
import com.bareum.server.domain.auth.dto.response.EmailVerificationSendResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Auth", description = "회원가입 및 인증")
public interface EmailVerificationSendControllerDocs {

    @Operation(
            summary = "이메일 인증 코드 발송",
            description = """
                    SIGNUP은 이메일·구글 가입 계정의 중복을 확인합니다.
                    PASSWORD_RESET은 이메일·비밀번호로 로그인 가능한 계정만 요청할 수 있습니다.
                    회원가입 인증번호의 유효기간은 5분이며, expiresIn은 발송 처리 후 남은 시간(초)입니다.
                    인증번호 원문은 응답에 포함하지 않으며, 이 요청으로 회원 계정을 생성하지 않습니다.
                    발송 제한 또는 해당 목적의 필수 설정이 누락되면 발송하지 않습니다.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "인증 메일 발송 요청 처리 성공",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = EmailVerificationSendResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "입력값 검증 실패(COMMON-0002), 본문 누락·잘못된 JSON 또는 인증 목적(COMMON-0003), "
                            + "비밀번호 재설정 불가 계정(AUTH-0003)",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/BadRequestProblemDetail"),
                            examples = {
                                    @ExampleObject(ref = "#/components/examples/ValidationFailed"),
                                    @ExampleObject(ref = "#/components/examples/InvalidRequestBody")
                            }
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "비밀번호 재설정을 요청한 이메일이 미가입 상태(AUTH-0002)",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/ProblemDetail")
                    )
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "회원가입을 요청한 이메일이 이미 가입된 상태(AUTH-0001)",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/ProblemDetail")
                    )
            ),
            @ApiResponse(
                    responseCode = "429",
                    description = "인증 메일 발송 제한 초과(AUTH-0004)",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/ProblemDetail")
                    )
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "메일 발송 실패(AUTH-0005) 또는 서버 오류(COMMON-0001)",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/ProblemDetail")
                    )
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "이메일 인증 정책 또는 메일 발송 설정 누락(AUTH-0006)",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/ProblemDetail")
                    )
            )
    })
    ResponseEntity<EmailVerificationSendResponse> send(EmailVerificationRequest request);
}
