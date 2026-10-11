package com.bareum.server.domain.auth.controller;

import com.bareum.server.domain.auth.config.LoginProperties;
import com.bareum.server.domain.auth.dto.request.EmailLoginRequest;
import com.bareum.server.domain.auth.dto.response.EmailLoginResponse;
import com.bareum.server.domain.auth.service.EmailLoginService;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@RestController
public class EmailLoginController implements EmailLoginControllerDocs {
    private final EmailLoginService service;
    private final LoginProperties properties;
    private final Clock clock;

    public EmailLoginController(
            EmailLoginService service,
            LoginProperties properties,
            @Qualifier("loginClock") Clock clock) {
        this.service = service;
        this.properties = properties;
        this.clock = clock;
    }

    @PostMapping(value = "/api/v1/auth/login/email", consumes = "application/json")
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
                .body(new EmailLoginResponse(issued.accessToken(), "Bearer", access, session));
    }

    private long remaining(Instant now, Instant expiry) {
        Duration d = Duration.between(now, expiry);
        return d.isPositive() ? d.getSeconds() + (d.getNano() > 0 ? 1 : 0) : 0;
    }
}
