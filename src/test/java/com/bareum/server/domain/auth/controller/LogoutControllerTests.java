package com.bareum.server.domain.auth.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;

import com.bareum.server.domain.auth.config.LoginProperties;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.service.*;
import com.bareum.server.global.exception.handler.GlobalExceptionHandler;

import jakarta.servlet.http.Cookie;

class LogoutControllerTests {
	@Test
	void infrastructureErrorsAreMappedWithoutClaimingSuccessfulLogout() throws Exception {
		var service = mock(LogoutService.class);
		var properties = new LoginProperties(900, 28800, true, "None", List.of(), 10, 60);
		var controller = new AuthController(mock(EmailLoginService.class),
				mock(CurrentUserService.class),
				new LoginCookieFactory(properties, Clock.systemUTC()), mock(TokenRenewService.class), service,
				mock(com.bareum.server.domain.auth.service.GoogleLoginService.class));
		var mvc = MockMvcBuilders.standaloneSetup(controller)
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
		String token = java.util.UUID.randomUUID().toString();
		for (RuntimeException cause : List.of(new DataAccessResourceFailureException("private DB"),
				new CannotCreateTransactionException("private connection"))) {
			doThrow(cause).when(service).logout(token);
			mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie("bareum_refresh", token)))
					.andExpect(status().isServiceUnavailable())
					.andExpect(jsonPath("$.code").value("AUTH-0019"))
					.andExpect(header().doesNotExist("Set-Cookie"))
					.andExpect(result -> assertFalse(result.getResponse().getContentAsString().contains("private")));
		}
	}

	@Test
	void duplicateRefreshCookiesNeverReachTheRevocationService() {
		var service = mock(LogoutService.class);
		var properties = new LoginProperties(900, 28800, true, "None", List.of(), 10, 60);
		var controller = new AuthController(mock(EmailLoginService.class),
				mock(CurrentUserService.class),
				new LoginCookieFactory(properties, Clock.systemUTC()), mock(TokenRenewService.class), service,
				mock(com.bareum.server.domain.auth.service.GoogleLoginService.class));
		var request = new MockHttpServletRequest();
		request.setCookies(new Cookie("bareum_refresh", "one"), new Cookie("bareum_refresh", "two"));
		assertEquals("AUTH-0021", assertThrows(AuthException.class,
				() -> controller.logout(request)).getBaseErrorCode().getCode());
		verifyNoInteractions(service);
	}
}
