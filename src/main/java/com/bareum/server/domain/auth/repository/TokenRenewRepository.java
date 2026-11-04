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
public class TokenRenewRepository {
	private final JdbcTemplate jdbc;

	public Optional<Session> findForUpdate(long memberId) {
		return jdbc.query(
				"SELECT id,jti,refresh_token_hash,expires_at,revoked_at FROM refresh_token WHERE member_id=? FOR UPDATE",
				(rs, n) -> new Session(rs.getLong(1), rs.getObject(2, UUID.class), rs.getString(3),
						rs.getTimestamp(4).toInstant(), rs.getTimestamp(5) != null),
				memberId).stream().findFirst();
	}

	public boolean wasConsumed(String hash, Session session, Instant now) {
		return !jdbc.query(
				"SELECT token_hash FROM used_refresh_token WHERE token_hash=? AND session_id=? AND family_id=? AND expires_at>?",
				(rs, n) -> rs.getString(1), hash, session.id(), session.family(), Timestamp.from(now)).isEmpty();
	}

	public boolean isKnownHash(String hash) {
		return !jdbc
				.query("SELECT token_hash FROM used_refresh_token WHERE token_hash=?", (rs, n) -> rs.getString(1), hash)
				.isEmpty();
	}

	public void revoke(Session session, Instant now) {
		jdbc.update("UPDATE refresh_token SET revoked_at=?,updated_at=? WHERE id=? AND revoked_at IS NULL",
				Timestamp.from(now), Timestamp.from(now), session.id());
	}

	public void rotate(Session session, String oldHash, String nextHash, Instant now) {
		jdbc.update(
				"INSERT INTO used_refresh_token(token_hash,session_id,family_id,consumed_at,expires_at) VALUES(?,?,?,?,?)",
				oldHash, session.id(), session.family(), Timestamp.from(now), Timestamp.from(session.expiresAt()));
		jdbc.update("UPDATE refresh_token SET refresh_token_hash=?,updated_at=? WHERE id=?",
				nextHash, Timestamp.from(now), session.id());
	}

	public int reserve(Session session, Instant now, long windowSeconds) {
		return jdbc.query("""
				INSERT INTO token_renew_window(session_id,family_id,window_started_at,request_count) VALUES(?,?,?,1)
				ON CONFLICT(session_id) DO UPDATE SET family_id=EXCLUDED.family_id,
				    window_started_at=CASE WHEN token_renew_window.family_id<>EXCLUDED.family_id
				        OR token_renew_window.window_started_at<=? THEN EXCLUDED.window_started_at
				        ELSE token_renew_window.window_started_at END,
				    request_count=CASE WHEN token_renew_window.family_id<>EXCLUDED.family_id
				        OR token_renew_window.window_started_at<=? THEN 1 ELSE token_renew_window.request_count+1 END
				RETURNING request_count
				""", (rs, n) -> rs.getInt(1), session.id(), session.family(), Timestamp.from(now),
				Timestamp.from(now.minusSeconds(windowSeconds)), Timestamp.from(now.minusSeconds(windowSeconds)))
				.getFirst();
	}

	public void deleteExpired(Instant now) {
		jdbc.update("DELETE FROM used_refresh_token WHERE expires_at<=?", Timestamp.from(now));
		jdbc.update("DELETE FROM token_renew_window WHERE window_started_at<?",
				Timestamp.from(now.minusSeconds(86400)));
	}

	public record Session(
		long id,
		UUID family,
		String refreshHash,
		Instant expiresAt,
		boolean revoked
	) {
		@Override
		public String toString() { return "Session[redacted]"; }
	}
}
