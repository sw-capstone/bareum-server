package com.bareum.server.domain.auth.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bareum.server.domain.auth.config.LoginProperties;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.service.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.CannotCreateTransactionException;
import java.time.Clock;
import java.util.List;

class LogoutControllerTests {
    @Test
    void infrastructureErrorsAreMappedWithoutClaimingSuccessfulLogout() {
        var service = mock(LogoutService.class);
        var properties = new LoginProperties(900, 28800, true, "None", List.of(), 10, 60);
        var controller = new LogoutController(service, new LoginCookieFactory(properties, Clock.systemUTC()));
        var request = new MockHttpServletRequest();
        String token = new LoginTokenGenerator().generate();
        request.setCookies(new Cookie("bareum_refresh", token));
        for (RuntimeException cause : List.of(new DataAccessResourceFailureException("private DB"),
                new CannotCreateTransactionException("private connection"))) {
            doThrow(cause).when(service).logout(token);
            var failure = assertThrows(AuthException.class, () -> controller.logout(request));
            assertEquals("AUTH-0019", failure.getBaseErrorCode().getCode());
            assertFalse(failure.toString().contains("private"));
        }
    }

    @Test
    void duplicateRefreshCookiesNeverReachTheRevocationService() {
        var service = mock(LogoutService.class);
        var properties = new LoginProperties(900, 28800, true, "None", List.of(), 10, 60);
        var controller = new LogoutController(service, new LoginCookieFactory(properties, Clock.systemUTC()));
        var request = new MockHttpServletRequest();
        request.setCookies(new Cookie("bareum_refresh", "one"), new Cookie("bareum_refresh", "two"));
        assertEquals("AUTH-0021", assertThrows(AuthException.class,
                () -> controller.logout(request)).getBaseErrorCode().getCode());
        verifyNoInteractions(service);
    }
}
