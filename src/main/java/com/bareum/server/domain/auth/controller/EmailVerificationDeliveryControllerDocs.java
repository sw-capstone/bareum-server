package com.bareum.server.domain.auth.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;

import com.bareum.server.domain.auth.dto.response.EmailVerificationDeliveryResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Auth", description = "회원가입 및 인증")
public interface EmailVerificationDeliveryControllerDocs {
	@Operation(summary = "인증 메일 발송 상태 조회", description = """
			접수 응답의 requestId로 조회합니다. PENDING 동안 타이머를 시작하지 않습니다.
			SENT는 SMTP 서버 수락이며 수신함 도착을 보장하지 않습니다.
			SENT의 expiresIn으로 인증번호 타이머를 시작하고, FAILED 또는 CANCELLED는 발송 실패·무효화를 안내합니다.
			요청 ID는 로그·분석 도구에 노출하지 않습니다. 응답은 Cache-Control: no-store를 사용합니다.
			상태 조회 계약은 프론트 연동 전 협의가 필요한 구현안입니다.
			""")
	@ApiResponse(responseCode = "200",
		description = "발송 상태",
		content = @Content(mediaType = "application/json",
			schema = @Schema(implementation = EmailVerificationDeliveryResponse.class)))
	@ApiResponse(responseCode = "404", description = "요청 ID 없음. 응답 본문 없음", content = @Content)
	@ApiResponse(responseCode = "400",
		description = "잘못된 요청 ID 형식",
		content = @Content(mediaType = "application/problem+json",
			schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
	ResponseEntity<EmailVerificationDeliveryResponse> status(UUID requestId);
}
