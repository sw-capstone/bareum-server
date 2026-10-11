package com.bareum.server.domain.auth.service;

import com.bareum.server.domain.auth.config.LoginProperties;
import com.bareum.server.domain.auth.config.TokenRenewProperties;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.exception.RefreshTokenReplayException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class TokenRenewService {
    private final JdbcTemplate jdbc;
    private final LoginTokenGenerator tokens;
    private final LoginProperties login;
    private final TokenRenewProperties policy;
    private final Clock clock;

    public TokenRenewService(
            JdbcTemplate jdbc,
            LoginTokenGenerator tokens,
            LoginProperties login,
            TokenRenewProperties policy,
            @Qualifier("loginClock") Clock clock) {
        this.jdbc = jdbc;
        this.tokens = tokens;
        this.login = login;
        this.policy = policy;
        this.clock = clock;
    }

    @Transactional(noRollbackFor = RefreshTokenReplayException.class)
    public LoginSessionService.IssuedLogin renew(String refresh) {
        if (!tokens.validFormat(refresh)) throw invalid();
        String hash = tokens.hash("refresh", refresh);
        // Resolve identity first, then use the same member -> session lock order as login.
        var identities =
                jdbc.query(
                        """
                        SELECT id,member_id FROM refresh_token WHERE refresh_token_hash=?
                        UNION
                        SELECT r.id,r.member_id FROM used_refresh_token u
                            JOIN refresh_token r ON r.id=u.session_id
                            WHERE u.token_hash=? AND u.expires_at>?
                        """,
                        (rs, n) -> new Identity(rs.getLong(1), rs.getLong(2)),
                        hash,
                        hash,
                        Timestamp.from(clock.instant()));
        if (identities.size() != 1) throw invalid();
        Identity identity = identities.getFirst();
        var members =
                jdbc.query(
                        "SELECT status,withdrawn_at FROM member WHERE id=? FOR UPDATE",
                        (rs, n) -> rs.getString(1).equals("ACTIVE") && rs.getTimestamp(2) == null,
                        identity.memberId());
        if (members.isEmpty()) throw invalid();
        var sessions =
                jdbc.query(
                        """
                        SELECT jti,refresh_token_hash,expires_at,revoked_at FROM refresh_token
                            WHERE id=? AND member_id=? FOR UPDATE
                        """,
                        (rs, n) ->
                                new Session(
                                        rs.getObject(1, UUID.class),
                                        rs.getString(2),
                                        rs.getTimestamp(3).toInstant(),
                                        rs.getTimestamp(4) != null),
                        identity.sessionId(),
                        identity.memberId());
        if (sessions.isEmpty()) throw invalid();
        Session session = sessions.getFirst();
        Instant now = clock.instant();
        if (session.revoked() || !session.expiresAt().isAfter(now)) throw invalid();
        if (!hash.equals(session.refreshHash())) {
            Integer used =
                    jdbc.queryForObject(
                            """
                            SELECT count(*) FROM used_refresh_token
                            WHERE token_hash=? AND session_id=? AND family_id=? AND expires_at>?
                            """,
                            Integer.class,
                            hash,
                            identity.sessionId(),
                            session.family(),
                            Timestamp.from(now));
            if (used != null && used > 0) {
                jdbc.update(
                        "UPDATE refresh_token SET revoked_at=?,updated_at=? WHERE id=?",
                        Timestamp.from(now),
                        Timestamp.from(now),
                        identity.sessionId());
                throw new RefreshTokenReplayException();
            }
            throw invalid();
        }
        if (!members.getFirst()) throw new AuthException(AuthErrorCode.LOGIN_ACCOUNT_RESTRICTED);
        reserve(identity.sessionId(), session.family(), now);
        String access = tokens.generate();
        String nextRefresh = tokens.generate();
        String nextHash = tokens.hash("refresh", nextRefresh);
        // A newly issued value must never match an already consumed refresh token.
        Integer reused =
                jdbc.queryForObject(
                        "SELECT count(*) FROM used_refresh_token WHERE token_hash=?",
                        Integer.class,
                        nextHash);
        if (hash.equals(nextHash) || (reused != null && reused > 0))
            throw new AuthException(AuthErrorCode.LOGIN_SERVICE_UNAVAILABLE);
        Instant accessExpiry = now.plusSeconds(login.accessTtlSeconds());
        if (accessExpiry.isAfter(session.expiresAt())) accessExpiry = session.expiresAt();
        jdbc.update(
                """
                INSERT INTO used_refresh_token(token_hash,session_id,family_id,consumed_at,expires_at)
                    VALUES(?,?,?,?,?)
                """,
                hash,
                identity.sessionId(),
                session.family(),
                Timestamp.from(now),
                Timestamp.from(session.expiresAt()));
        jdbc.update(
                """
                UPDATE refresh_token SET access_token_hash=?,refresh_token_hash=?,access_expires_at=?,
                    updated_at=? WHERE id=?
                """,
                tokens.hash("access", access),
                nextHash,
                Timestamp.from(accessExpiry),
                Timestamp.from(now),
                identity.sessionId());
        return new LoginSessionService.IssuedLogin(
                access, nextRefresh, accessExpiry, session.expiresAt());
    }

    private void reserve(long sessionId, UUID family, Instant now) {
        var counts =
                jdbc.query(
                        """
                        INSERT INTO token_renew_window(session_id,family_id,window_started_at,request_count)
                        VALUES(?,?,?,1)
                        ON CONFLICT(session_id) DO UPDATE SET family_id=EXCLUDED.family_id,
                            window_started_at=CASE WHEN token_renew_window.family_id<>EXCLUDED.family_id
                                OR token_renew_window.window_started_at<=? THEN EXCLUDED.window_started_at
                                ELSE token_renew_window.window_started_at END,
                            request_count=CASE WHEN token_renew_window.family_id<>EXCLUDED.family_id
                                OR token_renew_window.window_started_at<=? THEN 1
                                ELSE token_renew_window.request_count+1 END
                        RETURNING request_count
                        """,
                        (rs, n) -> rs.getInt(1),
                        sessionId,
                        family,
                        Timestamp.from(now),
                        Timestamp.from(now.minusSeconds(policy.requestWindowSeconds())),
                        Timestamp.from(now.minusSeconds(policy.requestWindowSeconds())));
        if (counts.getFirst() > policy.maxRequestsPerWindow())
            throw new AuthException(AuthErrorCode.TOKEN_RENEW_LIMIT_EXCEEDED);
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 3600000)
    public void purgeExpiredTokens() {
        Instant now = clock.instant();
        jdbc.update("DELETE FROM used_refresh_token WHERE expires_at<=?", Timestamp.from(now));
        jdbc.update(
                "DELETE FROM token_renew_window WHERE window_started_at<?",
                Timestamp.from(now.minusSeconds(86400)));
    }

    private AuthException invalid() {
        return new AuthException(AuthErrorCode.INVALID_REFRESH_TOKEN);
    }

    private record Identity(long sessionId, long memberId) {}

    private record Session(UUID family, String refreshHash, Instant expiresAt, boolean revoked) {
        @Override
        public String toString() {
            return "Session[redacted]";
        }
    }
}
