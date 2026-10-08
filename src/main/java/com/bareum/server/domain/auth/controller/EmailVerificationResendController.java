package com.bareum.server.domain.auth.controller;

import com.bareum.server.domain.auth.dto.request.EmailVerificationRequest;
import com.bareum.server.domain.auth.dto.response.EmailVerificationSendResponse;
import com.bareum.server.domain.auth.service.EmailVerificationResendService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth/email-verification/resend")
public class EmailVerificationResendController implements EmailVerificationResendControllerDocs {

    private final EmailVerificationResendService emailVerificationResendService;

    @PostMapping
    public ResponseEntity<EmailVerificationSendResponse> resend(
            @Valid @RequestBody EmailVerificationRequest request
    ) {
        return ResponseEntity.ok(emailVerificationResendService.resend(request));
    }
}
