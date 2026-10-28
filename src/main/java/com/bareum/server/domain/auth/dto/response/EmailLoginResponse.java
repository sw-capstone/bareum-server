package com.bareum.server.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record EmailLoginResponse(
	@Schema(description = "보호 API의 Authorization: Bearer 값. 프론트 메모리에서 관리") String accessToken,
	@Schema(allowableValues = {
		"Bearer"
	}) String tokenType,
	@Schema(description = "남은 Access Token 유효시간(초)", example = "900") long expiresIn,
	@Schema(description = "최초 로그인부터 남은 최대 로그인 시간(초)", example = "28800") long sessionExpiresIn
) {
	@Override
	public String toString() {
		return "EmailLoginResponse[redacted]";
	}
}
