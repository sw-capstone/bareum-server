package com.bareum.server.domain.auth.service;

import java.time.Clock;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.bareum.server.domain.auth.config.LoginProperties;
import com.bareum.server.domain.auth.support.TokenExpiry;

@Component
public class LoginCookieFactory {
	public static final String REFRESH_COOKIE = "bareum_refresh";
	private final LoginProperties properties;
	private final Clock clock;

	public LoginCookieFactory(LoginProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
	}

	public String refreshCookie(LoginSessionService.IssuedLogin issued) {
		return ResponseCookie.from(REFRESH_COOKIE, issued.refreshToken())
				.httpOnly(true)
				.secure(properties.cookieSecure())
				.sameSite(properties.cookieSameSite())
				.path("/api/v1/auth")
				.maxAge(TokenExpiry.remainingSeconds(clock.instant(), issued.sessionExpiresAt()))
				.build()
				.toString();
	}

	public String expireRefreshCookie() { return expiredCookie(REFRESH_COOKIE); }

	public String expireCsrfCookie() { return expiredCookie("XSRF-TOKEN"); }

	private String expiredCookie(String name) {
		return ResponseCookie.from(name, "")
				.httpOnly(true)
				.secure(properties.cookieSecure())
				.sameSite(properties.cookieSameSite())
				.path("/api/v1/auth")
				.maxAge(0)
				.build()
				.toString();
	}
}
