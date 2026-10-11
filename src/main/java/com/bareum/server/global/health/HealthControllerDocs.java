package com.bareum.server.global.health;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Health", description = "서버 상태 확인")
public interface HealthControllerDocs {

	@Operation(
			summary = "서버 응답 상태 확인",
			description = "Spring 서버가 HTTP 요청에 응답하는지 확인합니다. DB와 AI 서버의 상태는 검사하지 않습니다."
	)
	@ApiResponse(responseCode = "200", description = "서버 응답 가능")
	ResponseEntity<HealthResponse> health();
}
