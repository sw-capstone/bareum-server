package com.bareum.server.global.security.handler;

import java.io.IOException;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.bareum.server.domain.auth.exception.AuthErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class CustomAccessDeniedHandler implements AccessDeniedHandler {
	private final SecurityProblemWriter problems;

	public CustomAccessDeniedHandler(SecurityProblemWriter problems) {
		this.problems = problems;
	}

	@Override
	public void handle(
		HttpServletRequest request,
		HttpServletResponse response,
		AccessDeniedException e
	)
			throws IOException {
		problems.write(request, response, AuthErrorCode.ACCESS_DENIED);
	}
}
