package com.bareum.server.domain.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("auth.token-renew")
public record TokenRenewProperties(
        @DefaultValue("30") int maxRequestsPerWindow,
        @DefaultValue("60") long requestWindowSeconds) {
    public TokenRenewProperties {
        if (maxRequestsPerWindow < 1
                || maxRequestsPerWindow > 100
                || requestWindowSeconds < 1
                || requestWindowSeconds > 3600) {
            throw new IllegalArgumentException("Invalid token renewal policy configuration");
        }
    }
}
