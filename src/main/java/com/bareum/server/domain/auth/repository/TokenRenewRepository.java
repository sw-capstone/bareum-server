package com.bareum.server.domain.auth.repository;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.bareum.server.domain.auth.repository.LoginSessionRepository.Session;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class TokenRenewRepository {
	private final JdbcTemplate jdbc;

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

}
