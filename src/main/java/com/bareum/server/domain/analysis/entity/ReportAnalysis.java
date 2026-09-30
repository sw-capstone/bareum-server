package com.bareum.server.domain.analysis.entity;

import com.bareum.server.domain.analysis.exception.AnalysisException;
import com.bareum.server.domain.analysis.exception.AnalysisErrorCode;

import com.bareum.server.domain.analysis.enums.AnalysisStatus;
import com.bareum.server.domain.report.entity.ReportContent;
import com.bareum.server.domain.report.enums.ContentStatus;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "report_analysis")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportAnalysis extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "content_id", nullable = false)
	private ReportContent content;

	@Column(name = "request_key", nullable = false, unique = true)
	private UUID requestKey;

	@Column(name = "total_score", nullable = true, precision = 5, scale = 2)
	private BigDecimal totalScore;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 30)
	private AnalysisStatus status;

	@Column(name = "started_at", nullable = true)
	private Instant startedAt;

	@Column(name = "finished_at", nullable = true)
	private Instant finishedAt;

	@Column(name = "failure_code", nullable = true, length = 30)
	private String failureCode;

	@Column(name = "failure_reason", nullable = true, columnDefinition = "text")
	private String failureReason;

	@Column(name = "ruleset_version", nullable = false, length = 255)
	private String rulesetVersion;

	@Column(name = "prompt_version", nullable = false, length = 255)
	private String promptVersion;

	private ReportAnalysis(ReportContent content, UUID requestKey, String rulesetVersion, String promptVersion) {
		Objects.requireNonNull(content, "content");
		Objects.requireNonNull(requestKey, "requestKey");
		if (content.getStatus() != ContentStatus.FROZEN) {
			throw new AnalysisException(AnalysisErrorCode.INPUT_NOT_FROZEN);
		}
		this.content = content;
		this.requestKey = requestKey;
		this.status = AnalysisStatus.PENDING;
		this.rulesetVersion = rulesetVersion;
		this.promptVersion = promptVersion;
	}

	public static ReportAnalysis request(ReportContent content, UUID requestKey, String rulesetVersion, String promptVersion) {
		return new ReportAnalysis(content, requestKey, rulesetVersion, promptVersion);
	}

	public void start(Instant startedAt) {
		if (status != AnalysisStatus.PENDING) {
			throw new AnalysisException(AnalysisErrorCode.ANALYSIS_NOT_PENDING);
		}
		this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
		status = AnalysisStatus.PROCESSING;
	}

	public void succeed(BigDecimal totalScore, Instant finishedAt) {
		requireProcessing();
		if (totalScore == null || totalScore.signum() < 0 || totalScore.precision() - totalScore.scale() > 3 || totalScore.stripTrailingZeros().scale() > 2) {
			throw new AnalysisException(AnalysisErrorCode.INVALID_SCORE);
		}
		validateFinishTime(finishedAt);
		this.totalScore = totalScore;
		this.finishedAt = finishedAt;
		status = AnalysisStatus.SUCCEEDED;
	}

	public void fail(String failureCode, String failureReason, Instant finishedAt) {
		finishFailure(AnalysisStatus.FAILED, failureCode, failureReason, finishedAt);
	}

	public void finishPartiallyFailed(String failureCode, String failureReason, Instant finishedAt) {
		finishFailure(AnalysisStatus.PARTIAL_FAILED, failureCode, failureReason, finishedAt);
	}

	private void finishFailure(AnalysisStatus result, String failureCode, String failureReason, Instant finishedAt) {
		requireProcessing();
		validateFinishTime(finishedAt);
		this.failureCode = failureCode;
		this.failureReason = failureReason;
		this.finishedAt = finishedAt;
		status = result;
	}

	public void cancel(Instant finishedAt) {
		if (status != AnalysisStatus.PENDING && status != AnalysisStatus.PROCESSING) {
			throw new AnalysisException(AnalysisErrorCode.ANALYSIS_ALREADY_FINISHED);
		}
		validateFinishTime(finishedAt);
		this.finishedAt = finishedAt;
		status = AnalysisStatus.CANCELLED;
	}

	private void requireProcessing() {
		if (status != AnalysisStatus.PROCESSING) {
			throw new AnalysisException(AnalysisErrorCode.ANALYSIS_NOT_PROCESSING);
		}
	}

	private void validateFinishTime(Instant finishedAt) {
		Objects.requireNonNull(finishedAt, "finishedAt");
		if (startedAt != null && finishedAt.isBefore(startedAt)) {
			throw new AnalysisException(AnalysisErrorCode.INVALID_EXECUTION_TIME);
		}
	}
}
