package com.bareum.server.domain.report.dto.response;

import com.bareum.server.domain.report.enums.SourceDeletionStatus;

/** 접수한 원본 삭제 요청의 보고서 식별자와 처리 상태를 응답한다. */
public record SourceDeletionResponse(
	Long reportId,
	SourceDeletionStatus sourceDeletionStatus
) {
	public static SourceDeletionResponse pending(Long reportId) {
		return new SourceDeletionResponse(reportId, SourceDeletionStatus.PENDING);
	}
}
