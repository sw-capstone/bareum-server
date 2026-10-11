package com.bareum.server.domain.auth.controller;

import com.bareum.server.domain.auth.dto.request.EmailLoginRequest;
import com.bareum.server.domain.auth.dto.response.EmailLoginResponse;
import com.bareum.server.domain.auth.service.EmailLoginService;
import com.bareum.server.domain.auth.service.LoginCookieFactory;

import jakarta.validation.Valid;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EmailLoginController implements EmailLoginControllerDocs {
    private final EmailLoginService service;
    private final LoginCookieFactory cookies;

    public EmailLoginController(EmailLoginService service, LoginCookieFactory cookies) {
        this.service = service;
        this.cookies = cookies;
    }

    @PostMapping(value = "/api/v1/auth/login/email", consumes = "application/json")
    public ResponseEntity<EmailLoginResponse> login(@Valid @RequestBody EmailLoginRequest request) {
        var issued = service.login(request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .header(HttpHeaders.SET_COOKIE, cookies.refreshCookie(issued))
                .body(cookies.response(issued));
    }
}
