package com.bareum.server.domain.report.source;

import java.time.Instant;
import java.util.Locale;

import com.bareum.server.domain.report.enums.FileFormat;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;
import com.bareum.server.domain.report.intake.ReportIntakePolicy;

/** S3에 저장된 원본의 식별 정보. 다운로드 자격은 저장하지 않는다. */
public record UploadedReportFile(
	FileFormat format,
	String objectKey,
	String originalFilename,
	long sizeBytes,
	String sha256,
	String versionId,
	Instant uploadedAt
) {
	public UploadedReportFile {
		if (format == null || objectKey == null || objectKey.isBlank()
				|| originalFilename == null || originalFilename.isBlank()
				|| originalFilename.codePointCount(0, originalFilename.length()) > 255
				|| originalFilename.contains("/") || originalFilename.contains("\\")
				|| originalFilename.codePoints().anyMatch(Character::isISOControl)
				|| sizeBytes <= 0 || sha256 == null || !sha256.matches("[a-fA-F0-9]{64}")
				|| uploadedAt == null || (versionId != null && versionId.isBlank())) {
			throw new ReportException(ReportErrorCode.FILE_METADATA_REQUIRED);
		}
		if (sizeBytes > ReportIntakePolicy.MAX_FILE_SIZE_BYTES) {
			throw new ReportException(ReportErrorCode.FILE_TOO_LARGE);
		}
		sha256 = sha256.toLowerCase(Locale.ROOT);
	}

	@Override
	public String toString() {
		return "UploadedReportFile[format=" + format + ", sizeBytes=" + sizeBytes + "]";
	}
}
