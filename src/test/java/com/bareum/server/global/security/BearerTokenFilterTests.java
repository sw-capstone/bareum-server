package com.bareum.server.global.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.service.LoginSessionService;
import com.bareum.server.global.security.handler.SecurityProblemWriter;

import jakarta.servlet.FilterChain;

class BearerTokenFilterTests {
	private final LoginSessionService sessions = mock(LoginSessionService.class);
	private final SecurityProblemWriter problems = mock(SecurityProblemWriter.class);
	private final FilterChain chain = mock(FilterChain.class);
	private final BearerTokenFilter filter = new BearerTokenFilter(sessions, problems);

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void storageFailureReturns503AndDoesNotAuthenticateOrLeakException() throws Exception {
		String token = "header.payload.signature";
		var request = new MockHttpServletRequest();
		var response = new MockHttpServletResponse();
		request.addHeader("Authorization", "Bearer " + token);
		when(sessions.authenticate(token))
				.thenThrow(new DataAccessResourceFailureException("private database details"));
		filter.doFilter(request, response, chain);
		verify(problems).write(request, response, AuthErrorCode.LOGIN_SERVICE_UNAVAILABLE);
		verifyNoInteractions(chain);
		assertNull(SecurityContextHolder.getContext().getAuthentication());
	}

	@Test
	void validBearerCreatesMemberPrincipalWithoutRawCredentials() throws Exception {
		String token = "header.payload.signature";
		var request = new MockHttpServletRequest();
		var response = new MockHttpServletResponse();
		request.addHeader("Authorization", "bEaReR " + token);
		when(sessions.authenticate(token)).thenReturn(Optional.of(new LoginPrincipal(7L)));
		filter.doFilter(request, response, chain);
		var auth = SecurityContextHolder.getContext().getAuthentication();
		assertEquals(new LoginPrincipal(7L), auth.getPrincipal());
		assertNull(auth.getCredentials());
		assertTrue(auth.isAuthenticated());
		verify(chain).doFilter(request, response);
		verifyNoInteractions(problems);
	}
}
