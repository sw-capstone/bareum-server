package com.bareum.server.domain.auth.service;

import com.bareum.server.domain.auth.enums.VerificationPurpose;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EmailVerificationCodeHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec secretKey;

    public EmailVerificationCodeHasher(
            @Value("${auth.email-verification.hash-secret}")
            String encodedSecret
    ) {
        byte[] keyBytes;

        try {
            keyBytes = Base64.getDecoder().decode(encodedSecret);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "이메일 인증 해시 비밀키는 올바른 Base64 형식이어야 합니다."
            );
        }

        if (keyBytes.length < 32) {
            throw new IllegalArgumentException(
                    "이메일 인증 해시 비밀키는 최소 32바이트여야 합니다."
            );
        }

        this.secretKey = new SecretKeySpec(keyBytes, ALGORITHM);
    }

    public String hash(
            String email,
            VerificationPurpose purpose,
            String code
    ) {
        String payload = String.join(
                "\n",
                email.toLowerCase(Locale.ROOT),
                purpose.name(),
                code
        );

        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(secretKey);

            byte[] result = mac.doFinal(
                    payload.getBytes(StandardCharsets.UTF_8)
            );

            return HexFormat.of().formatHex(result);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "이메일 인증번호 해시를 생성하지 못했습니다.",
                    exception
            );
        }
    }

    public boolean matches(
            String email,
            VerificationPurpose purpose,
            String code,
            String storedHash
    ) {
        if (storedHash == null) {
            return false;
        }

        byte[] actual = hash(email, purpose, code)
                .getBytes(StandardCharsets.UTF_8);
        byte[] expected = storedHash.getBytes(StandardCharsets.UTF_8);

        return MessageDigest.isEqual(actual, expected);
    }
}
