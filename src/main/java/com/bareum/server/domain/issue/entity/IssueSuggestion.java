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
import jakarta.persistence.OneToOne;
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

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "issue_group_id", nullable = false, unique = true)
	private IssueGroup issueGroup;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "changes", nullable = false, columnDefinition = "jsonb")
	private JsonNode changes;

	@Enumerated(EnumType.STRING)
	@Column(name = "generation_type", nullable = false, length = 30)
	private SuggestionGenerationType generationType;

	private IssueSuggestion(IssueGroup issueGroup, JsonNode changes, SuggestionGenerationType generationType) {
		Objects.requireNonNull(issueGroup, "issueGroup");
		this.issueGroup = issueGroup;
		replaceSuggestion(changes, generationType);
	}

	public void replaceSuggestion(JsonNode changes, SuggestionGenerationType generationType) {
		if (changes == null || !changes.isArray()) {
			throw new IssueException(IssueErrorCode.INVALID_CHANGES);
		}
		Objects.requireNonNull(generationType, "generationType");
		this.changes = changes.deepCopy();
		this.generationType = generationType;
	}

	public static IssueSuggestion create(IssueGroup issueGroup, JsonNode changes, SuggestionGenerationType generationType) {
		return new IssueSuggestion(issueGroup, changes, generationType);
	}

	public JsonNode getChanges() {
		return changes == null ? null : changes.deepCopy();
	}
}
