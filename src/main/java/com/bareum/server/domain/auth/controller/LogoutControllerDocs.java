package com.bareum.server.domain.auth.controller;

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
public interface LogoutControllerDocs {
    @Operation(summary = "로그아웃", description = """
            AUTH-003. bareum_refresh 쿠키로 현재 로그인을 폐기하고 Refresh·CSRF 쿠키를 삭제합니다.
            본문·Access Token 없이 credentials: include와 X-CSRF-TOKEN 헤더로 호출합니다.
            CSRF 헤더는 GET /api/v1/auth/csrf 응답의 마스킹된 token을 사용합니다.
            이메일·Google 가입 방식과 회원 상태에 관계없이 동일하게 처리합니다.
            성공은 본문 없는 204입니다. 반복 요청·누락·알 수 없는·만료된 Refresh Token도
            유효한 CSRF 검증 후 204로 처리합니다. 중복 Refresh 쿠키는 401로 거부합니다.
            같은 로그인에서 갱신 전에 사용한 Refresh Token으로도 해당 로그인을 폐기하되,
            이전 로그인 토큰으로 새 로그인을 폐기하지 않습니다. 만료된 Authorization은 무시합니다.
            프론트는 성공 후 토큰·사용자·문서·분석 상태를 비우고 상태 조회를 중지합니다.
            갱신·로그아웃을 직렬화하고 다음 쿠키 인증 요청 전 CSRF 토큰을 다시 조회합니다.
            """, parameters = {
                @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true,
                        description = "CSRF 조회 응답의 마스킹된 token"),
                @Parameter(name = "bareum_refresh", in = ParameterIn.COOKIE,
                        description = "폐기할 로그인 쿠키. 누락 시에도 CSRF 검증 필요")
            })
    @ApiResponse(responseCode = "204", description = "로그인 폐기·쿠키 삭제. 본문 없음, Cache-Control: no-store",
            content = @Content)
    @ApiResponse(responseCode = "401", description = "AUTH-0021 중복 Refresh 쿠키",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
    @ApiResponse(responseCode = "403", description = "AUTH-0018 CSRF 검증 실패",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
    @ApiResponse(responseCode = "503", description = "AUTH-0019 인증 저장소 일시 장애",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
    ResponseEntity<Void> logout(@Parameter(hidden = true) HttpServletRequest request);
}
