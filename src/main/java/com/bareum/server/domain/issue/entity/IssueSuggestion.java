package com.bareum.server.domain.issue.entity;

import com.bareum.server.domain.issue.exception.IssueException;
import com.bareum.server.domain.issue.exception.IssueErrorCode;

import com.bareum.server.domain.issue.enums.SuggestionGenerationType;
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
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

@Entity
@Table(name = "issue_suggestion")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IssueSuggestion extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "issue_id", nullable = false)
	private Issue issue;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "changes", nullable = false, columnDefinition = "jsonb")
	private JsonNode changes;

	@Enumerated(EnumType.STRING)
	@Column(name = "generation_type", nullable = false, length = 30)
	private SuggestionGenerationType generationType;

	private IssueSuggestion(Issue issue, JsonNode changes, SuggestionGenerationType generationType) {
		Objects.requireNonNull(issue, "issue");
		if (changes == null || !changes.isArray()) {
			throw new IssueException(IssueErrorCode.INVALID_CHANGES);
		}
		Objects.requireNonNull(generationType, "generationType");
		this.issue = issue;
		this.changes = changes.deepCopy();
		this.generationType = generationType;
	}

	public static IssueSuggestion create(Issue issue, JsonNode changes, SuggestionGenerationType generationType) {
		return new IssueSuggestion(issue, changes, generationType);
	}

	public JsonNode getChanges() {
		return changes == null ? null : changes.deepCopy();
	}
}
