package com.bareum.server.domain.auth.service;

import java.sql.Timestamp;
import java.time.Clock;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LogoutService {
	private final JdbcTemplate jdbc;
	private final LoginTokenGenerator tokens;
	private final LoginJwtService jwt;
	private final Clock clock;

	public LogoutService(
		JdbcTemplate jdbc,
		LoginTokenGenerator tokens,
		LoginJwtService jwt,
		@Qualifier("loginClock") Clock clock
	) {
		this.jdbc = jdbc;
		this.tokens = tokens;
		this.jwt = jwt;
		this.clock = clock;
	}

	@Transactional
	public void logout(String refresh) {
		var verified = jwt.verifyForLogout(refresh);
		if (verified.isEmpty())
			return;
		var claims = verified.get();
		String hash = tokens.hash("refresh", refresh);
		// Match login and renewal's member -> session lock order.
		var members = jdbc.query("SELECT id FROM member WHERE id=? FOR UPDATE",
				(rs, n) -> rs.getLong(1), claims.memberId());
		if (members.isEmpty())
			return;
		var sessions = jdbc.query("""
				SELECT id,refresh_token_hash FROM refresh_token
				    WHERE member_id=? AND jti=? FOR UPDATE
				""", (rs, n) -> new Session(rs.getLong(1), rs.getString(2)),
				claims.memberId(), claims.family());
		if (sessions.isEmpty())
			return;
		Session session = sessions.getFirst();
		if (!hash.equals(session.refreshHash())) {
			Integer consumed = jdbc.queryForObject("""
					SELECT count(*) FROM used_refresh_token
					    WHERE token_hash=? AND session_id=? AND family_id=?
					""", Integer.class, hash, session.id(), claims.family());
			if (consumed == null || consumed == 0)
				return;
		}
		var now = Timestamp.from(clock.instant());
		jdbc.update("""
				UPDATE refresh_token SET revoked_at=?,updated_at=?
				    WHERE id=? AND jti=? AND revoked_at IS NULL
				""", now, now, session.id(), claims.family());
		jdbc.update(
				"UPDATE login_access_token SET revoked_at=? WHERE member_id=? AND family_id=? AND revoked_at IS NULL",
				now, claims.memberId(), claims.family());
	}

	private record Session(
		long id,
		String refreshHash
	) {
		@Override
		public String toString() { return "Session[redacted]"; }
	}
}
