package com.bareum.server.domain.analysis.entity;

import com.bareum.server.global.entity.BaseTimeEntity;

import com.bareum.server.domain.analysis.exception.AnalysisException;
import com.bareum.server.domain.analysis.exception.AnalysisErrorCode;

import com.bareum.server.domain.analysis.enums.AnalysisStepCode;
import com.bareum.server.domain.analysis.enums.AnalysisStepStatus;
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
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "report_analysis_step", uniqueConstraints = @UniqueConstraint(columnNames = {"report_analysis_id", "step_code"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportAnalysisStep extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(name = "step_code", nullable = false, length = 30)
	private AnalysisStepCode stepCode;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 30)
	private AnalysisStepStatus status;

	@Column(name = "started_at", nullable = true)
	private Instant startedAt;

	@Column(name = "finished_at", nullable = true)
	private Instant finishedAt;

	@Column(name = "failure_code", nullable = true, length = 30)
	private String failureCode;

	@Column(name = "failure_reason", nullable = true, columnDefinition = "text")
	private String failureReason;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "report_analysis_id", nullable = false)
	private ReportAnalysis reportAnalysis;

	private ReportAnalysisStep(AnalysisStepCode stepCode, ReportAnalysis reportAnalysis) {
		Objects.requireNonNull(stepCode, "stepCode");
		Objects.requireNonNull(reportAnalysis, "reportAnalysis");
		this.stepCode = stepCode;
		this.status = AnalysisStepStatus.PENDING;
		this.reportAnalysis = reportAnalysis;
	}

	public static ReportAnalysisStep create(AnalysisStepCode stepCode, ReportAnalysis reportAnalysis) {
		return new ReportAnalysisStep(stepCode, reportAnalysis);
	}

	public void start(Instant startedAt) {
		if (status != AnalysisStepStatus.PENDING) {
			throw new AnalysisException(AnalysisErrorCode.STEP_NOT_PENDING);
		}
		this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
		status = AnalysisStepStatus.PROCESSING;
	}

	public void succeed(Instant finishedAt) {
		if (status != AnalysisStepStatus.PROCESSING) {
			throw new AnalysisException(AnalysisErrorCode.STEP_NOT_PROCESSING);
		}
		finish(AnalysisStepStatus.SUCCEEDED, finishedAt);
	}

	public void fail(String failureCode, String failureReason, Instant finishedAt) {
		if (status != AnalysisStepStatus.PROCESSING) {
			throw new AnalysisException(AnalysisErrorCode.STEP_NOT_PROCESSING);
		}
		finish(AnalysisStepStatus.FAILED, finishedAt);
		this.failureCode = failureCode;
		this.failureReason = failureReason;
	}

	public void skip(Instant finishedAt) {
		if (status != AnalysisStepStatus.PENDING) {
			throw new AnalysisException(AnalysisErrorCode.STEP_NOT_PENDING);
		}
		finish(AnalysisStepStatus.SKIPPED, finishedAt);
	}

	public void cancel(Instant finishedAt) {
		if (status != AnalysisStepStatus.PENDING && status != AnalysisStepStatus.PROCESSING) {
			throw new AnalysisException(AnalysisErrorCode.STEP_ALREADY_FINISHED);
		}
		finish(AnalysisStepStatus.CANCELLED, finishedAt);
	}

	private void finish(AnalysisStepStatus result, Instant finishedAt) {
		Objects.requireNonNull(finishedAt, "finishedAt");
		if (startedAt != null && finishedAt.isBefore(startedAt)) {
			throw new AnalysisException(AnalysisErrorCode.INVALID_EXECUTION_TIME);
		}
		this.finishedAt = finishedAt;
		status = result;
	}
}
