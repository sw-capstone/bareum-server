package com.bareum.server.domain.auth.service;

import org.springframework.stereotype.Service;

import com.bareum.server.domain.auth.dto.request.EmailLoginRequest;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class EmailLoginService {
	private final EmailLoginCredentialService credentials;
	private final LoginSessionService sessions;
	private final LoginAttemptLimiter limiter;

	public LoginSessionService.IssuedLogin login(EmailLoginRequest request) {
		limiter.reserve(request.email());
		return sessions.issue(credentials.validateMember(request.email(), request.password()));
	}
}
