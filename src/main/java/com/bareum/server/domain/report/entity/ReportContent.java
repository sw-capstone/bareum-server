package com.bareum.server.domain.report.entity;

import java.util.Objects;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.bareum.server.domain.report.body.ReportBody;
import com.bareum.server.domain.report.body.ValidatedReportBody;
import com.bareum.server.domain.report.exception.ReportErrorCode;
import com.bareum.server.domain.report.exception.ReportException;
import com.bareum.server.global.entity.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 보고서 파싱에 성공했을 때 확정된 본문과 PDF 위치 정보를 저장한다. */
@Entity
@Table(name = "report_content")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportContent extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false)
	private Long id;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "report_id", nullable = false, unique = true)
	private Report report;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "content", nullable = false, columnDefinition = "jsonb")
	private ReportBody content;

	private ReportContent(Report report, ReportBody content) {
		this.report = Objects.requireNonNull(report, "report");
		this.content = content;
	}

	public static ReportContent create(Report report, ValidatedReportBody content) {
		if (content == null) {
			throw new ReportException(ReportErrorCode.INVALID_CONTENT);
		}
		return new ReportContent(report, content.getBody());
	}
}
