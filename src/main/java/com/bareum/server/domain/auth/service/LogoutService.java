package com.bareum.server.domain.auth.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

@Service
public class LogoutService {
    private final JdbcTemplate jdbc;
    private final LoginTokenGenerator tokens;
    private final Clock clock;

    public LogoutService(JdbcTemplate jdbc, LoginTokenGenerator tokens,
            @Qualifier("loginClock") Clock clock) {
        this.jdbc = jdbc;
        this.tokens = tokens;
        this.clock = clock;
    }

    @Transactional
    public void logout(String refresh) {
        if (!tokens.validFormat(refresh)) return;
        String hash = tokens.hash("refresh", refresh);
        var identities = jdbc.query("""
                SELECT id,member_id,jti FROM refresh_token WHERE refresh_token_hash=?
                UNION
                SELECT r.id,r.member_id,r.jti FROM used_refresh_token u
                    JOIN refresh_token r ON r.id=u.session_id AND r.jti=u.family_id
                    WHERE u.token_hash=?
                """, (rs, n) -> new Identity(rs.getLong(1), rs.getLong(2),
                        rs.getObject(3, UUID.class)), hash, hash);
        if (identities.size() != 1) return;
        Identity identity = identities.getFirst();
        // Match login and renewal's member -> session lock order.
        var members = jdbc.query("SELECT id FROM member WHERE id=? FOR UPDATE",
                (rs, n) -> rs.getLong(1), identity.memberId());
        if (members.isEmpty()) return;
        var sessions = jdbc.query("""
                SELECT refresh_token_hash FROM refresh_token
                    WHERE id=? AND member_id=? AND jti=? FOR UPDATE
                """, (rs, n) -> rs.getString(1), identity.sessionId(),
                identity.memberId(), identity.family());
        if (sessions.isEmpty()) return;
        if (!hash.equals(sessions.getFirst())) {
            Integer consumed = jdbc.queryForObject("""
                    SELECT count(*) FROM used_refresh_token
                        WHERE token_hash=? AND session_id=? AND family_id=?
                    """, Integer.class, hash, identity.sessionId(), identity.family());
            if (consumed == null || consumed == 0) return;
        }
        var now = Timestamp.from(clock.instant());
        jdbc.update("""
                UPDATE refresh_token SET revoked_at=?,updated_at=?
                    WHERE id=? AND jti=? AND revoked_at IS NULL
                """, now, now, identity.sessionId(), identity.family());
    }

    private record Identity(long sessionId, long memberId, UUID family) {}
}
