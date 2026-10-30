package com.bareum.server.domain.auth.controller;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bareum.server.domain.auth.config.LoginProperties;
import com.bareum.server.domain.auth.dto.request.EmailLoginRequest;
import com.bareum.server.domain.auth.dto.response.CurrentUserResponse;
import com.bareum.server.domain.auth.dto.response.EmailLoginResponse;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.service.EmailLoginService;
import com.bareum.server.domain.member.enums.MemberStatus;
import com.bareum.server.domain.member.repository.MemberRepository;
import com.bareum.server.global.security.LoginPrincipal;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController implements AuthControllerDocs {
	private final EmailLoginService service;
	private final MemberRepository members;
	private final LoginProperties properties;
	private final Clock clock;

	public AuthController(
		EmailLoginService service,
		MemberRepository members,
		LoginProperties properties,
		@Qualifier("loginClock") Clock clock
	) {
		this.service = service;
		this.members = members;
		this.properties = properties;
		this.clock = clock;
	}

	@PostMapping(value = "/login/email", consumes = "application/json")
	public ResponseEntity<EmailLoginResponse> login(@Valid @RequestBody EmailLoginRequest request) {
		var issued = service.login(request);
		Instant now = clock.instant();
		long access = remaining(now, issued.accessExpiresAt());
		long session = remaining(now, issued.sessionExpiresAt());
		String cookie =
				ResponseCookie.from("bareum_refresh", issued.refreshToken())
						.httpOnly(true)
						.secure(properties.cookieSecure())
						.sameSite(properties.cookieSameSite())
						.path("/api/v1/auth")
						.maxAge(session)
						.build()
						.toString();
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.header("Pragma", "no-cache")
				.header(HttpHeaders.SET_COOKIE, cookie)
				.body(EmailLoginResponse.of(issued.accessToken(), access, session));
	}

	@GetMapping("/me")
	public ResponseEntity<CurrentUserResponse> me(@AuthenticationPrincipal LoginPrincipal principal) {
		var member = members.findById(principal.memberId())
				.filter(m -> m.getStatus() == MemberStatus.ACTIVE && m.getWithdrawnAt() == null)
				.orElseThrow(() -> new AuthException(AuthErrorCode.INVALID_ACCESS_TOKEN));
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(CurrentUserResponse.from(member));
	}

	private long remaining(Instant now, Instant expiry) {
		Duration d = Duration.between(now, expiry);
		return d.isPositive() ? d.getSeconds() + (d.getNano() > 0 ? 1 : 0) : 0;
	}
}
