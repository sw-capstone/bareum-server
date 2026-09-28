package com.bareum.server.domain.report.entity;

import com.bareum.server.domain.report.exception.ReportException;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.member.entity.Member;
import com.bareum.server.domain.report.enums.FileFormat;
import com.bareum.server.domain.report.enums.InputMethod;
import com.bareum.server.domain.report.enums.ReportType;
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
import java.util.Objects;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

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

	@Column(name = "original_filename", nullable = true, length = 255)
	private String originalFilename;

	@Column(name = "file_size_bytes", nullable = true)
	private Long fileSizeBytes;

	@Column(name = "source_expires_at")
	private Instant sourceExpiresAt;

	@Column(name = "source_deleted_at")
	private Instant sourceDeletedAt;

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
		FileFormat fileFormat,
		String sourceObjectKey,
		String originalFilename,
		long fileSizeBytes,
		Instant uploadedAt
	) {
		if (fileFormat == null || sourceObjectKey == null || sourceObjectKey.isBlank()
			|| originalFilename == null || originalFilename.isBlank() || fileSizeBytes <= 0 || uploadedAt == null) {
			throw new ReportException(ReportErrorCode.FILE_METADATA_REQUIRED);
		}
		return Report.builder()
			.member(member)
			.reportType(reportType)
			.title(originalFilename)
			.fileFormat(fileFormat)
			.inputMethod(InputMethod.FILE)
			.sourceObjectKey(sourceObjectKey)
			.originalFilename(originalFilename)
			.fileSizeBytes(fileSizeBytes)
			.sourceExpiresAt(uploadedAt.plus(30, ChronoUnit.DAYS))
			.build();
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
}
