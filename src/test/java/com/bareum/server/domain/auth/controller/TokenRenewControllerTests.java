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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;

import com.bareum.server.domain.auth.config.LoginProperties;
import com.bareum.server.domain.auth.config.TokenRenewProperties;
import com.bareum.server.domain.auth.dto.response.CsrfTokenResponse;
import com.bareum.server.domain.auth.service.*;
import com.bareum.server.global.exception.handler.GlobalExceptionHandler;

import jakarta.servlet.http.Cookie;

class TokenRenewControllerTests {
	@Test
	void databaseOrTransactionFailureDoesNotLeakInfrastructureOrIssueCookie() throws Exception {
		var service = mock(TokenRenewService.class);
		var properties = new LoginProperties(900, 28800, true, "None", List.of(), 10, 60);
		var controller =
				new AuthController(mock(EmailLoginService.class),
						mock(CurrentUserService.class),
						new LoginCookieFactory(properties, Clock.systemUTC()), service, mock(LogoutService.class),
						mock(com.bareum.server.domain.auth.service.GoogleLoginService.class));
		var mvc = MockMvcBuilders.standaloneSetup(controller)
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
		String token = java.util.UUID.randomUUID().toString();
		for (RuntimeException cause : List.of(new DataAccessResourceFailureException("private infrastructure"),
				new CannotCreateTransactionException("private connection"))) {
			doThrow(cause).when(service).renew(token);
			mvc.perform(post("/api/v1/auth/token/renew").cookie(new Cookie("bareum_refresh", token)))
					.andExpect(status().isServiceUnavailable())
					.andExpect(jsonPath("$.code").value("AUTH-0019"))
					.andExpect(header().doesNotExist("Set-Cookie"))
					.andExpect(result -> assertFalse(result.getResponse().getContentAsString().contains("private")));
		}
	}

	@Test
	void csrfResponseDoesNotPrintTheToken() {
		String token = java.util.UUID.randomUUID().toString();
		assertFalse(new CsrfTokenResponse("X-CSRF-TOKEN", token).toString().contains(token));
	}

	@Test
	void renewalLimitConfigurationRejectsUnboundedOrInvalidValues() {
		assertThrows(IllegalArgumentException.class, () -> new TokenRenewProperties(0, 60));
		assertThrows(IllegalArgumentException.class, () -> new TokenRenewProperties(101, 60));
		assertThrows(IllegalArgumentException.class, () -> new TokenRenewProperties(30, 0));
		assertThrows(IllegalArgumentException.class, () -> new TokenRenewProperties(30, 3601));
		assertDoesNotThrow(() -> new TokenRenewProperties(30, 60));
	}
}
