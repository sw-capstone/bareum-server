package com.bareum.server.domain.auth.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bareum.server.domain.auth.dto.response.CurrentUserResponse;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.member.enums.MemberStatus;
import com.bareum.server.domain.member.repository.MemberRepository;
import com.bareum.server.global.security.LoginPrincipal;

@RestController
public class CurrentUserController implements CurrentUserControllerDocs {
	private final MemberRepository members;

	public CurrentUserController(MemberRepository members) {
		this.members = members;
	}

	@GetMapping("/api/v1/auth/me")
	public ResponseEntity<CurrentUserResponse> me(
		@AuthenticationPrincipal LoginPrincipal principal
	) {
		var member =
				members.findById(principal.memberId())
						.filter(
								m -> m.getStatus() == MemberStatus.ACTIVE
										&& m.getWithdrawnAt() == null)
						.orElseThrow(() -> new AuthException(AuthErrorCode.INVALID_ACCESS_TOKEN));
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.body(new CurrentUserResponse(member.getId(), member.getName(), member.getEmail()));
	}
}
