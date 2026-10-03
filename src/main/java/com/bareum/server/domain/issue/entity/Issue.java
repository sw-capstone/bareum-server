package com.bareum.server.domain.issue.entity;

import com.bareum.server.domain.analysis.entity.ReportAnalysisAreaResult;
import com.bareum.server.domain.issue.enums.IssueSeverity;
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
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "issue")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Issue extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "analysis_area_result_id", nullable = false)
	private ReportAnalysisAreaResult analysisAreaResult;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "issue_group_id", nullable = false)
	private IssueGroup issueGroup;

	@Enumerated(EnumType.STRING)
	@Column(name = "severity", nullable = false, length = 30)
	private IssueSeverity severity;

	@Column(name = "title", nullable = false, length = 255)
	private String title;

	@Column(name = "reason", nullable = false, columnDefinition = "text")
	private String reason;

	@Column(name = "review_guidance", nullable = true, columnDefinition = "text")
	private String reviewGuidance;

	@Builder(access = AccessLevel.PRIVATE)
	private Issue(
		ReportAnalysisAreaResult analysisAreaResult,
		IssueGroup issueGroup,
		IssueSeverity severity,
		String title,
		String reason,
		String reviewGuidance
	) {
		Objects.requireNonNull(analysisAreaResult, "analysisAreaResult");
		Objects.requireNonNull(severity, "severity");
		this.analysisAreaResult = analysisAreaResult;
		this.issueGroup = issueGroup;
		this.severity = severity;
		this.title = title;
		this.reason = reason;
		this.reviewGuidance = reviewGuidance;
	}

	public static Issue create(
		ReportAnalysisAreaResult analysisAreaResult,
		IssueGroup issueGroup,
		IssueSeverity severity,
		String title,
		String reason,
		String reviewGuidance
	) {
		return Issue.builder()
			.analysisAreaResult(analysisAreaResult)
			.issueGroup(issueGroup)
			.severity(severity)
			.title(title)
			.reason(reason)
			.reviewGuidance(reviewGuidance)
			.build();
	}

}
