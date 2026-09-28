package com.bareum.server.domain.report.entity;

import com.bareum.server.domain.analysis.entity.ReportAnalysis;
import com.bareum.server.domain.report.enums.ContentStatus;
import com.bareum.server.domain.report.enums.ResultStatus;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(name = "report_result")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLRestriction("deleted_at IS NULL")
public class ReportResult extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "report_analysis_id", nullable = false)
	private ReportAnalysis reportAnalysis;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "content_id", nullable = false)
	private ReportContent content;

	@Column(name = "final_score", precision = 5, scale = 2)
	private BigDecimal finalScore;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 30)
	@ColumnDefault("'PENDING'")
	private ResultStatus status;

	@Column(name = "failure_code", length = 30)
	private String failureCode;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	private ReportResult(ReportAnalysis reportAnalysis, ReportContent content) {
		if (content.getStatus() != ContentStatus.FROZEN) {
			throw new ReportException(ReportErrorCode.RESULT_CONTENT_NOT_FROZEN);
		}
		this.reportAnalysis = reportAnalysis;
		this.content = content;
		this.status = ResultStatus.PENDING;
	}

	public static ReportResult request(ReportAnalysis analysis, ReportContent content) {
        return new ReportResult(analysis, content);
	}

	public void start() {
		requireStatus(ResultStatus.PENDING);
		status = ResultStatus.PROCESSING;
	}

	public void succeed(BigDecimal score, Instant completedAt) {
		requireStatus(ResultStatus.PROCESSING);
		if (score == null || score.signum() < 0 || score.precision() - score.scale() > 3
			|| score.stripTrailingZeros().scale() > 2 || completedAt == null) {
			throw new ReportException(ReportErrorCode.INVALID_RESULT);
		}
		this.finalScore = score;
		this.completedAt = completedAt;
		status = ResultStatus.SUCCEEDED;
	}

	public void fail(String failureCode) {
		requireStatus(ResultStatus.PROCESSING);
		this.failureCode = failureCode;
		status = ResultStatus.FAILED;
	}

	public void softDelete(Instant deletedAt) {
		if (deletedAt == null) {
			throw new ReportException(ReportErrorCode.INVALID_RESULT);
		}
		if (this.deletedAt == null) {
			this.deletedAt = deletedAt;
		}
	}

	private void requireStatus(ResultStatus expected) {
		if (deletedAt != null || status != expected) {
			throw new ReportException(ReportErrorCode.INVALID_RESULT_STATE);
		}
	}
}
