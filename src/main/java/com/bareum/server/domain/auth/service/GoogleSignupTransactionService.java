package com.bareum.server.domain.auth.service;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bareum.server.domain.auth.dto.request.GoogleSignupRequest;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.repository.GoogleIdentityRepository;
import com.bareum.server.domain.member.entity.Member;
import com.bareum.server.domain.member.entity.MemberAuth;
import com.bareum.server.domain.member.enums.OAuthProvider;
import com.bareum.server.domain.member.enums.SignupMethod;
import com.bareum.server.domain.member.repository.MemberAuthRepository;
import com.bareum.server.domain.member.repository.MemberRepository;
import com.bareum.server.domain.term.entity.MemberTerm;
import com.bareum.server.domain.term.repository.MemberTermRepository;
import com.bareum.server.domain.term.service.SignupTermConsentValidator;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class GoogleSignupTransactionService {
	private final LoginTokenGenerator tokens;
	private final EmailVerificationRequestLock emailLock;
	private final MemberRepository members;
	private final MemberAuthRepository auths;
	private final MemberTermRepository memberTerms;
	private final SignupTermConsentValidator terms;
	private final LoginSessionService sessions;
	private final GoogleIdentityRepository identities;
	private final Clock clock;

	@Transactional
	public LoginSessionService.IssuedLogin signup(GoogleSignupRequest request) {
		String raw = request.signupToken();
		if (raw == null || !raw.matches(GoogleSignupRequest.TOKEN_PATTERN))
			throw invalid();
		String hash = tokens.hash("google-signup", raw);
		String subject = identities.findProofSubject(hash).orElseThrow(this::invalid);
		// 로그인에서 증명을 교체할 때와 같은 Google 식별자 락을 먼저 잡습니다.
		identities.lockSubject(subject);
		var proof = identities.findProofForUpdate(hash).orElseThrow(this::invalid);
		if (!subject.equals(proof.subject())
				|| proof.consumed()
				|| !clock.instant().isBefore(proof.expiresAt()))
			throw invalid();
		emailLock.lockForEmail(proof.email());
		if (auths.findMemberId(OAuthProvider.GOOGLE, subject).isPresent())
			throw invalid();
		var existing = members.findByEmailIgnoreCase(proof.email());
		if (existing.isPresent()) {
			throw new AuthException(existing.get().getSignupMethod() == SignupMethod.LOCAL
					? AuthErrorCode.EMAIL_LOGIN_REQUIRED : AuthErrorCode.EMAIL_ALREADY_REGISTERED);
		}
		var consents = terms.validate(request.agreements());
		var completedAt = clock.instant();
		if (!completedAt.isBefore(proof.expiresAt()))
			throw invalid();
		// 이메일·이름·Google 식별자는 검증된 증명에서만 가져옵니다.
		var member = members.saveAndFlush(Member.createGoogle(proof.email(), proof.name()));
		auths.saveAndFlush(
				MemberAuth.create(
						member, OAuthProvider.GOOGLE, subject, proof.email(), completedAt));
		memberTerms.saveAllAndFlush(
				consents.stream()
						.map(
								consent -> MemberTerm.create(
										member,
										consent.term(),
										consent.agreed() ? completedAt : null,
										null,
										consent.agreed(),
										completedAt))
						.toList());
		if (!identities.consumeProof(hash, clock.instant()))
			throw invalid();
		return sessions.issueForLockedMember(member.getId());
	}

	private AuthException invalid() {
		return new AuthException(AuthErrorCode.INVALID_GOOGLE_SIGNUP_PROOF);
	}
}
