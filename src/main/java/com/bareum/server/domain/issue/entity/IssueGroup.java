package com.bareum.server.domain.issue.entity;

import com.bareum.server.domain.analysis.entity.ReportAnalysis;
import com.bareum.server.domain.issue.enums.IssueScope;
import com.bareum.server.domain.issue.exception.IssueErrorCode;
import com.bareum.server.domain.issue.exception.IssueException;
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
@Table(name = "issue_group")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IssueGroup extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "report_analysis_id", nullable = false)
	private ReportAnalysis reportAnalysis;

	@Column(name = "target_id", length = 255)
	private String targetId;

	@Enumerated(EnumType.STRING)
	@Column(name = "scope", nullable = false, length = 30)
	private IssueScope scope;

	private IssueGroup(ReportAnalysis reportAnalysis, String targetId, IssueScope scope) {
		this.reportAnalysis = reportAnalysis;
		this.targetId = targetId;
		this.scope = scope;
	}

	public static IssueGroup forTarget(ReportAnalysis analysis, IssueScope scope, String targetId) {
		if (scope == null || scope == IssueScope.DOCUMENT || targetId == null || targetId.isBlank()) {
			throw new IssueException(IssueErrorCode.INVALID_GROUP_TARGET);
		}
        return new IssueGroup(analysis, targetId, scope);
	}

	public static IssueGroup forDocument(ReportAnalysis analysis) {
        return new IssueGroup(analysis, null, IssueScope.DOCUMENT);
	}

	public boolean isEditable() {
		return scope == IssueScope.SENTENCE;
	}
}
