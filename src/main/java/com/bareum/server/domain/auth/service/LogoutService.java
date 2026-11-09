package com.bareum.server.domain.auth.service;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bareum.server.domain.auth.repository.LoginSessionRepository;

@Service
public class LogoutService {
	private final LoginSessionRepository sessions;
	private final LoginTokenGenerator tokens;
	private final LoginJwtService jwt;
	private final Clock clock;

	public LogoutService(
		LoginSessionRepository sessions,
		LoginTokenGenerator tokens,
		LoginJwtService jwt,
		Clock clock
	) {
		this.sessions = sessions;
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
		if (sessions.findMemberForUpdate(claims.memberId()).isEmpty())
			return;
		var locked = sessions.findRefreshForUpdate(claims.memberId());
		if (locked.isEmpty() || !claims.family().equals(locked.get().family()))
			return;
		var session = locked.get();
		String hash = tokens.hash("refresh", refresh);
		if (!hash.equals(session.refreshHash())
				&& !sessions.wasConsumed(hash, session))
			return;
		var now = clock.instant();
		sessions.revokeRefresh(session, now);
		sessions.revokeFamily(claims.memberId(), claims.family(), now, session.expiresAt());
	}
}
