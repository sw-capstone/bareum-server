package com.bareum.server.domain.auth.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.member.entity.Member;
import com.bareum.server.domain.member.enums.MemberStatus;
import com.bareum.server.domain.member.repository.MemberRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CurrentUserService {
	private final MemberRepository members;

	@Transactional(readOnly = true)
	public Member get(long memberId) {
		return members.findById(memberId)
				.filter(member -> member.getStatus() == MemberStatus.ACTIVE && member.getWithdrawnAt() == null)
				.orElseThrow(() -> new AuthException(AuthErrorCode.INVALID_ACCESS_TOKEN));
	}
}
