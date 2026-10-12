package com.bareum.server.domain.auth.controller;

import com.bareum.server.domain.auth.dto.request.PasswordResetRequest;
import com.bareum.server.domain.auth.service.PasswordResetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth/password/reset")
public class PasswordResetController implements PasswordResetControllerDocs {
    private final PasswordResetService service;

    @PatchMapping
    public ResponseEntity<Void> reset(@Valid @RequestBody PasswordResetRequest request) {
        service.reset(request);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
