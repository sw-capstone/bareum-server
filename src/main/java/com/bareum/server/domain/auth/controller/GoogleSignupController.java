package com.bareum.server.domain.auth.controller;

import com.bareum.server.domain.auth.dto.request.GoogleSignupRequest;
import com.bareum.server.domain.auth.dto.response.EmailLoginResponse;
import com.bareum.server.domain.auth.service.GoogleSignupService;
import com.bareum.server.domain.auth.service.LoginCookieFactory;

import jakarta.validation.Valid;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class GoogleSignupController implements GoogleSignupControllerDocs {
    private final GoogleSignupService service;
    private final LoginCookieFactory cookies;

    public GoogleSignupController(GoogleSignupService service, LoginCookieFactory cookies) {
        this.service = service;
        this.cookies = cookies;
    }

    @PostMapping(value = "/api/v1/auth/signup/google", consumes = "application/json")
    public ResponseEntity<EmailLoginResponse> signup(
            @Valid @RequestBody GoogleSignupRequest request) {
        var issued = service.signup(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .header(HttpHeaders.SET_COOKIE, cookies.refreshCookie(issued))
                .body(cookies.response(issued));
    }
}
