package com.bareum.server.global.security;

import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.service.LoginSessionService;
import com.bareum.server.global.security.handler.SecurityProblemWriter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

public class BearerTokenFilter extends OncePerRequestFilter {
    private final LoginSessionService sessions;
    private final SecurityProblemWriter problems;

    public BearerTokenFilter(LoginSessionService sessions, SecurityProblemWriter problems) {
        this.sessions = sessions;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.equals("/api/v1/auth/token/renew")
                || path.equals("/api/v1/auth/csrf")
                || path.equals("/api/v1/auth/logout")
                || path.equals("/api/v1/auth/login/google")
                || path.equals("/api/v1/auth/signup/google")
                || path.equals("/api/v1/auth/password/reset");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var headers = Collections.list(request.getHeaders("Authorization"));
        if (headers.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        String value = headers.getFirst();
        if (headers.size() != 1
                || value.length() <= 7 || value.length() > 8199
                || !value.regionMatches(true, 0, "Bearer ", 0, 7)
                || !value.substring(7).matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) {
            problems.write(request, response, AuthErrorCode.INVALID_ACCESS_TOKEN);
            return;
        }
        java.util.Optional<LoginPrincipal> principal;
        try {
            principal = sessions.authenticate(value.substring(7));
        } catch (com.bareum.server.domain.auth.exception.AuthException failure) {
            problems.write(request, response, AuthErrorCode.LOGIN_SERVICE_UNAVAILABLE);
            return;
        } catch (org.springframework.dao.DataAccessException
                | org.springframework.transaction.TransactionException failure) {
            problems.write(request, response, AuthErrorCode.LOGIN_SERVICE_UNAVAILABLE);
            return;
        }
        if (principal.isEmpty()) {
            problems.write(request, response, AuthErrorCode.INVALID_ACCESS_TOKEN);
            return;
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        principal.get(), null, List.of(new SimpleGrantedAuthority("ROLE_MEMBER"))));
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }
}
