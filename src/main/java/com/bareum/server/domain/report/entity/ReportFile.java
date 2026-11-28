package com.bareum.server.domain.report.entity;

import java.time.Instant;
import java.util.Objects;

import com.bareum.server.domain.report.enums.FileFormat;
import com.bareum.server.domain.report.enums.ReportFileRole;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;
import com.bareum.server.domain.report.intake.PreparedReportFile;
import com.bareum.server.domain.report.source.UploadedReportFile;
import com.bareum.server.global.entity.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 보고서에 속한 S3 객체 한 개의 역할, 파일 정보와 삭제 상태를 저장한다. */
@Entity
@Table(name = "report_file",
	uniqueConstraints = @UniqueConstraint(name = "uk_report_file_role",
		columnNames = {
			"report_id", "file_role"
		}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportFile extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "report_id", nullable = false)
	private Report report;

	@Enumerated(EnumType.STRING)
	@Column(name = "file_role", nullable = false, length = 30)
	private ReportFileRole role;

	@Enumerated(EnumType.STRING)
	@Column(name = "file_format", nullable = false, length = 30)
	private FileFormat format;

	@Column(name = "filename", nullable = false, length = 255)
	private String filename;

	@Column(name = "size_bytes", nullable = false)
	private long sizeBytes;

	@Column(name = "sha256", length = 64)
	private String sha256;

	@Column(name = "object_key", nullable = false, columnDefinition = "text")
	private String objectKey;

	@Column(name = "version_id", columnDefinition = "text")
	private String versionId;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "cleanup_after")
	private Instant cleanupAfter;

	@Column(name = "s3_deleted_at")
	private Instant s3DeletedAt;

	private ReportFile(
		Report report,
		ReportFileRole role,
		FileFormat format,
		String filename,
		long sizeBytes,
		String sha256,
		String objectKey,
		String versionId
	) {
		this.report = Objects.requireNonNull(report, "report");
		report.requireActiveIntake();
		this.role = role;
		this.format = format;
		this.filename = filename;
		this.sizeBytes = sizeBytes;
		this.sha256 = sha256;
		this.objectKey = objectKey;
		this.versionId = versionId;
	}

	public static ReportFile original(Report report, UploadedReportFile file) {
		return new ReportFile(report, ReportFileRole.ORIGINAL, file.format(), file.originalFilename(), file.sizeBytes(),
				file.sha256(), file.objectKey(), file.versionId());
	}

	public static ReportFile reserveConvertedPdf(ReportFile original, String objectKey, PreparedReportFile pdf) {
		if (original.role != ReportFileRole.ORIGINAL || original.format == FileFormat.PDF
				|| original.deletedAt != null || pdf.format() != FileFormat.PDF
				|| objectKey == null || objectKey.isBlank() || original.objectKey.equals(objectKey)) {
			throw new ReportException(ReportErrorCode.FILE_METADATA_REQUIRED);
		}
		return new ReportFile(original.report, ReportFileRole.CONVERTED_PDF, FileFormat.PDF, pdf.filename(),
				pdf.sizeBytes(), pdf.sha256(), objectKey, null);
	}

	public void recordPdfUpload(UploadedReportFile pdf) {
		if (format != FileFormat.PDF || pdf.format() != format || deletedAt != null
				|| !objectKey.equals(pdf.objectKey()) || !filename.equals(pdf.originalFilename())
				|| sizeBytes != pdf.sizeBytes() || !Objects.equals(sha256, pdf.sha256())
				|| (versionId != null && !versionId.equals(pdf.versionId()))) {
			throw new ReportException(ReportErrorCode.INTAKE_REPORT_MISMATCH);
		}
		versionId = pdf.versionId();
	}

	public void scheduleDeletion(Instant deletedAt, Instant cleanupAfter) {
		if (deletedAt == null || cleanupAfter == null || cleanupAfter.isBefore(deletedAt)) {
			throw new ReportException(ReportErrorCode.INVALID_SOURCE_DELETION);
		}
		if (this.deletedAt == null) {
			this.deletedAt = deletedAt;
		}
		if (s3DeletedAt == null && (this.cleanupAfter == null || cleanupAfter.isAfter(this.cleanupAfter))) {
			this.cleanupAfter = cleanupAfter;
		}
	}

	public boolean isReadyForCleanup(Instant now) {
		return s3DeletedAt == null && cleanupAfter != null && !now.isBefore(cleanupAfter);
	}

	public void recoverVersion(String versionId) {
		if (versionId == null || versionId.isBlank()
				|| this.versionId != null && !this.versionId.equals(versionId)) {
			throw new ReportException(ReportErrorCode.INVALID_SOURCE_DELETION);
		}
		this.versionId = versionId;
	}

	public void markS3Deleted(Instant deletedAt) {
		if (s3DeletedAt == null) {
			if (deletedAt == null || !isReadyForCleanup(deletedAt)) {
				throw new ReportException(ReportErrorCode.INVALID_SOURCE_DELETION);
			}
			s3DeletedAt = deletedAt;
		}
	}
}
