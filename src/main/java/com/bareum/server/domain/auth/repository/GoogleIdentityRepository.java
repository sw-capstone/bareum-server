package com.bareum.server.domain.auth.repository;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class GoogleIdentityRepository {
	private final JdbcTemplate jdbc;

	public void lockSubject(String subject) {
		jdbc.execute("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
				(PreparedStatementCallback<Void>)statement -> {
					statement.setString(1, "bareum:google:" + subject);
					statement.execute();
					return null;
				});
	}

	public void replaceProof(String hash, String subject, String email, String name, Instant expiry, Instant now) {
		jdbc.update("DELETE FROM google_signup_proof WHERE google_subject=?", subject);
		jdbc.update("""
				INSERT INTO google_signup_proof(token_hash,google_subject,email,name,expires_at,created_at)
				VALUES(?,?,?,?,?,?)
				""", hash, subject, email, name, Timestamp.from(expiry), Timestamp.from(now));
	}

	public void deleteExpiredProofs(Instant now) {
		jdbc.update("DELETE FROM google_signup_proof WHERE expires_at<=?", Timestamp.from(now));
	}
}
