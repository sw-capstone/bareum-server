package com.bareum.server.domain.auth.controller;

import com.bareum.server.domain.auth.dto.response.EmailAvailabilityResponse;
import com.bareum.server.domain.auth.service.EmailAvailabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth/email")
public class EmailAvailabilityController implements EmailAvailabilityControllerDocs {

    private final EmailAvailabilityService emailAvailabilityService;

    @GetMapping("/availability")
    public ResponseEntity<EmailAvailabilityResponse> checkAvailability(
            @RequestParam(name = "email")

            String email
    ) {
        return ResponseEntity.ok(
                emailAvailabilityService.checkAvailability(email)
        );
    }
}
