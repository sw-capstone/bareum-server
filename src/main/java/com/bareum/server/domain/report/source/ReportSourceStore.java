package com.bareum.server.domain.report.source;

import java.net.URI;
import java.time.Instant;

import com.bareum.server.domain.report.intake.PreparedPdf;

/** 보고서 원본 저장과 임시 다운로드 URL 발급에 필요한 기능을 정의한다. */
public interface ReportSourceStore {
	String newObjectKey(long memberId);

	UploadedReportFile upload(String objectKey, PreparedPdf pdf);

	DownloadSource presign(UploadedReportFile source);

	/** 원본을 내려받을 임시 URL과 만료 시각을 전달한다. */
	record DownloadSource(
		URI url,
		Instant expiresAt
	) {
		@Override
		public String toString() {
			return "DownloadSource[url=REDACTED]";
		}
	}
}
