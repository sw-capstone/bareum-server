package com.bareum.server.domain.auth.service;

import com.bareum.server.domain.auth.config.LoginProperties;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.global.security.LoginPrincipal;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class LoginSessionService {
    private final JdbcTemplate jdbc;
    private final LoginTokenGenerator tokens;
    private final LoginProperties properties;
    private final Clock clock;

    public LoginSessionService(
            JdbcTemplate jdbc,
            LoginTokenGenerator tokens,
            LoginProperties properties,
            @Qualifier("loginClock") Clock clock) {
        this.jdbc = jdbc;
        this.tokens = tokens;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public IssuedLogin issue(EmailLoginCredentialService.ValidatedMember validated) {
        var members =
                jdbc.query(
                        """
                        SELECT password_hash FROM member WHERE id=? AND status='ACTIVE' AND withdrawn_at IS NULL
                            AND signup_method='LOCAL' FOR UPDATE
                        """,
                        (rs, row) -> rs.getString(1),
                        validated.memberId());
        if (members.isEmpty()) throw new AuthException(AuthErrorCode.LOGIN_ACCOUNT_RESTRICTED);
        if (!members.getFirst().equals(validated.passwordHash()))
            throw new AuthException(AuthErrorCode.INVALID_LOGIN_CREDENTIALS);
        Instant now = clock.instant();
        Instant accessExpiry = now.plusSeconds(properties.accessTtlSeconds());
        Instant sessionExpiry = now.plusSeconds(properties.sessionTtlSeconds());
        String access = tokens.generate();
        String refresh = tokens.generate();
        jdbc.update(
                """
                INSERT INTO refresh_token(member_id,jti,expires_at,created_at,updated_at,
                    access_token_hash,refresh_token_hash,access_expires_at,revoked_at)
                VALUES(?,?,?,?,?,?,?,?,NULL)
                ON CONFLICT(member_id) DO UPDATE SET jti=EXCLUDED.jti,expires_at=EXCLUDED.expires_at,
                    created_at=EXCLUDED.created_at,updated_at=EXCLUDED.updated_at,
                    access_token_hash=EXCLUDED.access_token_hash,refresh_token_hash=EXCLUDED.refresh_token_hash,
                    access_expires_at=EXCLUDED.access_expires_at,revoked_at=NULL
                """,
                validated.memberId(),
                UUID.randomUUID(),
                Timestamp.from(sessionExpiry),
                Timestamp.from(now),
                Timestamp.from(now),
                tokens.hash("access", access),
                tokens.hash("refresh", refresh),
                Timestamp.from(accessExpiry));
        return new IssuedLogin(access, refresh, accessExpiry, sessionExpiry);
    }

    @Transactional(readOnly = true)
    public Optional<LoginPrincipal> authenticate(String access) {
        if (!tokens.validFormat(access)) return Optional.empty();
        Instant now = clock.instant();
        return jdbc
                .query(
                        """
                        SELECT m.id FROM refresh_token r JOIN member m ON m.id=r.member_id
                        WHERE r.access_token_hash=? AND r.access_expires_at>? AND r.expires_at>?
                            AND r.revoked_at IS NULL AND m.status='ACTIVE' AND m.withdrawn_at IS NULL
                        """,
                        (rs, row) -> new LoginPrincipal(rs.getLong(1)),
                        tokens.hash("access", access),
                        Timestamp.from(now),
                        Timestamp.from(now))
                .stream()
                .findFirst();
    }

    public record IssuedLogin(
            String accessToken,
            String refreshToken,
            Instant accessExpiresAt,
            Instant sessionExpiresAt) {
        @Override
        public String toString() {
            return "IssuedLogin[redacted]";
        }
    }
}
