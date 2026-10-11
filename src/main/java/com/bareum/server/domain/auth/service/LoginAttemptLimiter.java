package com.bareum.server.domain.auth.service;

import com.bareum.server.domain.auth.config.LoginProperties;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;

@Service
public class LoginAttemptLimiter {
    private final JdbcTemplate jdbc;
    private final LoginProperties properties;
    private final LoginTokenGenerator tokens;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public LoginAttemptLimiter(
            JdbcTemplate jdbc,
            LoginProperties properties,
            LoginTokenGenerator tokens,
            @Qualifier("loginClock") Clock clock,
            PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.tokens = tokens;
        this.clock = clock;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelay = 3600000,
            initialDelay = 3600000)
    public void purgeExpiredWindows() {
        jdbc.update(
                "DELETE FROM login_request_window WHERE window_started_at < ?",
                Timestamp.from(clock.instant().minusSeconds(86400)));
    }

    public void reserve(String email) {
        var now = clock.instant();
        Boolean reserved =
                transaction.execute(
                        status -> {
                            String normalized =
                                    jdbc.queryForObject("SELECT upper(?)", String.class, email);
                            String key = tokens.hash("login-limit", normalized);
                            var count =
                                    jdbc.query(
                                            """
                                            INSERT INTO login_request_window(email_key,window_started_at,request_count) VALUES(?,?,1)
                                            ON CONFLICT(email_key) DO UPDATE SET
                                                window_started_at=CASE WHEN login_request_window.window_started_at <= ? THEN ?
                                                    ELSE login_request_window.window_started_at END,
                                                request_count=CASE WHEN login_request_window.window_started_at <= ? THEN 1
                                                    ELSE login_request_window.request_count+1 END
                                            WHERE login_request_window.window_started_at <= ? OR login_request_window.request_count < ?
                                            RETURNING request_count
                                            """,
                                            (rs, row) -> rs.getInt(1),
                                            key,
                                            Timestamp.from(now),
                                            Timestamp.from(
                                                    now.minusSeconds(
                                                            properties.requestWindowSeconds())),
                                            Timestamp.from(now),
                                            Timestamp.from(
                                                    now.minusSeconds(
                                                            properties.requestWindowSeconds())),
                                            Timestamp.from(
                                                    now.minusSeconds(
                                                            properties.requestWindowSeconds())),
                                            properties.maxRequestsPerWindow());
                            return !count.isEmpty();
                        });
        if (!Boolean.TRUE.equals(reserved))
            throw new AuthException(AuthErrorCode.LOGIN_REQUEST_LIMIT_EXCEEDED);
    }
}
