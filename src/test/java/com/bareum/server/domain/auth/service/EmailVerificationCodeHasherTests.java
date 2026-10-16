package com.bareum.server.domain.auth.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bareum.server.domain.auth.enums.VerificationPurpose;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class EmailVerificationCodeHasherTests {

    // 테스트 전용 키이며 실제 메일 인증에는 사용하지 않습니다.
    private static final String TEST_SECRET =
            Base64.getEncoder().encodeToString(new byte[32]);

    private final EmailVerificationCodeHasher hasher =
            new EmailVerificationCodeHasher(TEST_SECRET);

    @Test
    void sameEmailPurposeAndCodeMatch() {
        String storedHash = hasher.hash(
                "user@example.com",
                VerificationPurpose.SIGNUP,
                "123456"
        );

        assertTrue(hasher.matches(
                "user@example.com",
                VerificationPurpose.SIGNUP,
                "123456",
                storedHash
        ));
    }

    @Test
    void differentCodeDoesNotMatch() {
        String storedHash = hasher.hash(
                "user@example.com",
                VerificationPurpose.SIGNUP,
                "123456"
        );

        assertFalse(hasher.matches(
                "user@example.com",
                VerificationPurpose.SIGNUP,
                "654321",
                storedHash
        ));
    }

    @Test
    void differentEmailDoesNotMatch() {
        String storedHash = hasher.hash(
                "user@example.com",
                VerificationPurpose.SIGNUP,
                "123456"
        );

        assertFalse(hasher.matches(
                "other@example.com",
                VerificationPurpose.SIGNUP,
                "123456",
                storedHash
        ));
    }

    @Test
    void differentPurposeDoesNotMatch() {
        String storedHash = hasher.hash(
                "user@example.com",
                VerificationPurpose.SIGNUP,
                "123456"
        );

        assertFalse(hasher.matches(
                "user@example.com",
                VerificationPurpose.PASSWORD_RESET,
                "123456",
                storedHash
        ));
    }
}
