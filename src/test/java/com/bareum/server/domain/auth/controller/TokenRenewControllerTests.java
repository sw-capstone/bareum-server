package com.bareum.server.domain.auth.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bareum.server.domain.auth.config.LoginProperties;
import com.bareum.server.domain.auth.config.TokenRenewProperties;
import com.bareum.server.domain.auth.dto.response.CsrfTokenResponse;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.service.*;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.CannotCreateTransactionException;

import java.time.Clock;
import java.util.List;

class TokenRenewControllerTests {
    @Test
    void databaseOrTransactionFailureDoesNotLeakInfrastructureOrIssueCookie() {
        var service = mock(TokenRenewService.class);
        var properties = new LoginProperties(900, 28800, true, "None", List.of(), 10, 60);
        var controller =
                new TokenRenewController(
                        service, new LoginCookieFactory(properties, Clock.systemUTC()));
        String token = new LoginTokenGenerator().generate();
        var request = new MockHttpServletRequest();
        request.setCookies(new Cookie("bareum_refresh", token));
        when(service.renew(token))
                .thenThrow(new DataAccessResourceFailureException("private infrastructure"));
        var failure = assertThrows(AuthException.class, () -> controller.renew(request));
        assertEquals("AUTH-0019", failure.getBaseErrorCode().getCode());
        assertFalse(failure.toString().contains("private infrastructure"));
        doThrow(new CannotCreateTransactionException("private connection"))
                .when(service)
                .renew(token);
        assertFalse(
                assertThrows(AuthException.class, () -> controller.renew(request))
                        .toString()
                        .contains("private connection"));
    }

    @Test
    void csrfResponseDoesNotPrintTheToken() {
        String token = new LoginTokenGenerator().generate();
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
