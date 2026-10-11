package com.bareum.server.domain.auth.config;

import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Set;

@ConfigurationProperties("auth.google")
public record GoogleLoginProperties(
        @DefaultValue List<String> clientIds, @DefaultValue("600") long signupTtlSeconds) {
    public GoogleLoginProperties {
        clientIds = clientIds == null ? List.of() : List.copyOf(clientIds);
        if (signupTtlSeconds < 1 || signupTtlSeconds > 600)
            throw new IllegalArgumentException("Invalid Google signup expiry configuration");
    }

    public Set<String> allowedClientIds() {
        if (clientIds.isEmpty()
                || clientIds.stream().anyMatch(id -> id.isBlank() || !id.equals(id.strip())))
            throw new AuthException(AuthErrorCode.GOOGLE_LOGIN_NOT_CONFIGURED);
        return Set.copyOf(clientIds);
    }
}
