package com.bareum.server.domain.auth.repository;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class LoginAttemptRepository {
	private final JdbcTemplate jdbc;

	public String comparisonKey(String email) {
		return jdbc.queryForObject("SELECT upper(?)", String.class, email);
	}

	public boolean reserve(String key, Instant now, long windowSeconds, int maximum) {
		var windowStart = Timestamp.from(now.minusSeconds(windowSeconds));
		return !jdbc.query("""
				INSERT INTO login_request_window(email_key,window_started_at,request_count) VALUES(?,?,1)
				ON CONFLICT(email_key) DO UPDATE SET
				    window_started_at=CASE WHEN login_request_window.window_started_at <= ? THEN ?
				        ELSE login_request_window.window_started_at END,
				    request_count=CASE WHEN login_request_window.window_started_at <= ? THEN 1
				        ELSE login_request_window.request_count+1 END
				WHERE login_request_window.window_started_at <= ? OR login_request_window.request_count < ?
				RETURNING request_count
				""", (rs, row) -> rs.getInt(1), key, Timestamp.from(now), windowStart, Timestamp.from(now),
				windowStart, windowStart, maximum).isEmpty();
	}

	public void deleteBefore(Instant cutoff) {
		jdbc.update("DELETE FROM login_request_window WHERE window_started_at < ?", Timestamp.from(cutoff));
	}
}
