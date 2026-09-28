package com.bareum.server.domain.analysis.entity;

import com.bareum.server.domain.analysis.exception.AnalysisException;
import com.bareum.server.domain.analysis.exception.AnalysisErrorCode;

import com.bareum.server.domain.analysis.enums.AnalysisAreaCode;
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
import java.math.BigDecimal;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

@Entity
@Table(name = "report_analysis_area_result", uniqueConstraints = @UniqueConstraint(columnNames = {"report_analysis_id", "area_code"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportAnalysisAreaResult {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "report_analysis_id", nullable = false)
	private ReportAnalysis reportAnalysis;

	@Column(name = "deduction_score", nullable = true, precision = 5, scale = 2)
	private BigDecimal deductionScore;

	@Enumerated(EnumType.STRING)
	@Column(name = "area_code", nullable = false, length = 30)
	private AnalysisAreaCode areaCode;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "result_detail", nullable = true, columnDefinition = "jsonb")
	private JsonNode resultDetail;

	private ReportAnalysisAreaResult(
		ReportAnalysis reportAnalysis,
		BigDecimal deductionScore,
		AnalysisAreaCode areaCode,
		JsonNode resultDetail
	) {
		Objects.requireNonNull(reportAnalysis, "reportAnalysis");
		Objects.requireNonNull(areaCode, "areaCode");
		if (resultDetail != null && resultDetail.isNull()) {
			throw new AnalysisException(AnalysisErrorCode.INVALID_RESULT_DETAIL);
		}
		this.reportAnalysis = reportAnalysis;
		this.deductionScore = deductionScore;
		this.areaCode = areaCode;
		this.resultDetail = resultDetail == null ? null : resultDetail.deepCopy();
	}

	public static ReportAnalysisAreaResult create(
		ReportAnalysis reportAnalysis,
		BigDecimal deductionScore,
		AnalysisAreaCode areaCode,
		JsonNode resultDetail
	) {
		return new ReportAnalysisAreaResult(reportAnalysis, deductionScore, areaCode, resultDetail);
	}

	public JsonNode getResultDetail() {
		return resultDetail == null ? null : resultDetail.deepCopy();
	}
}
