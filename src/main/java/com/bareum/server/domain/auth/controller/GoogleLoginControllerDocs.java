package com.bareum.server.domain.auth.controller;

import com.bareum.server.domain.auth.dto.request.GoogleLoginRequest;
import com.bareum.server.domain.auth.dto.response.GoogleLoginResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;

@Tag(name = "Auth", description = "회원가입 및 인증")
public interface GoogleLoginControllerDocs {
    @Operation(
            summary = "Google 로그인",
            description =
                    "AUTH-004. Google ID Token의 서명·발급자·대상 Client ID·만료·이메일 인증을 검증합니다. 기존 회원은 LOGIN과"
                        + " 로그인 토큰, HttpOnly 갱신 쿠키를 반환합니다. 신규 회원은 SIGNUP_REQUIRED와 AUTH-011 전용 가입"
                        + " 증명을 반환합니다. GET /api/v1/auth/csrf에서 받은 쿠키와 X-CSRF-TOKEN 헤더를 반드시 함께"
                        + " 전송합니다. 같은 이메일의 LOCAL 계정을 자동 연결하지 않습니다.",
            parameters =
                    @Parameter(
                            name = "X-CSRF-TOKEN",
                            in = io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER,
                            required = true,
                            description = "GET /api/v1/auth/csrf 응답의 token"))
    @ApiResponse(
            responseCode = "200",
            description = "LOGIN 또는 SIGNUP_REQUIRED",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = GoogleLoginResponse.class),
                            examples = {
                                @ExampleObject(
                                        name = "기존 회원",
                                        value =
                                                "{\"status\":\"LOGIN\",\"accessToken\":\"<accessToken>\",\"tokenType\":\"Bearer\",\"expiresIn\":900,\"sessionExpiresIn\":28800}"),
                                @ExampleObject(
                                        name = "신규 회원",
                                        value =
                                                "{\"status\":\"SIGNUP_REQUIRED\",\"signupToken\":\"<signupToken>\",\"signupExpiresIn\":600}")
                            }))
    @ApiResponse(
            responseCode = "400",
            description = "입력 검증 오류",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/BadRequestProblemDetail")))
    @ApiResponse(
            responseCode = "401",
            description = "잘못된 Google 인증",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
    @ApiResponse(
            responseCode = "403",
            description = "제한 계정·CSRF 실패·추가 이메일 확인 필요",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
    @ApiResponse(
            responseCode = "409",
            description = "기존 이메일 가입 계정",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
    @ApiResponse(
            responseCode = "429",
            description = "로그인 요청 제한",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
    @ApiResponse(
            responseCode = "500",
            description = "저장 실패",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
    @ApiResponse(
            responseCode = "503",
            description = "Client ID 미설정·Google 공개키 조회 또는 인증 저장소 장애",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
    ResponseEntity<GoogleLoginResponse> login(GoogleLoginRequest request);
}
