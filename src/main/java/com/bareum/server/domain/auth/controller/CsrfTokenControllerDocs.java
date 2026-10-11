package com.bareum.server.domain.auth.controller;

import com.bareum.server.domain.auth.dto.response.CsrfTokenResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;

@Tag(name = "Auth", description = "회원가입 및 인증")
public interface CsrfTokenControllerDocs {
    @Operation(
            summary = "CSRF 토큰 조회",
            description =
                    """
                    로그인 토큰 없이 credentials: include로 호출합니다. HttpOnly XSRF-TOKEN 쿠키를 설정하고
                    본문에 마스킹된 token과 headerName=X-CSRF-TOKEN을 반환합니다. 갱신 요청에 해당 헤더와
                    쿠키를 함께 보냅니다. 프론트는 쿠키를 직접 읽지 않습니다. 응답은 캐시하지 않습니다.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "CSRF 토큰 및 쿠키",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CsrfTokenResponse.class)))
    ResponseEntity<CsrfTokenResponse> csrf(@Parameter(hidden = true) CsrfToken csrf);
}
