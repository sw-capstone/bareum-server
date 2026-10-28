package com.bareum.server.domain.auth.dto.response;

public record CurrentUserResponse(
	Long memberId,
	String name,
	String email
) {
	@Override
	public String toString() {
		return "CurrentUserResponse[redacted]";
	}
}
