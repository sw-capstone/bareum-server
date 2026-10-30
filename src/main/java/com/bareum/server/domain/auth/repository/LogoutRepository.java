package com.bareum.server.domain.auth.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class LogoutRepository {
	private final JdbcTemplate jdbc;

	public Optional<Session> findForUpdate(long memberId, UUID family) {
		return jdbc.query(
				"SELECT id,refresh_token_hash,expires_at FROM refresh_token WHERE member_id=? AND jti=? FOR UPDATE",
				(rs, n) -> new Session(rs.getLong(1), rs.getString(2), rs.getTimestamp(3).toInstant()),
				memberId, family).stream().findFirst();
	}

	public boolean wasConsumed(String hash, long sessionId, UUID family) {
		return !jdbc
				.query("SELECT token_hash FROM used_refresh_token WHERE token_hash=? AND session_id=? AND family_id=?",
						(rs, n) -> rs.getString(1), hash, sessionId, family)
				.isEmpty();
	}

	public void revoke(long sessionId, UUID family, Instant now) {
		jdbc.update("UPDATE refresh_token SET revoked_at=?,updated_at=? WHERE id=? AND jti=? AND revoked_at IS NULL",
				Timestamp.from(now), Timestamp.from(now), sessionId, family);
	}

	public record Session(
		long id,
		String refreshHash,
		Instant expiresAt
	) {
		@Override
		public String toString() { return "Session[redacted]"; }
	}
}
