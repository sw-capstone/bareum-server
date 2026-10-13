package com.bareum.server.domain.auth.controller;

import com.bareum.server.domain.auth.dto.request.EmailLoginRequest;
import com.bareum.server.domain.auth.dto.response.EmailLoginResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;

@Tag(name = "Auth", description = "회원가입 및 인증")
public interface EmailLoginControllerDocs {
    @Operation(
            summary = "이메일 로그인",
            description =
                    """
                    이메일 대소문자를 구분하지 않고 자격증명을 확인합니다. 비밀번호는 변형하지 않습니다.
                    Access Token은 Authorization: Bearer로 사용하며 기본 900초입니다.
                    Refresh Token은 bareum_refresh HttpOnly 쿠키로만 전달하며 기본 28800초입니다.
                    JWT Access·Refresh를 발급하며, 재로그인은 이전 Refresh를 교체하고 이전 Access는 원래 만료까지 유지합니다. keepSignedIn=true는 지원하지 않습니다.
                    토큰 갱신은 POST /api/v1/auth/token/renew로 연결합니다. 로그아웃 API는 별도 구현 범위입니다.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "토큰과 갱신 쿠키 발급",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = EmailLoginResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "입력 검증 오류",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/BadRequestProblemDetail")))
    @ApiResponse(
            responseCode = "401",
            description = "잘못된 자격증명",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
    @ApiResponse(
            responseCode = "403",
            description = "Google 로그인 필요 또는 제한 계정",
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
    ResponseEntity<EmailLoginResponse> login(EmailLoginRequest request);
}
