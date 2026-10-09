package com.bareum.server.domain.auth.service;

import com.bareum.server.domain.auth.enums.VerificationPurpose;

public interface EmailVerificationMailSender {

    void sendVerificationCode(
            String email,
            String code,
            VerificationPurpose purpose
    );
}
