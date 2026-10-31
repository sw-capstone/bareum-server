package com.bareum.server.domain.auth.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.bareum.server.domain.member.entity.Member;
import com.bareum.server.domain.member.enums.MemberStatus;
import com.bareum.server.domain.member.repository.MemberRepository;
import com.bareum.server.global.security.LoginPrincipal;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class LoginSessionRepository {
	private final MemberRepository members;
	private final LoginSessionQueryRepository sessions;
	private final JdbcTemplate jdbc;

	public Optional<Member> findMemberForUpdate(long memberId) {
		return members.findByIdForUpdate(memberId);
	}

	public void replaceRefresh(
		long memberId,
		UUID family,
		String refreshHash,
		Instant sessionExpiry,
		Instant now
	) {
		jdbc.update("""
				INSERT INTO refresh_token(member_id,jti,expires_at,created_at,updated_at,
				    refresh_token_hash,revoked_at)
				VALUES(?,?,?,?,?,?,NULL)
				ON CONFLICT(member_id) DO UPDATE SET jti=EXCLUDED.jti,expires_at=EXCLUDED.expires_at,
				    created_at=EXCLUDED.created_at,updated_at=EXCLUDED.updated_at,
				    refresh_token_hash=EXCLUDED.refresh_token_hash,revoked_at=NULL
				""", memberId, family, Timestamp.from(sessionExpiry), Timestamp.from(now), Timestamp.from(now),
				refreshHash);
	}

	public Optional<LoginPrincipal> findActivePrincipal(long memberId, UUID family, Instant now) {
		return sessions.findActivePrincipal(memberId, family, now, MemberStatus.ACTIVE);
	}

	public void revokeFamily(long memberId, UUID family, Instant now, Instant expiresAt) {
		if (!expiresAt.isAfter(now))
			return;
		jdbc.update("""
				INSERT INTO revoked_login_session(family_id,member_id,revoked_at,expires_at) VALUES(?,?,?,?)
				ON CONFLICT(family_id) DO UPDATE SET
				    expires_at=GREATEST(revoked_login_session.expires_at,EXCLUDED.expires_at)
				WHERE revoked_login_session.member_id=EXCLUDED.member_id
				""", family, memberId, Timestamp.from(now), Timestamp.from(expiresAt));
	}

	public void deleteExpiredRevocations(Instant now) {
		sessions.deleteExpired(now);
	}
}
