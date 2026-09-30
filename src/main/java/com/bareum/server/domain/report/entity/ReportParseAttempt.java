package com.bareum.server.domain.report.entity;

import com.bareum.server.domain.report.exception.ReportException;
import com.bareum.server.domain.report.exception.ReportErrorCode;

import com.bareum.server.domain.report.enums.ExtractionQuality;
import com.bareum.server.domain.report.enums.ParseStatus;
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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

@Entity
@Table(name = "report_parse_attempt")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportParseAttempt extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(name = "process_status", nullable = false, length = 30)
	@ColumnDefault("'PENDING'")
	private ParseStatus processStatus;

	@Enumerated(EnumType.STRING)
	@Column(name = "extraction_quality", nullable = true, length = 30)
	private ExtractionQuality extractionQuality;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "extraction_warnings", nullable = false, columnDefinition = "jsonb")
	@ColumnDefault("'[]'::jsonb")
	private JsonNode extractionWarnings;

	@Column(name = "error_code", nullable = true, length = 30)
	private String errorCode;

	@Column(name = "started_at", nullable = true)
	private Instant startedAt;

	@Column(name = "completed_at", nullable = true)
	private Instant completedAt;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "report_id", nullable = false)
	private Report report;

	@OneToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "produced_content_id", nullable = true, unique = true)
	private ReportContent producedContent;

	private ReportParseAttempt(Report report) {
		Objects.requireNonNull(report, "report");
		this.processStatus = ParseStatus.PENDING;
		this.extractionWarnings = JsonNodeFactory.instance.arrayNode();
		this.report = report;
	}

	public static ReportParseAttempt request(Report report) {
		return new ReportParseAttempt(report);
	}

	public JsonNode getExtractionWarnings() {
		return extractionWarnings == null ? null : extractionWarnings.deepCopy();
	}

	public void start(Instant startedAt) {
		if (processStatus != ParseStatus.PENDING) {
			throw new ReportException(ReportErrorCode.PARSE_NOT_PENDING);
		}
		this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
		processStatus = ParseStatus.PROCESSING;
	}

	public void complete(ReportContent producedContent, ExtractionQuality quality, JsonNode warnings, Instant completedAt) {
		requireProcessing();
		Objects.requireNonNull(producedContent, "producedContent");
		Objects.requireNonNull(quality, "quality");
		if (warnings == null || !warnings.isArray()) {
			throw new ReportException(ReportErrorCode.INVALID_PARSE_RESULT);
		}
		Report owner = producedContent.getReport();
		if (owner != report && (report.getId() == null || !report.getId().equals(owner.getId()))) {
			throw new ReportException(ReportErrorCode.PARSE_REPORT_MISMATCH);
		}
		validateCompletionTime(completedAt);
		this.producedContent = producedContent;
		this.extractionQuality = quality;
		this.extractionWarnings = warnings.deepCopy();
		this.completedAt = completedAt;
		processStatus = ParseStatus.SUCCEEDED;
	}

	public void fail(String errorCode, Instant completedAt) {
		requireProcessing();
		validateCompletionTime(completedAt);
		this.errorCode = errorCode;
		this.completedAt = completedAt;
		processStatus = ParseStatus.FAILED;
	}

	private void requireProcessing() {
		if (processStatus != ParseStatus.PROCESSING) {
			throw new ReportException(ReportErrorCode.PARSE_NOT_PROCESSING);
		}
	}

	private void validateCompletionTime(Instant completedAt) {
		Objects.requireNonNull(completedAt, "completedAt");
		if (completedAt.isBefore(startedAt)) {
			throw new ReportException(ReportErrorCode.INVALID_PARSE_TIME);
		}
	}
}
