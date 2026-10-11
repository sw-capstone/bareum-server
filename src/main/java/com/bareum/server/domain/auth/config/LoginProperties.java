package com.bareum.server.domain.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.util.List;

@ConfigurationProperties("auth.login")
public record LoginProperties(
        @DefaultValue("900") long accessTtlSeconds,
        @DefaultValue("28800") long sessionTtlSeconds,
        @DefaultValue("true") boolean cookieSecure,
        @DefaultValue("None") String cookieSameSite,
        @DefaultValue List<String> allowedOrigins,
        @DefaultValue("10") int maxRequestsPerWindow,
        @DefaultValue("60") long requestWindowSeconds) {
    public LoginProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        if (accessTtlSeconds < 1
                || accessTtlSeconds > 900
                || sessionTtlSeconds < accessTtlSeconds
                || sessionTtlSeconds > 28800
                || maxRequestsPerWindow < 1
                || maxRequestsPerWindow > 100
                || requestWindowSeconds < 1
                || requestWindowSeconds > 3600
                || !List.of("None", "Lax", "Strict").contains(cookieSameSite)
                || ("None".equals(cookieSameSite) && !cookieSecure)) {
            throw new IllegalArgumentException("Invalid login policy configuration");
        }
        for (String origin : allowedOrigins) {
            URI uri;
            try {
                uri = URI.create(origin);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Invalid login origin configuration");
            }
            if (uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty())
                    || !List.of("http", "https").contains(uri.getScheme())
                    || origin.contains("*")
                    || origin.equals("null")) {
                throw new IllegalArgumentException("Login origins must be exact HTTP origins");
            }
        }
    }
}
