package com.bareum.server.domain.auth.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bareum.server.domain.auth.dto.request.EmailVerificationConfirmRequest;
import com.bareum.server.domain.auth.dto.request.EmailVerificationRequest;
import com.bareum.server.domain.auth.dto.response.EmailVerificationConfirmResponse;
import com.bareum.server.domain.auth.dto.response.EmailVerificationDeliveryResponse;
import com.bareum.server.domain.auth.service.EmailVerificationConfirmService;
import com.bareum.server.domain.auth.service.EmailVerificationDeliveryService;
import com.bareum.server.domain.auth.service.EmailVerificationResendService;
import com.bareum.server.domain.auth.service.EmailVerificationSendService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth/email-verification")
public class EmailVerificationController implements EmailVerificationControllerDocs {
	private final EmailVerificationSendService sendService;
	private final EmailVerificationDeliveryService deliveryService;
	private final EmailVerificationConfirmService confirmService;
	private final EmailVerificationResendService resendService;

	@PostMapping
	public ResponseEntity<EmailVerificationDeliveryResponse> send(
		@Valid @RequestBody EmailVerificationRequest request
	) {
		return accepted(sendService.send(request));
	}

	@GetMapping("/deliveries/{requestId}")
	public ResponseEntity<EmailVerificationDeliveryResponse> status(@PathVariable UUID requestId) {
		return deliveryService.status(requestId)
				.map(body -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body))
				.orElseGet(() -> ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build());
	}

	@PostMapping("/code")
	public ResponseEntity<EmailVerificationConfirmResponse> confirm(
		@Valid @RequestBody EmailVerificationConfirmRequest request
	) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(confirmService.confirm(request));
	}

	@PostMapping("/resend")
	public ResponseEntity<EmailVerificationDeliveryResponse> resend(
		@Valid @RequestBody EmailVerificationRequest request
	) {
		return accepted(resendService.resend(request));
	}

	private ResponseEntity<EmailVerificationDeliveryResponse> accepted(EmailVerificationDeliveryResponse response) {
		return ResponseEntity.accepted()
				.location(URI.create("/api/v1/auth/email-verification/deliveries/" + response.requestId()))
				.cacheControl(CacheControl.noStore())
				.body(response);
	}
}
