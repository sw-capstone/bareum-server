package com.bareum.server.domain.auth.service;

import com.bareum.server.domain.auth.config.EmailOutboxProperties;
import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EmailOutboxCipher {
    private static final int NONCE_BYTES = 12;
    private final EmailOutboxProperties properties;
    private final SecureRandom random = new SecureRandom();

    public static String context(String email, VerificationPurpose purpose, long ttl, String codeHash) {
        return email + ":" + purpose.name() + ":" + ttl + ":" + codeHash;
    }

    public void requireConfigured() {
        properties.requireConfigured();
        key();
    }

    public byte[] encrypt(UUID jobId, long verificationId, String codeHash, String code) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            byte[] ciphertext = cipher(Cipher.ENCRYPT_MODE, nonce, jobId, verificationId, codeHash)
                    .doFinal(code.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(nonce.length + ciphertext.length)
                    .put(nonce).put(ciphertext).array();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Outbox encryption failed");
        }
    }

    public String decrypt(UUID jobId, long verificationId, String codeHash,
                          String keyId, byte[] encrypted) {
        if (!properties.keyId().equals(keyId) || encrypted == null
                || encrypted.length <= NONCE_BYTES + 16) {
            throw new IllegalStateException("Invalid outbox payload");
        }
        try {
            byte[] plaintext = cipher(Cipher.DECRYPT_MODE, Arrays.copyOf(encrypted, NONCE_BYTES),
                    jobId, verificationId, codeHash).doFinal(
                            Arrays.copyOfRange(encrypted, NONCE_BYTES, encrypted.length));
            String code = new String(plaintext, StandardCharsets.UTF_8);
            Arrays.fill(plaintext, (byte) 0);
            if (!code.matches("[0-9]{6}")) {
                throw new IllegalStateException("Invalid outbox payload");
            }
            return code;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Invalid outbox payload");
        }
    }

    private Cipher cipher(int mode, byte[] nonce, UUID jobId, long verificationId,
                          String codeHash) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key(), new GCMParameterSpec(128, nonce));
        cipher.updateAAD((properties.keyId() + ":" + jobId + ":" + verificationId + ":" + codeHash)
                .getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private SecretKeySpec key() {
        try {
            byte[] bytes = Base64.getDecoder().decode(properties.encryptionKey());
            if (bytes.length != 32) {
                throw new IllegalArgumentException();
            }
            SecretKeySpec key = new SecretKeySpec(bytes, "AES");
            Arrays.fill(bytes, (byte) 0);
            return key;
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new AuthException(AuthErrorCode.EMAIL_VERIFICATION_NOT_CONFIGURED);
        }
    }
}
