package com.bareum.server.domain.auth.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class EmailVerificationRequestLock {

    private static final String LOCK_NAMESPACE =
            "bareum:email-verification:";

    private final JdbcTemplate jdbcTemplate;

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForEmail(String email) {
        String normalizedEmail = jdbcTemplate.queryForObject(
                "SELECT upper(?)",
                String.class,
                email
        );

        long lockKey = createLockKey(
                Objects.requireNonNull(normalizedEmail, "normalizedEmail")
        );

        jdbcTemplate.execute(
                "SELECT pg_advisory_xact_lock(?)",
                (PreparedStatementCallback<Void>) statement -> {
                    statement.setLong(1, lockKey);
                    statement.execute();
                    return null;
                }
        );
    }

    private long createLockKey(String normalizedEmail) {
        String value = LOCK_NAMESPACE + normalizedEmail;

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));

            return ByteBuffer.wrap(digest).getLong();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "이메일 인증 요청 잠금 키를 생성하지 못했습니다.",
                    exception
            );
        }
    }
}
