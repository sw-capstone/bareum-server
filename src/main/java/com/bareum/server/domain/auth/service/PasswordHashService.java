package com.bareum.server.domain.auth.service;

import java.util.Map;
import java.util.Objects;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class PasswordHashService {

    private static final String ENCODING_ID = "pbkdf2-sha256-v1";
    private final PasswordEncoder encoder = new DelegatingPasswordEncoder(
            ENCODING_ID,
            Map.of(ENCODING_ID, new Pbkdf2PasswordEncoder("", 16, 600_000,
                    Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256))
    );

    public String hash(String password) {
        return Objects.requireNonNull(encoder.encode(Objects.requireNonNull(password, "password")));
    }

    public boolean matches(String password, String storedHash) {
        if (password == null || password.isEmpty() || storedHash == null || storedHash.isBlank()) {
            return false;
        }
        try {
            return encoder.matches(password, storedHash);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
