package com.bareum.server.domain.auth.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.member.entity.Member;
import com.bareum.server.domain.member.enums.MemberStatus;
import com.bareum.server.domain.member.enums.SignupMethod;
import com.bareum.server.domain.member.repository.MemberRepository;

@Service
public class EmailLoginCredentialService {

	private final MemberRepository members;
	private final PasswordHashService passwordHashes;
	private final String dummyHash;

	public EmailLoginCredentialService(
		MemberRepository members,
		PasswordHashService passwordHashes
	) {
		this.members = members;
		this.passwordHashes = passwordHashes;
		this.dummyHash = passwordHashes.hash(UUID.randomUUID().toString());
	}

	// 세션 발급 직전에 다시 확인할 회원 ID와 비밀번호 해시를 반환합니다.
	@Transactional(readOnly = true)
	public ValidatedMember validateMember(String email, String password) {
		if (email == null || email.isBlank() || password == null || password.isEmpty()) {
			throw new AuthException(AuthErrorCode.INVALID_LOGIN_CREDENTIALS);
		}

		Member member = members.findByEmailIgnoreCase(email).orElse(null);
		if (member == null) {
			passwordHashes.matches(password, dummyHash);
			throw new AuthException(AuthErrorCode.INVALID_LOGIN_CREDENTIALS);
		}
		if (member.getSignupMethod() == SignupMethod.GOOGLE) {
			passwordHashes.matches(password, dummyHash);
			if (member.getStatus() != MemberStatus.ACTIVE || member.getWithdrawnAt() != null) {
				throw new AuthException(AuthErrorCode.LOGIN_ACCOUNT_RESTRICTED);
			}
			throw new AuthException(AuthErrorCode.GOOGLE_LOGIN_REQUIRED);
		}
		if (member.getSignupMethod() != SignupMethod.LOCAL
				|| !passwordHashes.matches(password, member.getPasswordHash())) {
			throw new AuthException(AuthErrorCode.INVALID_LOGIN_CREDENTIALS);
		}
		if (member.getStatus() != MemberStatus.ACTIVE || member.getWithdrawnAt() != null) {
			throw new AuthException(AuthErrorCode.LOGIN_ACCOUNT_RESTRICTED);
		}
		return new ValidatedMember(member.getId(), member.getPasswordHash());
	}

	public record ValidatedMember(
		Long memberId,
		String passwordHash
	) {
		@Override
		public String toString() {
			return "ValidatedMember[redacted]";
		}
	}
}
