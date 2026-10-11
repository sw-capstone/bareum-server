package com.bareum.server.domain.auth.controller;

import com.bareum.server.domain.auth.dto.request.GoogleLoginRequest;
import com.bareum.server.domain.auth.dto.response.GoogleLoginResponse;
import com.bareum.server.domain.auth.service.GoogleLoginService;
import com.bareum.server.domain.auth.service.LoginCookieFactory;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.Duration;

@RestController
public class GoogleLoginController implements GoogleLoginControllerDocs {
    private final GoogleLoginService service;
    private final LoginCookieFactory cookies;
    private final Clock clock;

    public GoogleLoginController(
            GoogleLoginService service,
            LoginCookieFactory cookies,
            @Qualifier("loginClock") Clock clock) {
        this.service = service;
        this.cookies = cookies;
        this.clock = clock;
    }

    @PostMapping(value = "/api/v1/auth/login/google", consumes = "application/json")
    public ResponseEntity<GoogleLoginResponse> login(
            @Valid @RequestBody GoogleLoginRequest request) {
        var result = service.login(request.idToken());
        var response =
                ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .header("Pragma", "no-cache");
        if (result.login() != null)
            return response.header(HttpHeaders.SET_COOKIE, cookies.refreshCookie(result.login()))
                    .body(GoogleLoginResponse.login(cookies.response(result.login())));
        var duration = Duration.between(clock.instant(), result.signupExpiresAt());
        long seconds =
                duration.isPositive()
                        ? duration.getSeconds() + (duration.getNano() > 0 ? 1 : 0)
                        : 0;
        return response.body(GoogleLoginResponse.signup(result.signupToken(), seconds));
    }
}
