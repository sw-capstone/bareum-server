package com.bareum.server.domain.auth.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bareum.server.domain.auth.config.GoogleLoginProperties;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.repository.GoogleIdentityRepository;
import com.bareum.server.domain.member.enums.MemberStatus;
import com.bareum.server.domain.member.enums.OAuthProvider;
import com.bareum.server.domain.member.enums.SignupMethod;
import com.bareum.server.domain.member.repository.MemberAuthRepository;
import com.bareum.server.domain.member.repository.MemberRepository;

@Service
public class GoogleLoginTransactionService {
	private final GoogleIdentityRepository repository;
	private final LoginSessionService sessions;
	private final LoginTokenGenerator tokens;
	private final GoogleSignupTokenGenerator signupTokens;
	private final MemberRepository members;
	private final MemberAuthRepository auths;
	private final GoogleLoginProperties policy;
	private final EmailVerificationRequestLock emailLock;
	private final Clock clock;

	public GoogleLoginTransactionService(
		GoogleIdentityRepository repository,
		LoginSessionService sessions,
		LoginTokenGenerator tokens,
		GoogleLoginProperties policy,
		EmailVerificationRequestLock emailLock,
		Clock clock,
		GoogleSignupTokenGenerator signupTokens,
		MemberRepository members,
		MemberAuthRepository auths
	) {
		this.repository = repository;
		this.sessions = sessions;
		this.tokens = tokens;
		this.policy = policy;
		this.emailLock = emailLock;
		this.clock = clock;
		this.signupTokens = signupTokens;
		this.members = members;
		this.auths = auths;
	}

	@Transactional
	public Result resolve(GoogleIdentity identity) {
		repository.lockSubject(identity.subject());
		var linked = auths.findMemberId(OAuthProvider.GOOGLE, identity.subject());
		if (linked.isPresent()) {
			long memberId = linked.get();
			var member = members.findByIdForUpdate(memberId)
					.filter(m -> m.getStatus() == MemberStatus.ACTIVE && m.getWithdrawnAt() == null
							&& m.getSignupMethod() == SignupMethod.GOOGLE)
					.orElseThrow(() -> new AuthException(AuthErrorCode.LOGIN_ACCOUNT_RESTRICTED));
			var auth = auths.findForUpdate(member.getId(), OAuthProvider.GOOGLE, identity.subject());
			if (auth.isEmpty() || auth.get().getUnlinkedAt() != null)
				throw new AuthException(AuthErrorCode.LOGIN_ACCOUNT_RESTRICTED);
			return new Result(sessions.issueForLockedMember(memberId), null, null);
		}
		emailLock.lockForEmail(identity.email());
		var existing = members.findByEmailIgnoreCase(identity.email());
		if (existing.isPresent()) {
			if (existing.get().getSignupMethod() == SignupMethod.LOCAL)
				throw new AuthException(AuthErrorCode.EMAIL_LOGIN_REQUIRED);
			throw new AuthException(AuthErrorCode.INVALID_GOOGLE_AUTHENTICATION);
		}
		// Gmail 또는 검증된 Workspace 도메인만 신규 가입 이메일의 소유권 증명으로 사용합니다.
		if (!identity.email().toLowerCase(Locale.ROOT).endsWith("@gmail.com")
				&& identity.hostedDomain() == null)
			throw new AuthException(AuthErrorCode.GOOGLE_EMAIL_VERIFICATION_REQUIRED);
		if (identity.name() == null || identity.name().isBlank())
			throw new AuthException(AuthErrorCode.INVALID_GOOGLE_AUTHENTICATION);
		Instant now = clock.instant(), expiry = now.plusSeconds(policy.signupTtlSeconds());
		String proof = signupTokens.generate();
		repository.replaceProof(tokens.hash("google-signup", proof), identity.subject(), identity.email(),
				identity.name(), expiry, now);
		return new Result(null, proof, expiry);
	}

	@Scheduled(fixedDelay = 3600000, initialDelay = 3600000)
	public void purgeExpiredProofs() {
		repository.deleteExpiredProofs(clock.instant());
	}

	public record Result(
		LoginSessionService.IssuedLogin login,
		String signupToken,
		Instant signupExpiresAt
	) {
		@Override
		public String toString() {
			return "GoogleLoginResult[redacted]";
		}
	}
}
