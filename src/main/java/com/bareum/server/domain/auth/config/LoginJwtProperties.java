package com.bareum.server.domain.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("auth.login-jwt")
public record LoginJwtProperties(
	String signingKey
) {
	@Override
	public String toString() { return "LoginJwtProperties[redacted]"; }
}
