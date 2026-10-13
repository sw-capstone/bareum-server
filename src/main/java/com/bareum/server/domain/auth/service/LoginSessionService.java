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
    private final LoginJwtService jwt;
    private final LoginProperties properties;
    private final Clock clock;

    public LoginSessionService(
            JdbcTemplate jdbc,
            LoginTokenGenerator tokens,
            LoginJwtService jwt,
            LoginProperties properties,
            @Qualifier("loginClock") Clock clock) {
        this.jdbc = jdbc;
        this.tokens = tokens;
        this.jwt = jwt;
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
        return issueForLockedMember(validated.memberId());
    }

    // 호출자는 같은 트랜잭션에서 회원 상태·인증 수단을 확인하고 회원 행을 잠가야 합니다.
    IssuedLogin issueForLockedMember(long memberId) {
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant accessExpiry = now.plusSeconds(properties.accessTtlSeconds());
        Instant sessionExpiry = now.plusSeconds(properties.sessionTtlSeconds());
        UUID family = UUID.randomUUID();
        String access = jwt.issue("access", memberId, family, now, accessExpiry);
        String refresh = jwt.issue("refresh", memberId, family, now, sessionExpiry);
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
                memberId,
                family,
                Timestamp.from(sessionExpiry),
                Timestamp.from(now),
                Timestamp.from(now),
                tokens.hash("access", access),
                tokens.hash("refresh", refresh),
                Timestamp.from(accessExpiry));
        storeAccess(access, memberId, family, now, accessExpiry);
        return new IssuedLogin(access, refresh, accessExpiry, sessionExpiry);
    }

    @Transactional(readOnly = true)
    public Optional<LoginPrincipal> authenticate(String access) {
        var verified = jwt.verify(access, "access");
        if (verified.isEmpty()) return Optional.empty();
        var claims = verified.get();
        Instant now = clock.instant();
        return jdbc
                .query(
                        """
                        SELECT m.id FROM login_access_token a JOIN member m ON m.id=a.member_id
                        WHERE a.token_hash=? AND a.member_id=? AND a.family_id=? AND a.expires_at>?
                            AND a.revoked_at IS NULL AND m.status='ACTIVE' AND m.withdrawn_at IS NULL
                            AND NOT EXISTS(SELECT 1 FROM refresh_token r WHERE r.member_id=a.member_id
                                AND r.jti=a.family_id AND r.revoked_at IS NOT NULL)
                        """,
                        (rs, row) -> new LoginPrincipal(rs.getLong(1)),
                        tokens.hash("access", access),
                        claims.memberId(), claims.family(),
                        Timestamp.from(now))
                .stream()
                .findFirst();
    }

    void storeAccess(String access, long memberId, UUID family, Instant now, Instant expiry) {
        jdbc.update("""
                INSERT INTO login_access_token(token_hash,member_id,family_id,expires_at,created_at)
                VALUES(?,?,?,?,?)
                """, tokens.hash("access", access), memberId, family, Timestamp.from(expiry), Timestamp.from(now));
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 3600000, initialDelay = 3600000)
    public void purgeExpiredAccessTokens() {
        jdbc.update("DELETE FROM login_access_token WHERE expires_at<=?", Timestamp.from(clock.instant()));
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
