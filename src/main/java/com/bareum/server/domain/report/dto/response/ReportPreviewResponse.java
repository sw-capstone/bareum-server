package com.bareum.server.domain.report.dto.response;

import java.net.URI;
import java.time.Instant;

public record ReportPreviewResponse(
	long reportId,
	URI pdfUrl,
	Instant expiresAt
) {
	@Override
	public String toString() {
		return "ReportPreviewResponse[reportId=" + reportId + ", pdfUrl=REDACTED, expiresAt=" + expiresAt + "]";
	}
}
