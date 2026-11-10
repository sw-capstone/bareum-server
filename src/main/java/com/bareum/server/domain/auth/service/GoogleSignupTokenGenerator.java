package com.bareum.server.domain.auth.service;

import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.stereotype.Component;

@Component
public class GoogleSignupTokenGenerator {
	private final SecureRandom random = new SecureRandom();

	public String generate() {
		byte[] value = new byte[32];
		random.nextBytes(value);
		return "gs_" + Base64.getUrlEncoder().withoutPadding().encodeToString(value);
	}
}
