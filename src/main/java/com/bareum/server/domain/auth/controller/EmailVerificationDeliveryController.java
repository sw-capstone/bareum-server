package com.bareum.server.domain.auth.controller;

import com.bareum.server.domain.auth.dto.response.EmailVerificationDeliveryResponse;
import com.bareum.server.domain.auth.service.EmailVerificationDeliveryService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class EmailVerificationDeliveryController implements EmailVerificationDeliveryControllerDocs {
    private final EmailVerificationDeliveryService deliveryService;

    @GetMapping("/api/v1/auth/email-verification/deliveries/{requestId}")
    public ResponseEntity<EmailVerificationDeliveryResponse> status(@PathVariable UUID requestId) {
        return deliveryService.status(requestId)
                .map(body -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body))
                .orElseGet(() -> ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build());
    }
}
