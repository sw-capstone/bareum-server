package com.bareum.server.domain.auth.controller;

import com.bareum.server.domain.auth.dto.request.EmailSignupRequest;
import com.bareum.server.domain.auth.dto.response.EmailSignupResponse;
import com.bareum.server.domain.auth.service.EmailSignupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth/signup")
public class EmailSignupController implements EmailSignupControllerDocs {

    private final EmailSignupService signupService;

    @PostMapping
    public ResponseEntity<EmailSignupResponse> signup(@Valid @RequestBody EmailSignupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(signupService.signup(request));
    }
}
