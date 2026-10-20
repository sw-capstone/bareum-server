package com.bareum.server.domain.auth.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bareum.server.domain.auth.dto.request.EmailVerificationRequest;
import com.bareum.server.domain.auth.dto.response.EmailVerificationDeliveryResponse;
import com.bareum.server.domain.auth.service.EmailVerificationSendService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth/email-verification")
public class EmailVerificationSendController implements EmailVerificationSendControllerDocs {

	private final EmailVerificationSendService emailVerificationSendService;

	@PostMapping
	public ResponseEntity<EmailVerificationDeliveryResponse> send(
		@Valid @RequestBody EmailVerificationRequest request
	) {
		var response = emailVerificationSendService.send(request);
		return ResponseEntity.accepted()
				.location(java.net.URI.create("/api/v1/auth/email-verification/deliveries/" + response.requestId()))
				.cacheControl(org.springframework.http.CacheControl.noStore())
				.body(response);
	}
}
