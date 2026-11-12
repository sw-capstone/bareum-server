package com.bareum.server.domain.auth.controller;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bareum.server.domain.auth.dto.request.EmailLoginRequest;
import com.bareum.server.domain.auth.dto.request.GoogleLoginRequest;
import com.bareum.server.domain.auth.dto.request.GoogleSignupRequest;
import com.bareum.server.domain.auth.dto.response.CsrfTokenResponse;
import com.bareum.server.domain.auth.dto.response.CurrentUserResponse;
import com.bareum.server.domain.auth.dto.response.EmailLoginResponse;
import com.bareum.server.domain.auth.dto.response.GoogleLoginResponse;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.service.CurrentUserService;
import com.bareum.server.domain.auth.service.EmailLoginService;
import com.bareum.server.domain.auth.service.GoogleLoginService;
import com.bareum.server.domain.auth.service.GoogleSignupService;
import com.bareum.server.domain.auth.service.LoginCookieFactory;
import com.bareum.server.domain.auth.service.LogoutService;
import com.bareum.server.domain.auth.service.TokenRenewService;
import com.bareum.server.global.security.LoginPrincipal;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController implements AuthControllerDocs {
	private final EmailLoginService service;
	private final CurrentUserService currentUsers;
	private final LoginCookieFactory cookies;
	private final TokenRenewService renewals;
	private final LogoutService logoutService;
	private final GoogleLoginService googleLoginService;
	private final GoogleSignupService googleSignupService;
	private final Clock clock;

	@PostMapping(value = "/login/email", consumes = "application/json")
	public ResponseEntity<EmailLoginResponse> login(@Valid @RequestBody EmailLoginRequest request) {
		var issued = service.login(request);

		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.header("Pragma", "no-cache")
				.header(HttpHeaders.SET_COOKIE, cookies.refreshCookie(issued))
				.body(EmailLoginResponse.from(issued, clock.instant()));
	}

	@GetMapping("/me")
	public ResponseEntity<CurrentUserResponse> me(@AuthenticationPrincipal LoginPrincipal principal) {
		var member = currentUsers.get(principal.memberId());
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(CurrentUserResponse.from(member));
	}

	@PostMapping("/token/renew")
	public ResponseEntity<EmailLoginResponse> renew(HttpServletRequest request) {
		var values = refreshCookieValues(request);
		if (values.size() != 1)
			throw new AuthException(AuthErrorCode.INVALID_REFRESH_TOKEN);
		var issued = renewals.renew(values.getFirst());
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.header("Pragma", "no-cache")
				.header(HttpHeaders.SET_COOKIE, cookies.refreshCookie(issued))
				.body(EmailLoginResponse.from(issued, clock.instant()));
	}

	@GetMapping("/csrf")
	public ResponseEntity<CsrfTokenResponse> csrf(CsrfToken csrf) {
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.header("Pragma", "no-cache")
				.body(new CsrfTokenResponse(csrf.getHeaderName(), csrf.getToken()));
	}

	@PostMapping("/logout")
	public ResponseEntity<Void> logout(HttpServletRequest request) {
		var values = refreshCookieValues(request);
		if (values.size() > 1)
			throw new AuthException(AuthErrorCode.INVALID_REFRESH_TOKEN);
		logoutService.logout(values.isEmpty() ? null : values.getFirst());
		return ResponseEntity.noContent()
				.cacheControl(CacheControl.noStore())
				.header("Pragma", "no-cache")
				.header(HttpHeaders.SET_COOKIE, cookies.expireRefreshCookie(), cookies.expireCsrfCookie())
				.build();
	}

	private List<String> refreshCookieValues(HttpServletRequest request) {
		Cookie[] supplied = request.getCookies();
		return supplied == null ? List.of() : Arrays.stream(supplied)
				.filter(cookie -> LoginCookieFactory.REFRESH_COOKIE.equals(cookie.getName()))
				.map(Cookie::getValue)
				.toList();
	}

	@PostMapping(value = "/login/google", consumes = "application/json")
	public ResponseEntity<GoogleLoginResponse> googleLogin(@Valid @RequestBody GoogleLoginRequest request) {
		var result = googleLoginService.login(request.idToken());
		var now = clock.instant();
		var response = ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache");
		if (result.login() != null)
			return response.header(HttpHeaders.SET_COOKIE, cookies.refreshCookie(result.login()))
					.body(GoogleLoginResponse.login(result.login(), now));
		return response.body(
				GoogleLoginResponse.signup(result.signupToken(), result.signupExpiresAt(), now));
	}

	@PostMapping(value = "/signup/google", consumes = "application/json")
	public ResponseEntity<EmailLoginResponse> googleSignup(@Valid @RequestBody GoogleSignupRequest request) {
		var issued = googleSignupService.signup(request);
		return ResponseEntity.status(HttpStatus.CREATED)
				.cacheControl(CacheControl.noStore())
				.header("Pragma", "no-cache")
				.header(HttpHeaders.SET_COOKIE, cookies.refreshCookie(issued))
				.body(EmailLoginResponse.from(issued, clock.instant()));
	}
}
