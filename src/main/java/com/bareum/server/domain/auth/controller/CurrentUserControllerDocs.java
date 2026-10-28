package com.bareum.server.domain.auth.controller;

import org.springframework.http.ResponseEntity;

import com.bareum.server.domain.auth.dto.response.CurrentUserResponse;
import com.bareum.server.global.security.LoginPrincipal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Auth")
public interface CurrentUserControllerDocs {
	@Operation(
		summary = "현재 사용자 조회",
		description = "Authorization: Bearer 인증으로 이름·이메일을 조회합니다. 쿠키만으로 인증하지 않습니다.",
		security = @SecurityRequirement(name = "bearerAuth"))
	@ApiResponse(
		responseCode = "200",
		description = "현재 사용자",
		content = @Content(
			mediaType = "application/json",
			schema = @Schema(implementation = CurrentUserResponse.class)))
	@ApiResponse(
		responseCode = "401",
		description = "인증 누락·만료·폐기·제한 회원",
		content = @Content(
			mediaType = "application/problem+json",
			schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
	@ApiResponse(
		responseCode = "403",
		description = "접근 거부",
		content = @Content(
			mediaType = "application/problem+json",
			schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
	@ApiResponse(
		responseCode = "503",
		description = "인증 저장소 일시 장애",
		content = @Content(
			mediaType = "application/problem+json",
			schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
	ResponseEntity<CurrentUserResponse> me(
		@io.swagger.v3.oas.annotations.Parameter(hidden = true) LoginPrincipal principal
	);
}
