package com.bareum.server.domain.auth.controller;

import com.bareum.server.domain.auth.dto.response.EmailLoginResponse;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.service.LoginCookieFactory;
import com.bareum.server.domain.auth.service.TokenRenewService;

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

@RestController
public class TokenRenewController implements TokenRenewControllerDocs {
    private final TokenRenewService service;
    private final LoginCookieFactory cookies;

    public TokenRenewController(TokenRenewService service, LoginCookieFactory cookies) {
        this.service = service;
        this.cookies = cookies;
    }

    @PostMapping("/api/v1/auth/token/renew")
    public ResponseEntity<EmailLoginResponse> renew(HttpServletRequest request) {
        Cookie[] supplied = request.getCookies();
        var values =
                supplied == null
                        ? java.util.List.<String>of()
                        : Arrays.stream(supplied)
                                .filter(
                                        cookie ->
                                                LoginCookieFactory.REFRESH_COOKIE.equals(
                                                        cookie.getName()))
                                .map(Cookie::getValue)
                                .toList();
        if (values.size() != 1) throw new AuthException(AuthErrorCode.INVALID_REFRESH_TOKEN);
        try {
            var issued = service.renew(values.getFirst());
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .header("Pragma", "no-cache")
                    .header(HttpHeaders.SET_COOKIE, cookies.refreshCookie(issued))
                    .body(cookies.response(issued));
        } catch (DataAccessException | TransactionException failure) {
            throw new AuthException(AuthErrorCode.LOGIN_SERVICE_UNAVAILABLE);
        }
    }
}
