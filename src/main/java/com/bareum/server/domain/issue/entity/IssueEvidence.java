package com.bareum.server.domain.issue.entity;

import com.bareum.server.domain.issue.enums.EvidenceType;
import com.bareum.server.global.entity.BaseCreatedTimeEntity;
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
import java.time.LocalDate;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "issue_evidence")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IssueEvidence extends BaseCreatedTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "issue_id", nullable = false)
	private Issue issue;

	@Enumerated(EnumType.STRING)
	@Column(name = "evidence_type", nullable = false, length = 30)
	private EvidenceType evidenceType;

	@Column(name = "source_title", nullable = false, length = 255)
	private String sourceTitle;

	@Column(name = "source_locator", nullable = true, length = 255)
	private String sourceLocator;

	@Column(name = "quoted_text", nullable = false, columnDefinition = "text")
	private String quotedText;

	@Column(name = "source_url", nullable = true, columnDefinition = "text")
	private String sourceUrl;

	@Column(name = "source_version", nullable = true, length = 255)
	private String sourceVersion;

	@Column(name = "source_effective_date", nullable = true)
	private LocalDate sourceEffectiveDate;

	@Builder(access = AccessLevel.PRIVATE)
	private IssueEvidence(
		Issue issue,
		EvidenceType evidenceType,
		String sourceTitle,
		String sourceLocator,
		String quotedText,
		String sourceUrl,
		String sourceVersion,
		LocalDate sourceEffectiveDate
	) {
		Objects.requireNonNull(issue, "issue");
		Objects.requireNonNull(evidenceType, "evidenceType");
		this.issue = issue;
		this.evidenceType = evidenceType;
		this.sourceTitle = sourceTitle;
		this.sourceLocator = sourceLocator;
		this.quotedText = quotedText;
		this.sourceUrl = sourceUrl;
		this.sourceVersion = sourceVersion;
		this.sourceEffectiveDate = sourceEffectiveDate;
	}

	public static IssueEvidence create(
		Issue issue,
		EvidenceType evidenceType,
		String sourceTitle,
		String sourceLocator,
		String quotedText,
		String sourceUrl,
		String sourceVersion,
		LocalDate sourceEffectiveDate
	) {
		return IssueEvidence.builder()
			.issue(issue)
			.evidenceType(evidenceType)
			.sourceTitle(sourceTitle)
			.sourceLocator(sourceLocator)
			.quotedText(quotedText)
			.sourceUrl(sourceUrl)
			.sourceVersion(sourceVersion)
			.sourceEffectiveDate(sourceEffectiveDate)
			.build();
	}
}
