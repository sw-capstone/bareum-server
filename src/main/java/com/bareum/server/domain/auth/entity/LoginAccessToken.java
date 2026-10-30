package com.bareum.server.domain.auth.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.bareum.server.domain.member.entity.Member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 발급 이력은 불변으로 보관하고, 세션 단위 폐기는 별도 원자적 쓰기에서 처리한다. */
@Entity
@Table(name = "login_access_token")
@Immutable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoginAccessToken {

	@Id
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "token_hash", nullable = false, length = 64)
	private String tokenHash;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@Column(name = "family_id", nullable = false)
	private UUID familyId;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	private LoginAccessToken(String tokenHash, Member member, UUID familyId, Instant expiresAt, Instant createdAt) {
		this.tokenHash = tokenHash;
		this.member = member;
		this.familyId = familyId;
		this.expiresAt = expiresAt;
		this.createdAt = createdAt;
	}

	public static LoginAccessToken create(
		String tokenHash,
		Member member,
		UUID familyId,
		Instant expiresAt,
		Instant createdAt
	) {
		return new LoginAccessToken(tokenHash, member, familyId, expiresAt, createdAt);
	}
}
