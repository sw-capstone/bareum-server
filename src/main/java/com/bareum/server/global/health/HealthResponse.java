package com.bareum.server.global.health;

import io.swagger.v3.oas.annotations.media.Schema;

public record HealthResponse(
	@Schema(description = "서버 응답 상태", example = "ok") String status
) {
}
