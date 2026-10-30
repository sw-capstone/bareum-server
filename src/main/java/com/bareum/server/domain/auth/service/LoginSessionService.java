package com.bareum.server.domain.auth.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bareum.server.domain.auth.config.LoginProperties;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.repository.LoginSessionRepository;
import com.bareum.server.domain.member.enums.MemberStatus;
import com.bareum.server.domain.member.enums.SignupMethod;
import com.bareum.server.global.security.LoginPrincipal;

@Service
public class LoginSessionService {
	private final LoginSessionRepository repository;
	private final LoginTokenGenerator tokens;
	private final LoginJwtService jwt;
	private final LoginProperties properties;
	private final Clock clock;

	public LoginSessionService(
		LoginSessionRepository repository,
		LoginTokenGenerator tokens,
		LoginJwtService jwt,
		LoginProperties properties,
		@Qualifier("loginClock") Clock clock
	) {
		this.repository = repository;
		this.tokens = tokens;
		this.jwt = jwt;
		this.properties = properties;
		this.clock = clock;
	}

	@Transactional
	public IssuedLogin issue(EmailLoginCredentialService.ValidatedMember validated) {
		var member = repository.findMemberForUpdate(validated.memberId())
				.filter(m -> m.getStatus() == MemberStatus.ACTIVE && m.getWithdrawnAt() == null
						&& m.getSignupMethod() == SignupMethod.LOCAL)
				.orElseThrow(() -> new AuthException(AuthErrorCode.LOGIN_ACCOUNT_RESTRICTED));
		if (!member.getPasswordHash().equals(validated.passwordHash()))
			throw new AuthException(AuthErrorCode.INVALID_LOGIN_CREDENTIALS);
		return issueForLockedMember(validated.memberId());
	}

	// 호출자는 같은 트랜잭션에서 회원 상태·인증 수단을 확인하고 회원 행을 잠가야 합니다.
	IssuedLogin issueForLockedMember(long memberId) {
		Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
		Instant accessExpiry = now.plusSeconds(properties.accessTtlSeconds());
		Instant sessionExpiry = now.plusSeconds(properties.sessionTtlSeconds());
		UUID family = UUID.randomUUID();
		String refresh = jwt.issue("refresh", memberId, family, now, sessionExpiry);
		repository.replaceRefresh(memberId, family, tokens.hash("refresh", refresh), sessionExpiry, now);
		String access = jwt.issue("access", memberId, family, now, accessExpiry);
		return new IssuedLogin(access, refresh, accessExpiry, sessionExpiry);
	}

	@Transactional(readOnly = true)
	public Optional<LoginPrincipal> authenticate(String access) {
		var verified = jwt.verify(access, "access");
		if (verified.isEmpty())
			return Optional.empty();
		var claims = verified.get();
		Instant now = clock.instant();
		return repository.findActivePrincipal(claims.memberId(), claims.family(), now);
	}

	@org.springframework.scheduling.annotation.Scheduled(fixedDelay = 3600000, initialDelay = 3600000)
	@Transactional
	public void purgeExpiredSessionRevocations() {
		repository.deleteExpiredRevocations(clock.instant());
	}

	public record IssuedLogin(
		String accessToken,
		String refreshToken,
		Instant accessExpiresAt,
		Instant sessionExpiresAt
	) {
		@Override
		public String toString() {
			return "IssuedLogin[redacted]";
		}
	}
}
