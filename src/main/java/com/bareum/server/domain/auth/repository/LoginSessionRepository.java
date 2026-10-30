package com.bareum.server.domain.auth.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.bareum.server.domain.auth.entity.LoginAccessToken;
import com.bareum.server.domain.member.entity.Member;
import com.bareum.server.domain.member.repository.MemberRepository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class LoginSessionRepository {
	private final MemberRepository members;
	private final LoginAccessTokenQueryRepository accessTokens;
	private final JdbcTemplate jdbc;
	private final EntityManager entityManager;

	public Optional<Member> findMemberForUpdate(long memberId) {
		return members.findByIdForUpdate(memberId);
	}

	public void replaceRefresh(
		long memberId,
		UUID family,
		String accessHash,
		String refreshHash,
		Instant accessExpiry,
		Instant sessionExpiry,
		Instant now
	) {
		jdbc.update("""
				INSERT INTO refresh_token(member_id,jti,expires_at,created_at,updated_at,
				    access_token_hash,refresh_token_hash,access_expires_at,revoked_at)
				VALUES(?,?,?,?,?,?,?,?,NULL)
				ON CONFLICT(member_id) DO UPDATE SET jti=EXCLUDED.jti,expires_at=EXCLUDED.expires_at,
				    created_at=EXCLUDED.created_at,updated_at=EXCLUDED.updated_at,
				    access_token_hash=EXCLUDED.access_token_hash,refresh_token_hash=EXCLUDED.refresh_token_hash,
				    access_expires_at=EXCLUDED.access_expires_at,revoked_at=NULL
				""", memberId, family, Timestamp.from(sessionExpiry), Timestamp.from(now), Timestamp.from(now),
				accessHash, refreshHash, Timestamp.from(accessExpiry));
	}

	public void storeAccess(String hash, long memberId, UUID family, Instant now, Instant expiry) {
		entityManager.persist(LoginAccessToken.create(hash, entityManager.getReference(Member.class, memberId),
				family, expiry, now));
		entityManager.flush();
	}

	public void deleteExpiredAccess(Instant now) {
		accessTokens.deleteExpired(now);
	}
}
