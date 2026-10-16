package com.bareum.server.domain.auth.dto.response;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "인증 메일 접수 및 발송 상태. 인증번호·수신 주소는 포함하지 않음")
public record EmailVerificationDeliveryResponse(
	@Schema(description = "발송 상태 조회용 요청 ID") UUID requestId,
	@Schema(description = "PENDING: 대기·전송·재시도, SENT: SMTP 수락, FAILED: 최종 실패, CANCELLED: 무효화",
		allowableValues = {
			"PENDING", "SENT", "FAILED", "CANCELLED"
		}) String status,
	@Schema(description = "SMTP 수락 후 남은 코드 유효시간(초). 대기·실패·취소 시 0", example = "0") long expiresIn
) {
	public static EmailVerificationDeliveryResponse pending(UUID id) {
		return new EmailVerificationDeliveryResponse(id, "PENDING", 0);
	}
}
