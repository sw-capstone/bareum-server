package com.bareum.server.domain.auth.service;

import com.bareum.server.domain.auth.enums.VerificationPurpose;

public interface EmailVerificationMailSender {

	default void requireConfigured() {
	}

	void sendVerificationCode(
		String email,
		String code,
		VerificationPurpose purpose
	);
}
