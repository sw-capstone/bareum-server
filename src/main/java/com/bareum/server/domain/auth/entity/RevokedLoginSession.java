package com.bareum.server.domain.auth.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Immutable;

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

/** 폐기된 세션만 보관한다. Access 토큰별 발급 정보는 저장하지 않는다. */
@Entity
@Table(name = "revoked_login_session")
@Immutable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RevokedLoginSession {
	@Id
	@Column(name = "family_id", nullable = false)
	private UUID familyId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@Column(name = "revoked_at", nullable = false)
	private Instant revokedAt;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;
}
