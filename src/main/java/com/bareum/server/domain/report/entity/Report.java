package com.bareum.server.domain.report.entity;

import java.time.Instant;
import java.util.Objects;

import com.bareum.server.domain.member.entity.Member;
import com.bareum.server.domain.report.enums.FileFormat;
import com.bareum.server.domain.report.enums.InputMethod;
import com.bareum.server.domain.report.enums.ReportType;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;
import com.bareum.server.domain.report.intake.ReportIntakePolicy;
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
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 보고서의 소유자, 문서 유형과 원본 파일의 보관 상태를 저장한다. */
@Entity
@Table(name = "report")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Report extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@Enumerated(EnumType.STRING)
	@Column(name = "report_type", nullable = false, length = 30)
	private ReportType reportType;

	@Column(name = "title", nullable = false, length = 255)
	private String title;

	@Enumerated(EnumType.STRING)
	@Column(name = "file_format", nullable = true, length = 30)
	private FileFormat fileFormat;

	@Enumerated(EnumType.STRING)
	@Column(name = "input_method", nullable = false, length = 30)
	private InputMethod inputMethod;

	@Column(name = "source_object_key", nullable = true, columnDefinition = "text")
	private String sourceObjectKey;

	@Column(name = "source_sha256", length = 64)
	private String sourceSha256;

	@Column(name = "source_version_id", columnDefinition = "text")
	private String sourceVersionId;

	@Column(name = "original_filename", nullable = true, length = 255)
	private String originalFilename;

	@Column(name = "file_size_bytes", nullable = true)
	private Long fileSizeBytes;

	@Column(name = "source_expires_at")
	private Instant sourceExpiresAt;

	@Column(name = "source_deleted_at")
	private Instant sourceDeletedAt;

	@Column(name = "intake_cancelled_at")
	private Instant intakeCancelledAt;

	@Column(name = "source_cleanup_after")
	private Instant sourceCleanupAfter;

	@Builder(access = AccessLevel.PRIVATE)
	private Report(
		Member member,
		ReportType reportType,
		String title,
		FileFormat fileFormat,
		InputMethod inputMethod,
		String sourceObjectKey,
		String originalFilename,
		Long fileSizeBytes,
		Instant sourceExpiresAt
	) {
		Objects.requireNonNull(member, "member");
		Objects.requireNonNull(reportType, "reportType");
		Objects.requireNonNull(inputMethod, "inputMethod");
		this.member = member;
		this.reportType = reportType;
		this.title = title;
		this.fileFormat = fileFormat;
		this.inputMethod = inputMethod;
		this.sourceObjectKey = sourceObjectKey;
		this.originalFilename = originalFilename;
		this.fileSizeBytes = fileSizeBytes;
		this.sourceExpiresAt = sourceExpiresAt;
	}

	public static Report createFromFile(
		Member member,
		ReportType reportType,
		UploadedReportFile source
	) {
		Objects.requireNonNull(source, "source");
		Report report = Report.builder()
				.member(member)
				.reportType(reportType)
				.title(source.originalFilename())
				.fileFormat(source.format())
				.inputMethod(InputMethod.FILE)
				.sourceObjectKey(source.objectKey())
				.originalFilename(source.originalFilename())
				.fileSizeBytes(source.sizeBytes())
				.sourceExpiresAt(source.uploadedAt().plus(ReportIntakePolicy.SOURCE_RETENTION))
				.build();
		report.sourceSha256 = source.sha256();
		report.sourceVersionId = source.versionId();
		return report;
	}

	public static Report createFromText(Member member, ReportType reportType, String title) {
		return Report.builder()
				.member(member)
				.reportType(reportType)
				.title(title)
				.inputMethod(InputMethod.TEXT)
				.build();
	}

	public void markSourceDeleted(Instant deletedAt) {
		if (inputMethod != InputMethod.FILE || deletedAt == null) {
			throw new ReportException(ReportErrorCode.INVALID_SOURCE_DELETION);
		}
		if (sourceDeletedAt == null) {
			sourceDeletedAt = deletedAt;
		}
	}

	public void cancelIntake(Instant cancelledAt) {
		if (inputMethod != InputMethod.FILE || cancelledAt == null) {
			throw new ReportException(ReportErrorCode.INVALID_SOURCE_DELETION);
		}
		if (intakeCancelledAt == null) {
			intakeCancelledAt = cancelledAt;
			scheduleSourceCleanup(cancelledAt);
		}
	}

	public void scheduleSourceCleanup(Instant cleanupAfter) {
		if (inputMethod != InputMethod.FILE || cleanupAfter == null) {
			throw new ReportException(ReportErrorCode.INVALID_SOURCE_DELETION);
		}
		if (sourceDeletedAt == null && (sourceCleanupAfter == null || cleanupAfter.isAfter(sourceCleanupAfter))) {
			sourceCleanupAfter = cleanupAfter;
		}
	}

	public void requireActiveIntake() {
		if (intakeCancelledAt != null) {
			throw new ReportException(ReportErrorCode.INTAKE_CANCELLED);
		}
	}
}
