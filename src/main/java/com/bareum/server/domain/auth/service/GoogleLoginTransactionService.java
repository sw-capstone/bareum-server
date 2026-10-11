package com.bareum.server.domain.auth.service;

import com.bareum.server.domain.auth.config.GoogleLoginProperties;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

@Service
public class GoogleLoginTransactionService {
    private final JdbcTemplate jdbc;
    private final LoginSessionService sessions;
    private final LoginTokenGenerator tokens;
    private final GoogleLoginProperties policy;
    private final EmailVerificationRequestLock emailLock;
    private final Clock clock;

    public GoogleLoginTransactionService(
            JdbcTemplate jdbc,
            LoginSessionService sessions,
            LoginTokenGenerator tokens,
            GoogleLoginProperties policy,
            EmailVerificationRequestLock emailLock,
            @Qualifier("loginClock") Clock clock) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.tokens = tokens;
        this.policy = policy;
        this.emailLock = emailLock;
        this.clock = clock;
    }

    @Transactional
    public Result resolve(GoogleIdentity identity) {
        jdbc.execute(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                (PreparedStatementCallback<Void>)
                        statement -> {
                            statement.setString(1, "bareum:google:" + identity.subject());
                            statement.execute();
                            return null;
                        });
        var linked =
                jdbc.query(
                        "SELECT member_id FROM member_auth WHERE provider='GOOGLE' AND"
                            + " provider_user_id=?",
                        (rs, n) -> rs.getLong(1),
                        identity.subject());
        if (!linked.isEmpty()) {
            long memberId = linked.getFirst();
            var active =
                    jdbc.query(
                            "SELECT status,withdrawn_at,signup_method FROM member WHERE id=? FOR"
                                + " UPDATE",
                            (rs, n) ->
                                    rs.getString(1).equals("ACTIVE")
                                            && rs.getTimestamp(2) == null
                                            && rs.getString(3).equals("GOOGLE"),
                            memberId);
            var auth =
                    jdbc.query(
                            """
                            SELECT unlinked_at FROM member_auth WHERE member_id=? AND provider='GOOGLE'
                                AND provider_user_id=? FOR UPDATE
                            """,
                            (rs, n) -> rs.getTimestamp(1) == null,
                            memberId,
                            identity.subject());
            if (active.isEmpty() || !active.getFirst() || auth.isEmpty() || !auth.getFirst())
                throw new AuthException(AuthErrorCode.LOGIN_ACCOUNT_RESTRICTED);
            return new Result(sessions.issueForLockedMember(memberId), null, null);
        }
        emailLock.lockForEmail(identity.email());
        var methods =
                jdbc.query(
                        "SELECT signup_method FROM member WHERE upper(email)=upper(?) AND"
                            + " NOT (status='DELETED' AND withdrawn_at IS NOT NULL)",
                        (rs, n) -> rs.getString(1),
                        identity.email());
        if (!methods.isEmpty()) {
            if (methods.contains("LOCAL"))
                throw new AuthException(AuthErrorCode.EMAIL_LOGIN_REQUIRED);
            throw new AuthException(AuthErrorCode.INVALID_GOOGLE_AUTHENTICATION);
        }
        // Gmail 또는 검증된 Workspace 도메인만 신규 가입 이메일의 소유권 증명으로 사용합니다.
        if (!identity.email().toLowerCase(Locale.ROOT).endsWith("@gmail.com")
                && identity.hostedDomain() == null)
            throw new AuthException(AuthErrorCode.GOOGLE_EMAIL_VERIFICATION_REQUIRED);
        if (identity.name() == null || identity.name().isBlank())
            throw new AuthException(AuthErrorCode.INVALID_GOOGLE_AUTHENTICATION);
        Instant now = clock.instant(), expiry = now.plusSeconds(policy.signupTtlSeconds());
        String proof = "gs_" + tokens.generate();
        jdbc.update("DELETE FROM google_signup_proof WHERE google_subject=?", identity.subject());
        jdbc.update(
                """
                INSERT INTO google_signup_proof(token_hash,google_subject,email,name,expires_at,created_at)
                VALUES(?,?,?,?,?,?)
                """,
                tokens.hash("google-signup", proof),
                identity.subject(),
                identity.email(),
                identity.name(),
                Timestamp.from(expiry),
                Timestamp.from(now));
        return new Result(null, proof, expiry);
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 3600000)
    public void purgeExpiredProofs() {
        jdbc.update(
                "DELETE FROM google_signup_proof WHERE expires_at<=?",
                Timestamp.from(clock.instant()));
    }

    public record Result(
            LoginSessionService.IssuedLogin login, String signupToken, Instant signupExpiresAt) {
        @Override
        public String toString() {
            return "GoogleLoginResult[redacted]";
        }
    }
}
