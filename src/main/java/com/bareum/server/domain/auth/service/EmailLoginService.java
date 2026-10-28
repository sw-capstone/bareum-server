package com.bareum.server.domain.auth.service;

import org.springframework.stereotype.Service;

import com.bareum.server.domain.auth.dto.request.EmailLoginRequest;

@Service
public class EmailLoginService {
	private final EmailLoginCredentialService credentials;
	private final LoginSessionService sessions;
	private final LoginAttemptLimiter limiter;

	public EmailLoginService(
		EmailLoginCredentialService credentials,
		LoginSessionService sessions,
		LoginAttemptLimiter limiter
	) {
		this.credentials = credentials;
		this.sessions = sessions;
		this.limiter = limiter;
	}

	public LoginSessionService.IssuedLogin login(EmailLoginRequest request) {
		limiter.reserve(request.email());
		return sessions.issue(credentials.validateMember(request.email(), request.password()));
	}
}
