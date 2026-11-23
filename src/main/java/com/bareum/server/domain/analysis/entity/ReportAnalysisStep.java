package com.bareum.server.domain.analysis.entity;

import java.time.Instant;
import java.util.Objects;

import org.hibernate.annotations.ColumnDefault;

import com.bareum.server.domain.analysis.enums.AnalysisStepCode;
import com.bareum.server.domain.analysis.enums.AnalysisStepStatus;
import com.bareum.server.domain.analysis.exception.AnalysisErrorCode;
import com.bareum.server.domain.analysis.exception.AnalysisException;
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

@Entity
@Table(name = "report_analysis_step",
	uniqueConstraints = @UniqueConstraint(columnNames = {
		"report_analysis_id", "step_code"
	}))
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

	@Column(name = "retry_count", nullable = false)
	@ColumnDefault("0")
	private int retryCount;

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
		if (stepCode == AnalysisStepCode.REPORT_GENERATION) {
			reportAnalysis.requireScoreForReportGeneration();
		}
		this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
		status = AnalysisStepStatus.PROCESSING;
	}

	public void succeed(Instant finishedAt) {
		requireNonReportGenerationStep();
		if (status != AnalysisStepStatus.PROCESSING) {
			throw new AnalysisException(AnalysisErrorCode.STEP_NOT_PROCESSING);
		}
		finish(AnalysisStepStatus.SUCCEEDED, finishedAt);
	}

	public void fail(String failureCode, String failureReason, Instant finishedAt) {
		requireNonReportGenerationStep();
		if (status != AnalysisStepStatus.PROCESSING) {
			throw new AnalysisException(AnalysisErrorCode.STEP_NOT_PROCESSING);
		}
		finish(AnalysisStepStatus.FAILED, finishedAt);
		this.failureCode = failureCode;
		this.failureReason = failureReason;
	}

	/** 첫 리포트 생성 시도가 일시 오류로 끝났을 때 같은 단계에서 재시도한다. */
	public void retryReportGeneration(Instant startedAt) {
		requireReportGenerationAttempt(0, startedAt);
		retryCount++;
		this.startedAt = startedAt;
	}

	/** 생성된 결과 리포트를 단계와 전체 분석 상태를 갱신하는 트랜잭션에서 함께 저장한다. */
	public AnalysisResultReport completeReportGeneration(int expectedRetryCount, String summary, Instant finishedAt) {
		requireReportGenerationAttempt(expectedRetryCount, finishedAt);
		reportAnalysis.finishReportGenerationSucceeded(finishedAt);
		markFinished(AnalysisStepStatus.SUCCEEDED, finishedAt);
		return AnalysisResultReport.create(reportAnalysis, summary, finishedAt);
	}

	public void failReportGeneration(
		int expectedRetryCount,
		String failureCode,
		String failureReason,
		Instant finishedAt
	) {
		requireReportGenerationAttempt(expectedRetryCount, finishedAt);
		reportAnalysis.finishReportGenerationFailed(failureCode, failureReason, finishedAt);
		markFinished(AnalysisStepStatus.FAILED, finishedAt);
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
		validateExecutionTime(finishedAt);
		markFinished(result, finishedAt);
	}

	private void markFinished(AnalysisStepStatus result, Instant finishedAt) {
		this.finishedAt = finishedAt;
		status = result;
	}

	private void validateExecutionTime(Instant time) {
		Objects.requireNonNull(time, "time");
		if (startedAt != null && time.isBefore(startedAt)) {
			throw new AnalysisException(AnalysisErrorCode.INVALID_EXECUTION_TIME);
		}
	}

	private void requireNonReportGenerationStep() {
		if (stepCode == AnalysisStepCode.REPORT_GENERATION) {
			throw new AnalysisException(AnalysisErrorCode.INVALID_REPORT_GENERATION_TRANSITION);
		}
	}

	private void requireReportGenerationAttempt(int expectedRetryCount, Instant time) {
		if (stepCode != AnalysisStepCode.REPORT_GENERATION) {
			throw new AnalysisException(AnalysisErrorCode.INVALID_REPORT_GENERATION_TRANSITION);
		}
		if (status != AnalysisStepStatus.PROCESSING) {
			throw new AnalysisException(AnalysisErrorCode.STEP_NOT_PROCESSING);
		}
		if (retryCount != expectedRetryCount) {
			throw new AnalysisException(AnalysisErrorCode.STALE_REPORT_GENERATION_ATTEMPT);
		}
		reportAnalysis.requireScoreForReportGeneration();
		validateExecutionTime(time);
	}
}
