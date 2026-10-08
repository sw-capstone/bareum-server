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
public interface EmailVerificationResendControllerDocs {

    @Operation(
            summary = "이메일 인증 코드 재발송",
            description = """
                    이메일·목적에 해당하는 최신 미인증 요청의 번호를 교체합니다.
                    만료된 번호도 다시 발송할 수 있으며 검증 실패 횟수는 초기화하지 않습니다.
                    회원가입 인증번호 유효기간은 재발송 요청 시점부터 5분입니다.
                    expiresIn은 발송 처리 후 남은 시간(초)이며 인증번호 원문은 반환하지 않습니다.
                    최초 발송과 같은 발송 제한을 적용합니다. 필수 설정 누락 시 발송하지 않습니다.
                    인증 완료 후 재발송 정책이 미정이므로 완료 증명이 있는 요청은 변경하지 않고 503을 반환합니다.
                    요청·응답·오류 매핑과 인증 요청 식별 방식은 팀 검토가 필요한 구현 초안입니다.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "인증 메일 재발송 처리 성공",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = EmailVerificationSendResponse.class))),
            @ApiResponse(responseCode = "400", description = "입력 검증 실패(COMMON-0002), 잘못된 본문(COMMON-0003), "
                    + "재설정 불가 계정(AUTH-0003), 인증 요청 없음·미발송·이미 사용된 요청(AUTH-0007)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/BadRequestProblemDetail"),
                            examples = {
                                    @ExampleObject(ref = "#/components/examples/ValidationFailed"),
                                    @ExampleObject(ref = "#/components/examples/InvalidRequestBody")
                            })),
            @ApiResponse(responseCode = "404", description = "비밀번호 재설정 이메일이 미가입 상태(AUTH-0002)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/ProblemDetail"))),
            @ApiResponse(responseCode = "409", description = "회원가입 이메일이 이미 가입된 상태(AUTH-0001)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/ProblemDetail"))),
            @ApiResponse(responseCode = "429", description = "인증 메일 발송 제한 초과(AUTH-0004)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/ProblemDetail"))),
            @ApiResponse(responseCode = "500", description = "메일 발송 실패·발송 중 만료·동일 번호 생성(AUTH-0005) 또는 서버 오류",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/ProblemDetail"))),
            @ApiResponse(responseCode = "503", description = "필수 설정 누락 또는 인증 완료 후 재발송 정책 미확정(AUTH-0006)",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
    })
    ResponseEntity<EmailVerificationSendResponse> resend(EmailVerificationRequest request);
}
