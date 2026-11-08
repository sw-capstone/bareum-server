package com.bareum.server.domain.report.dto.response;

import java.time.Instant;
import java.util.List;

import com.bareum.server.domain.report.body.ReportBody.Warning;
import com.bareum.server.domain.report.entity.ReportParseAttempt;
import com.bareum.server.domain.report.enums.ExtractionQuality;
import com.bareum.server.domain.report.enums.ParseStatus;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;

import io.swagger.v3.oas.annotations.media.Schema;

/** 저장된 보고서와 본문, 파싱 결과와 원본 만료 시각을 응답한다. */
public record ReportUploadResponse(
	Long reportId,
	Long contentId,
	Long parseAttemptId,
	@Schema(allowableValues = "SUCCEEDED") ParseStatus parseStatus,
	ExtractionQuality extractionQuality,
	List<Warning> warnings,
	Instant sourceExpiresAt
) {
	public ReportUploadResponse {
		warnings = List.copyOf(warnings);
	}

	public static ReportUploadResponse from(ReportParseAttempt attempt) {
		if (attempt.getProcessStatus() != ParseStatus.SUCCEEDED) {
			throw new ReportException(ReportErrorCode.INVALID_INTAKE_RESULT);
		}
		return new ReportUploadResponse(attempt.getReport().getId(), attempt.getProducedContent().getId(),
				attempt.getId(), attempt.getProcessStatus(), attempt.getExtractionQuality(),
				attempt.getProducedContent().getContent().warnings(), attempt.getReport().getSourceExpiresAt());
	}
}
