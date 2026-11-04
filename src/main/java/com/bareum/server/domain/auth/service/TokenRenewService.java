package com.bareum.server.domain.auth.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bareum.server.domain.auth.config.LoginProperties;
import com.bareum.server.domain.auth.config.TokenRenewProperties;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.exception.RefreshTokenReplayException;
import com.bareum.server.domain.auth.repository.LoginSessionRepository;
import com.bareum.server.domain.auth.repository.TokenRenewRepository;
import com.bareum.server.domain.member.enums.MemberStatus;

@Service
public class TokenRenewService {
	private final LoginSessionRepository sessions;
	private final TokenRenewRepository repository;
	private final LoginTokenGenerator tokens;
	private final LoginJwtService jwt;
	private final LoginProperties login;
	private final TokenRenewProperties policy;
	private final Clock clock;

	public TokenRenewService(
		LoginSessionRepository sessions,
		TokenRenewRepository repository,
		LoginTokenGenerator tokens,
		LoginJwtService jwt,
		LoginProperties login,
		TokenRenewProperties policy,
		Clock clock
	) {
		this.sessions = sessions;
		this.repository = repository;
		this.tokens = tokens;
		this.jwt = jwt;
		this.login = login;
		this.policy = policy;
		this.clock = clock;
	}

	@Transactional(noRollbackFor = RefreshTokenReplayException.class)
	public LoginSessionService.IssuedLogin renew(String refresh) {
		var verified = jwt.verify(refresh, "refresh").orElseThrow(this::invalid);
		var member = sessions.findMemberForUpdate(verified.memberId()).orElseThrow(this::invalid);
		var session = repository.findForUpdate(verified.memberId()).orElseThrow(this::invalid);
		if (!verified.family().equals(session.family()))
			throw invalid();
		Instant now = clock.instant();
		if (session.revoked() || !session.expiresAt().isAfter(now))
			throw invalid();
		String hash = tokens.hash("refresh", refresh);
		if (!hash.equals(session.refreshHash())) {
			if (repository.wasConsumed(hash, session, now)) {
				repository.revoke(session, now);
				sessions.revokeFamily(verified.memberId(), session.family(), now, session.expiresAt());
				throw new RefreshTokenReplayException();
			}
			throw invalid();
		}
		if (member.getStatus() != MemberStatus.ACTIVE || member.getWithdrawnAt() != null)
			throw new AuthException(AuthErrorCode.LOGIN_ACCOUNT_RESTRICTED);
		if (repository.reserve(session, now, policy.requestWindowSeconds()) > policy.maxRequestsPerWindow())
			throw new AuthException(AuthErrorCode.TOKEN_RENEW_LIMIT_EXCEEDED);
		now = now.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
		Instant accessExpiry = now.plusSeconds(login.accessTtlSeconds());
		if (accessExpiry.isAfter(session.expiresAt()))
			accessExpiry = session.expiresAt();
		String nextRefresh = jwt.issue("refresh", verified.memberId(), session.family(), now, session.expiresAt());
		String nextHash = tokens.hash("refresh", nextRefresh);
		if (hash.equals(nextHash) || repository.isKnownHash(nextHash))
			throw new AuthException(AuthErrorCode.LOGIN_SERVICE_UNAVAILABLE);
		repository.rotate(session, hash, nextHash, now);
		String access = jwt.issue("access", verified.memberId(), session.family(), now, accessExpiry);
		return new LoginSessionService.IssuedLogin(access, nextRefresh, accessExpiry, session.expiresAt());
	}

	@Scheduled(fixedDelay = 3600000, initialDelay = 3600000)
	public void purgeExpiredTokens() { repository.deleteExpired(clock.instant()); }

	private AuthException invalid() { return new AuthException(AuthErrorCode.INVALID_REFRESH_TOKEN); }
}
