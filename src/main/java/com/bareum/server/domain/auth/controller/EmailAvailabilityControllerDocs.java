package com.bareum.server.domain.auth.controller;

import com.bareum.server.domain.auth.dto.response.EmailAvailabilityResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;

@Tag(name = "Auth", description = "회원가입 및 인증")
public interface EmailAvailabilityControllerDocs {

    @Operation(
            summary = "이메일 사용 가능 여부 조회",
            description = """
			이메일 가입과 구글 가입 계정을 모두 확인합니다.
			대소문자만 다른 이메일도 중복으로 판단합니다.
			사용 가능하면 available이 true, 중복이면 false입니다.
			사전 조회는 이메일을 예약하지 않으며, 최종 가입 시 중복을 다시 확인합니다.
			"""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "이메일 사용 가능 여부 조회 성공",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(
                                    implementation = EmailAvailabilityResponse.class
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "이메일 파라미터 누락(COMMON-0004) 또는 빈 값·형식·길이 검증 실패(COMMON-0002)",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/BadRequestProblemDetail"),
                            examples = {
                                    @ExampleObject(name = "MissingRequestParameter", ref = "#/components/examples/MissingRequestParameter"),
                                    @ExampleObject(name = "ValidationFailed", ref = "#/components/examples/ValidationFailed")
                            }
                    )
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "서버 오류로 조회 실패",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/ProblemDetail"),
                            examples = @ExampleObject(
                                    ref = "#/components/examples/InternalServerError"
                            )
                    )
            )
    })
    ResponseEntity<EmailAvailabilityResponse> checkAvailability(
            @Parameter(
                    description = "중복 여부를 확인할 이메일",
                    required = true,
                    example = "user@example.com",
                    schema = @Schema(type = "string", format = "email", maxLength = 255)
            )
            @NotBlank
            @Email
            @Size(max = 255)
            String email
    );
}
