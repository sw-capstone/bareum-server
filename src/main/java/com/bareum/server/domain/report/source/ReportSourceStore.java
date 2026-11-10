package com.bareum.server.domain.report.source;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import com.bareum.server.domain.report.enums.FileFormat;
import com.bareum.server.domain.report.intake.PreparedReportFile;

/** 보고서 원본 저장과 임시 URL 발급, 버전 조회와 삭제 기능을 정의한다. */
public interface ReportSourceStore {
	String newObjectKey(long memberId, FileFormat format);

	UploadedReportFile upload(String objectKey, PreparedReportFile file);

	DownloadSource presign(UploadedReportFile source);

	List<String> findVersions(String objectKey);

	void delete(String objectKey, String versionId);

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
