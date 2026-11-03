package com.bareum.server.domain.report.parsing;

import java.net.URI;
import java.time.OffsetDateTime;

/** AI에 보낼 보고서 식별자, 파싱 시도 식별자, 원본 파일 정보를 담는다. */
public record PdfParseRequest(
	long reportId,
	long parseAttemptId,
	Source source
) {

	/** AI가 원본 PDF를 확인하고 다운로드하는 데 필요한 정보를 담는다. */
	public record Source(
		String objectKey,
		String originalFilename,
		long sizeBytes,
		String sha256,
		URI downloadUrl,
		OffsetDateTime urlExpiresAt
	) {

		@Override
		public String toString() {
			return "Source[downloadUrl=REDACTED]";
		}
	}
}
