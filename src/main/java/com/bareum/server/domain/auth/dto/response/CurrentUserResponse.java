package com.bareum.server.domain.auth.dto.response;

import com.bareum.server.domain.member.entity.Member;

public record CurrentUserResponse(
	Long memberId,
	String name,
	String email
) {
	public static CurrentUserResponse from(Member member) {
		return new CurrentUserResponse(member.getId(), member.getName(), member.getEmail());
	}

	@Override
	public String toString() {
		return "CurrentUserResponse[redacted]";
	}
}
