package com.bareum.server.domain.auth.controller;

import com.bareum.server.domain.auth.dto.response.EmailLoginResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;

@Tag(name = "Auth", description = "회원가입 및 인증")
public interface TokenRenewControllerDocs {
    @Operation(
            summary = "로그인 토큰 갱신",
            description =
                    """
                    AUTH-009. 본문·Access Token 없이 bareum_refresh 쿠키와 X-CSRF-TOKEN 헤더로 호출합니다.
                    먼저 GET /api/v1/auth/csrf를 credentials: include로 호출하고 받은 token을 헤더에 넣습니다.
                    갱신 요청에도 credentials: include를 사용합니다. 새 Access Token은 본문, 새 Refresh Token은
                    HttpOnly 쿠키로 전달합니다. 15분 Access 기간은 최초 로그인부터 최대 8시간의 남은 시간으로
                    제한하며 최대 로그인 만료를 연장하지 않습니다. 이전 Access·Refresh Token은 교체됩니다.
                    사용된 Refresh Token을 재사용하면 해당 로그인 토큰을 폐기합니다. 동시 갱신과 자동 재시도는
                    허용하지 않으므로 프론트에서 갱신 요청을 직렬화해야 합니다. 오류는 ProblemDetail로 반환합니다.
                    """,
            parameters = {
                @Parameter(
                        name = "X-CSRF-TOKEN",
                        in = ParameterIn.HEADER,
                        required = true,
                        description = "CSRF 조회 응답의 마스킹된 token"),
                @Parameter(
                        name = "bareum_refresh",
                        in = ParameterIn.COOKIE,
                        required = true,
                        description = "브라우저가 전달하는 HttpOnly Refresh Token")
            })
    @ApiResponse(
            responseCode = "200",
            description = "새 토큰·갱신 쿠키. Cache-Control: no-store",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = EmailLoginResponse.class)))
    @ApiResponse(
            responseCode = "401",
            description = "AUTH-0021 유효하지 않은 갱신 토큰 또는 AUTH-0022 재사용",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
    @ApiResponse(
            responseCode = "403",
            description = "AUTH-0016 제한 회원 또는 AUTH-0018 CSRF 실패",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
    @ApiResponse(
            responseCode = "429",
            description = "AUTH-0023 로그인 세션별 갱신 요청 제한",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
    @ApiResponse(
            responseCode = "503",
            description = "AUTH-0019 인증 저장소 일시 장애",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
    ResponseEntity<EmailLoginResponse> renew(@Parameter(hidden = true) HttpServletRequest request);
}
