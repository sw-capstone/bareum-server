package com.bareum.server.domain.auth.controller;

import java.util.Arrays;
import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bareum.server.domain.auth.dto.request.EmailLoginRequest;
import com.bareum.server.domain.auth.dto.response.CsrfTokenResponse;
import com.bareum.server.domain.auth.dto.response.CurrentUserResponse;
import com.bareum.server.domain.auth.dto.response.EmailLoginResponse;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.service.EmailLoginService;
import com.bareum.server.domain.auth.service.LoginCookieFactory;
import com.bareum.server.domain.auth.service.LogoutService;
import com.bareum.server.domain.auth.service.TokenRenewService;
import com.bareum.server.domain.member.enums.MemberStatus;
import com.bareum.server.domain.member.repository.MemberRepository;
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
	private final MemberRepository members;
	private final LoginCookieFactory cookies;
	private final TokenRenewService renewals;
	private final LogoutService logoutService;

	@PostMapping(value = "/login/email", consumes = "application/json")
	public ResponseEntity<EmailLoginResponse> login(@Valid @RequestBody EmailLoginRequest request) {
		var issued = service.login(request);

		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.header("Pragma", "no-cache")
				.header(HttpHeaders.SET_COOKIE, cookies.refreshCookie(issued))
				.body(cookies.response(issued));
	}

	@GetMapping("/me")
	public ResponseEntity<CurrentUserResponse> me(@AuthenticationPrincipal LoginPrincipal principal) {
		var member = members.findById(principal.memberId())
				.filter(m -> m.getStatus() == MemberStatus.ACTIVE && m.getWithdrawnAt() == null)
				.orElseThrow(() -> new AuthException(AuthErrorCode.INVALID_ACCESS_TOKEN));
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(CurrentUserResponse.from(member));
	}

	@PostMapping("/token/renew")
	public ResponseEntity<EmailLoginResponse> renew(HttpServletRequest request) {
		Cookie[] supplied = request.getCookies();
		var values =
				supplied == null
						? java.util.List.<String>of()
						: Arrays.stream(supplied)
								.filter(
										cookie -> LoginCookieFactory.REFRESH_COOKIE.equals(
												cookie.getName()))
								.map(Cookie::getValue)
								.toList();
		if (values.size() != 1)
			throw new AuthException(AuthErrorCode.INVALID_REFRESH_TOKEN);
		try {
			var issued = renewals.renew(values.getFirst());
			return ResponseEntity.ok()
					.cacheControl(CacheControl.noStore())
					.header("Pragma", "no-cache")
					.header(HttpHeaders.SET_COOKIE, cookies.refreshCookie(issued))
					.body(cookies.response(issued));
		} catch (DataAccessException | TransactionException failure) {
			throw new AuthException(AuthErrorCode.LOGIN_SERVICE_UNAVAILABLE);
		}
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
		Cookie[] supplied = request.getCookies();
		var values = supplied == null ? List.<String>of() : Arrays.stream(supplied)
				.filter(c -> LoginCookieFactory.REFRESH_COOKIE.equals(c.getName()))
				.map(Cookie::getValue)
				.toList();
		if (values.size() > 1)
			throw new AuthException(AuthErrorCode.INVALID_REFRESH_TOKEN);
		try {
			logoutService.logout(values.isEmpty() ? null : values.getFirst());
			return ResponseEntity.noContent()
					.cacheControl(CacheControl.noStore())
					.header("Pragma", "no-cache")
					.header(HttpHeaders.SET_COOKIE, cookies.expireRefreshCookie(), cookies.expireCsrfCookie())
					.build();
		} catch (DataAccessException | TransactionException failure) {
			throw new AuthException(AuthErrorCode.LOGIN_SERVICE_UNAVAILABLE);
		}
	}
}
