package com.bareum.server.domain.auth.controller;

import com.bareum.server.domain.auth.dto.response.CsrfTokenResponse;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CsrfTokenController implements CsrfTokenControllerDocs {
    @GetMapping("/api/v1/auth/csrf")
    public ResponseEntity<CsrfTokenResponse> csrf(CsrfToken csrf) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(new CsrfTokenResponse(csrf.getHeaderName(), csrf.getToken()));
    }
}
