package com.bareum.server.global.security.handler;

import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.global.response.ProblemDetailFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

@Component
public class SecurityProblemWriter {
    private final JsonMapper mapper;

    public SecurityProblemWriter(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, AuthErrorCode error)
            throws IOException {
        response.setStatus(error.getHttpStatus().value());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        if (error.getHttpStatus().value() == 401) response.setHeader("WWW-Authenticate", "Bearer");
        mapper.writeValue(
                response.getOutputStream(),
                ProblemDetailFactory.create(error, error.getMessage(), request));
    }
}
