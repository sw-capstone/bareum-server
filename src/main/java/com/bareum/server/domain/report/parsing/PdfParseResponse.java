package com.bareum.server.domain.report.parsing;

import java.time.OffsetDateTime;

import com.bareum.server.domain.report.enums.ExtractionQuality;
import com.bareum.server.domain.report.enums.PdfParseErrorAction;
import com.bareum.server.domain.report.enums.PdfParseErrorCode;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.JsonNode;

/** AI PDF 파싱 API의 성공 응답과 실패 응답을 구분하는 공통 타입. */
public sealed interface PdfParseResponse {

	/** 파싱에 성공한 보고서의 추출 결과, 처리 시각, 추출 품질을 담는다. */
	public record Succeeded(
		@JsonProperty(required = true) long reportId,
		@JsonProperty(required = true) long parseAttemptId,
		@JsonProperty(required = true) String status,
		@JsonProperty(required = true) OffsetDateTime startedAt,
		@JsonProperty(required = true) OffsetDateTime completedAt,
		@JsonProperty(required = true) ExtractionQuality extractionQuality,
		@JsonProperty(required = true) JsonNode parsed,
		@JsonProperty(required = true) JsonNode error
	) implements PdfParseResponse {
	}

	/** 파싱 실패 정보와 서버가 알려 준 재요청 대기시간을 담는다. */
	public record Failed(
		@JsonProperty(required = true) Long reportId,
		@JsonProperty(required = true) Long parseAttemptId,
		@JsonProperty(required = true) Error error,
		@JsonIgnore Integer retryAfterSeconds
	) implements PdfParseResponse {
	}

	/** AI가 반환한 오류 코드, 설명, 후속 행동을 담는다. */
	public record Error(
		@JsonProperty(required = true) PdfParseErrorCode code,
		@JsonProperty(required = true) String message,
		@JsonProperty(required = true) PdfParseErrorAction action
	) {
	}
}
