package com.bareum.server.global.security.handler;

import java.io.IOException;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.bareum.server.domain.auth.exception.AuthErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class CustomAuthenticationEntryPoint implements AuthenticationEntryPoint {
	private final SecurityProblemWriter problems;

	public CustomAuthenticationEntryPoint(SecurityProblemWriter problems) {
		this.problems = problems;
	}

	@Override
	public void commence(
		HttpServletRequest request,
		HttpServletResponse response,
		AuthenticationException e
	)
			throws IOException {
		problems.write(request, response, AuthErrorCode.INVALID_ACCESS_TOKEN);
	}
}
