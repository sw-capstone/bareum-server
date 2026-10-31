package com.bareum.server.domain.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

@Component
public class LoginTokenGenerator {
	public String hash(String purpose, String value) {
		try {
			return HexFormat.of()
					.formatHex(
							MessageDigest.getInstance("SHA-256")
									.digest(
											(purpose + ":" + value)
													.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}
}
