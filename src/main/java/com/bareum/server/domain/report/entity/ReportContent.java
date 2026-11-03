package com.bareum.server.domain.report.entity;

import java.util.Objects;

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.bareum.server.domain.report.body.ReportBody;
import com.bareum.server.domain.report.body.ValidatedReportBody;
import com.bareum.server.domain.report.enums.ContentStatus;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "report_content")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportContent extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "report_id", nullable = false)
	private Report report;

	// TODO(#53): 본문 조회와 편집 저장 성능을 측정하고, 결과에 따라 저장 단위와 갱신 방식을 재검토한다.
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "content", nullable = false, columnDefinition = "jsonb")
	private ReportBody content;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 30)
	@ColumnDefault("'DRAFT'")
	private ContentStatus status;

	@Column(name = "is_current", nullable = false)
	@ColumnDefault("false")
	private boolean isCurrent;

	@Column(name = "revision", nullable = false)
	@ColumnDefault("1")
	private long revision;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "base_content_id", nullable = true)
	private ReportContent baseContent;

	private ReportContent(Report report, ReportBody content) {
		Objects.requireNonNull(report, "report");
		this.report = report;
		this.content = content;
		this.status = ContentStatus.DRAFT;
		this.isCurrent = false;
		this.revision = 1L;
	}

	public static ReportContent create(Report report, ValidatedReportBody content) {
		return new ReportContent(report, requireBody(content));
	}

	public static ReportContent draftFrom(ReportContent baseline) {
		Objects.requireNonNull(baseline, "baseline");
		if (baseline.getStatus() != ContentStatus.FROZEN) {
			throw new ReportException(ReportErrorCode.BASE_CONTENT_NOT_FROZEN);
		}
		ReportContent draft = new ReportContent(baseline.getReport(), baseline.getContent());
		draft.baseContent = baseline;
		return draft;
	}

	public void replaceContent(ValidatedReportBody replacement) {
		if (status != ContentStatus.DRAFT) {
			throw new ReportException(ReportErrorCode.FROZEN_CONTENT_CANNOT_BE_EDITED);
		}
		content = requireBody(replacement);
		revision++;
	}

	private static ReportBody requireBody(ValidatedReportBody validated) {
		if (validated == null) {
			throw new ReportException(ReportErrorCode.INVALID_CONTENT);
		}
		return validated.getBody();
	}

	public void freeze() {
		if (status != ContentStatus.DRAFT) {
			throw new ReportException(ReportErrorCode.CONTENT_ALREADY_FROZEN);
		}
		status = ContentStatus.FROZEN;
	}

	public void adoptAsCurrent() {
		isCurrent = true;
	}

	public void retireFromCurrent() {
		isCurrent = false;
	}
}
