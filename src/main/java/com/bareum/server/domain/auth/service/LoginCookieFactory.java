package com.bareum.server.domain.auth.service;

import com.bareum.server.domain.auth.config.LoginProperties;
import com.bareum.server.domain.auth.dto.response.EmailLoginResponse;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component
public class LoginCookieFactory {
    public static final String REFRESH_COOKIE = "bareum_refresh";
    private final LoginProperties properties;
    private final Clock clock;

    public LoginCookieFactory(LoginProperties properties, @Qualifier("loginClock") Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public EmailLoginResponse response(LoginSessionService.IssuedLogin issued) {
        Instant now = clock.instant();
        return new EmailLoginResponse(
                issued.accessToken(),
                "Bearer",
                remaining(now, issued.accessExpiresAt()),
                remaining(now, issued.sessionExpiresAt()));
    }

    public String refreshCookie(LoginSessionService.IssuedLogin issued) {
        return ResponseCookie.from(REFRESH_COOKIE, issued.refreshToken())
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite(properties.cookieSameSite())
                .path("/api/v1/auth")
                .maxAge(remaining(clock.instant(), issued.sessionExpiresAt()))
                .build()
                .toString();
    }

    public String expireRefreshCookie() {
        return expiredCookie(REFRESH_COOKIE);
    }

    public String expireCsrfCookie() {
        return expiredCookie("XSRF-TOKEN");
    }

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

    private long remaining(Instant now, Instant expiry) {
        Duration duration = Duration.between(now, expiry);
        return duration.isPositive() ? duration.getSeconds() + (duration.getNano() > 0 ? 1 : 0) : 0;
    }
}
