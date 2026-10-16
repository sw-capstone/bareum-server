package com.bareum.server.domain.auth.controller;

import com.bareum.server.domain.auth.dto.request.GoogleSignupRequest;
import com.bareum.server.domain.auth.dto.response.EmailLoginResponse;

import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;

@Tag(name = "Auth", description = "회원가입 및 인증")
public interface GoogleSignupControllerDocs {
    @Operation(
            summary = "Google 가입 완료",
            description =
                    "AUTH-011. AUTH-004의 가입용 증명과 약관 동의를 검증하고 회원·Google 인증수단·동의 이력·로그인 세션을 함께 저장합니다."
                        + " 이메일·이름은 검증된 증명을 사용합니다. GET /api/v1/auth/csrf의 쿠키와 X-CSRF-TOKEN 헤더를 반드시"
                        + " 전송합니다. 중복 가입·자동 계정 연결은 허용하지 않습니다.",
            parameters =
                    @Parameter(
                            name = "X-CSRF-TOKEN",
                            in = io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER,
                            required = true,
                            description = "GET /api/v1/auth/csrf 응답의 token"))
    @ApiResponse(
            responseCode = "201",
            description = "가입 완료 및 자동 로그인. 갱신 정보는 HttpOnly 쿠키로 전달",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = EmailLoginResponse.class),
                            examples =
                                    @ExampleObject(
                                            value =
                                                    "{\"accessToken\":\"<accessToken>\",\"tokenType\":\"Bearer\",\"expiresIn\":900,\"sessionExpiresIn\":28800}")))
    @ApiResponse(
            responseCode = "400",
            description = "입력 검증 또는 필수 약관 미동의",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/BadRequestProblemDetail")))
    @ApiResponse(
            responseCode = "401",
            description = "잘못됨·만료됨·교체됨·이미 사용된 가입 증명",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
    @ApiResponse(
            responseCode = "403",
            description = "CSRF 검증 실패",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
    @ApiResponse(
            responseCode = "409",
            description = "가입 방식과 관계없이 동일 이메일 가입 차단",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
    @ApiResponse(
            responseCode = "500",
            description = "약관 데이터 무결성 오류 또는 예상하지 못한 저장 오류",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
    @ApiResponse(
            responseCode = "503",
            description = "가입 저장소 장애",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
    ResponseEntity<EmailLoginResponse> signup(GoogleSignupRequest request);
}
