package com.bareum.server.domain.term.entity;

import com.bareum.server.global.entity.BaseTimeEntity;

import com.bareum.server.domain.term.exception.TermException;
import com.bareum.server.domain.term.exception.TermErrorCode;

import com.bareum.server.domain.member.entity.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "member_term")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberTerm extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "term_id", nullable = false)
	private Term term;

	@Column(name = "agreed_at", nullable = true)
	private Instant agreedAt;

	@Column(name = "withdrawn_at", nullable = true)
	private Instant withdrawnAt;

	@Column(name = "is_agreed", nullable = false)
	private boolean isAgreed;

	@Column(name = "responded_at", nullable = false)
	private Instant respondedAt;

	@Builder(access = AccessLevel.PRIVATE)
	private MemberTerm(
		Member member,
		Term term,
		Instant agreedAt,
		Instant withdrawnAt,
		boolean isAgreed,
		Instant respondedAt
	) {
		Objects.requireNonNull(member, "member");
		Objects.requireNonNull(term, "term");
		Objects.requireNonNull(respondedAt, "respondedAt");
		if (isAgreed && (agreedAt == null || withdrawnAt != null)) {
			throw new TermException(TermErrorCode.INVALID_CONSENT_STATE);
		}
		if (!isAgreed && agreedAt != null && withdrawnAt == null) {
			throw new TermException(TermErrorCode.INVALID_CONSENT_STATE);
		}
		this.member = member;
		this.term = term;
		this.agreedAt = agreedAt;
		this.withdrawnAt = withdrawnAt;
		this.isAgreed = isAgreed;
		this.respondedAt = respondedAt;
	}

	public static MemberTerm create(
		Member member,
		Term term,
		Instant agreedAt,
		Instant withdrawnAt,
		boolean isAgreed,
		Instant respondedAt
	) {
		return MemberTerm.builder()
			.member(member)
			.term(term)
			.agreedAt(agreedAt)
			.withdrawnAt(withdrawnAt)
			.isAgreed(isAgreed)
			.respondedAt(respondedAt)
			.build();
	}
}
