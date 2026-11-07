package com.bareum.server.domain.auth.service;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bareum.server.domain.auth.repository.LoginSessionRepository;
import com.bareum.server.domain.auth.repository.LogoutRepository;

@Service
public class LogoutService {
	private final LoginSessionRepository sessions;
	private final LogoutRepository repository;
	private final LoginTokenGenerator tokens;
	private final LoginJwtService jwt;
	private final Clock clock;

	public LogoutService(
		LoginSessionRepository sessions,
		LogoutRepository repository,
		LoginTokenGenerator tokens,
		LoginJwtService jwt,
		@Qualifier("loginClock") Clock clock
	) {
		this.sessions = sessions;
		this.repository = repository;
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
		var locked = repository.findByMemberIdAndJti(claims.memberId(), claims.family());
		if (locked.isEmpty())
			return;
		var session = locked.get();
		String hash = tokens.hash("refresh", refresh);
		if (!hash.equals(session.getRefreshTokenHash())
				&& !repository.wasConsumed(hash, session.getId(), claims.family()))
			return;
		var now = clock.instant();
		repository.revoke(session.getId(), claims.family(), now);
		sessions.revokeFamily(claims.memberId(), claims.family(), now, session.getExpiresAt());
	}
}
