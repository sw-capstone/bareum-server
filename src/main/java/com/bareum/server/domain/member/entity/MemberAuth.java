package com.bareum.server.domain.member.entity;

import com.bareum.server.global.entity.BaseTimeEntity;

import com.bareum.server.domain.member.enums.OAuthProvider;
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
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(name = "member_auth", uniqueConstraints = @UniqueConstraint(columnNames = {"provider", "provider_user_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberAuth extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@Enumerated(EnumType.STRING)
	@Column(name = "provider", nullable = false, length = 30)
	private OAuthProvider provider;

	@Column(name = "provider_user_id", nullable = false, length = 255)
	private String providerUserId;

	@Column(name = "provider_email", nullable = true, length = 255)
	private String providerEmail;

	@Column(name = "linked_at", nullable = false)
	@ColumnDefault("now()")
	private Instant linkedAt;

	@Column(name = "unlinked_at", nullable = true)
	private Instant unlinkedAt;

	@Builder(access = AccessLevel.PRIVATE)
	private MemberAuth(
		Member member,
		OAuthProvider provider,
		String providerUserId,
		String providerEmail,
		Instant linkedAt
	) {
		Objects.requireNonNull(member, "member");
		Objects.requireNonNull(provider, "provider");
		Objects.requireNonNull(linkedAt, "linkedAt");
		this.member = member;
		this.provider = provider;
		this.providerUserId = providerUserId;
		this.providerEmail = providerEmail;
		this.linkedAt = linkedAt;
	}

	public static MemberAuth create(
		Member member,
		OAuthProvider provider,
		String providerUserId,
		String providerEmail,
		Instant linkedAt
	) {
		return MemberAuth.builder()
			.member(member)
			.provider(provider)
			.providerUserId(providerUserId)
			.providerEmail(providerEmail)
			.linkedAt(linkedAt)
			.build();
	}
}
