package com.bareum.server.domain.auth.controller;

import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.service.LoginCookieFactory;
import com.bareum.server.domain.auth.service.LogoutService;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

@RestController
public class LogoutController implements LogoutControllerDocs {
    private final LogoutService service;
    private final LoginCookieFactory cookies;

    public LogoutController(LogoutService service, LoginCookieFactory cookies) {
        this.service = service;
        this.cookies = cookies;
    }

    @PostMapping("/api/v1/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        Cookie[] supplied = request.getCookies();
        var values = supplied == null ? List.<String>of() : Arrays.stream(supplied)
                .filter(c -> LoginCookieFactory.REFRESH_COOKIE.equals(c.getName()))
                .map(Cookie::getValue).toList();
        if (values.size() > 1) throw new AuthException(AuthErrorCode.INVALID_REFRESH_TOKEN);
        try {
            service.logout(values.isEmpty() ? null : values.getFirst());
            return ResponseEntity.noContent()
                    .cacheControl(CacheControl.noStore())
                    .header("Pragma", "no-cache")
                    .header(HttpHeaders.SET_COOKIE, cookies.expireRefreshCookie(), cookies.expireCsrfCookie())
                    .build();
        } catch (DataAccessException | TransactionException failure) {
            throw new AuthException(AuthErrorCode.LOGIN_SERVICE_UNAVAILABLE);
        }
    }
}
