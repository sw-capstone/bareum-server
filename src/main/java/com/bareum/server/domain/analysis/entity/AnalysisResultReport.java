package com.bareum.server.domain.analysis.entity;

import java.time.Instant;

import org.hibernate.annotations.SQLRestriction;

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

@Entity
@Table(name = "analysis_result_report")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLRestriction("deleted_at IS NULL")
public class AnalysisResultReport extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false)
	private Long id;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "report_analysis_id", nullable = false, unique = true)
	private ReportAnalysis reportAnalysis;

	@Column(name = "summary", columnDefinition = "text")
	private String summary;

	@Column(name = "generated_at", nullable = false)
	private Instant generatedAt;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	private AnalysisResultReport(ReportAnalysis reportAnalysis, String summary, Instant generatedAt) {
		this.reportAnalysis = reportAnalysis;
		this.summary = summary;
		this.generatedAt = generatedAt;
	}

	static AnalysisResultReport create(ReportAnalysis reportAnalysis, String summary, Instant generatedAt) {
		return new AnalysisResultReport(reportAnalysis, summary, generatedAt);
	}
}
