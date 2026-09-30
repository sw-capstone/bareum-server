package com.bareum.server.domain.auth.entity;

import com.bareum.server.domain.member.entity.Member;
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
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "refresh_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false)
	private Long id;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "member_id", nullable = false, unique = true)
	private Member member;

	@Column(name = "jti", nullable = false, unique = true)
	private UUID jti;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	private RefreshToken(Member member, UUID jti, Instant expiresAt) {
		Objects.requireNonNull(member, "member");
		Objects.requireNonNull(jti, "jti");
		Objects.requireNonNull(expiresAt, "expiresAt");
		this.member = member;
		this.jti = jti;
		this.expiresAt = expiresAt;
	}

	public static RefreshToken create(Member member, UUID jti, Instant expiresAt) {
		return new RefreshToken(member, jti, expiresAt);
	}
}
