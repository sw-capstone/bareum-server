package com.bareum.server.domain.auth.service;

import com.bareum.server.domain.auth.dto.request.PasswordResetRequest;
import com.bareum.server.domain.auth.entity.EmailVerification;
import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.repository.EmailVerificationRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordResetService {
    private final EmailSignupPasswordPolicy passwordPolicy;
    private final PasswordHashService passwords;
    private final EmailVerificationRequestLock emailLock;
    private final EmailVerificationRepository verifications;
    private final EmailVerificationTokenGenerator tokens;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public PasswordResetService(EmailSignupPasswordPolicy passwordPolicy, PasswordHashService passwords,
            EmailVerificationRequestLock emailLock, EmailVerificationRepository verifications,
            EmailVerificationTokenGenerator tokens, JdbcTemplate jdbc,
            @Qualifier("emailVerificationClock") Clock clock) {
        this.passwordPolicy = passwordPolicy;
        this.passwords = passwords;
        this.emailLock = emailLock;
        this.verifications = verifications;
        this.tokens = tokens;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void reset(PasswordResetRequest request) {
        if (!passwordPolicy.isValid(request.password()))
            throw new AuthException(AuthErrorCode.INVALID_SIGNUP_PASSWORD);
        if (request.verificationToken() == null || !request.verificationToken().matches("[A-Za-z0-9_-]{43}"))
            throw invalid();
        emailLock.lockForEmail(request.email());
        EmailVerification proof = verifications
                .findFirstByEmailIgnoreCaseAndPurposeOrderByIdDesc(request.email(), VerificationPurpose.PASSWORD_RESET)
                .orElseThrow(this::invalid);
        validate(request.verificationToken(), proof, clock.instant());
        // 이메일 잠금 이후 member -> refresh_token 순서로 잠가 로그인·갱신과 순서를 맞춘다.
        var accounts = jdbc.query("""
                SELECT id,password_hash,signup_method,status,withdrawn_at,created_at FROM member
                WHERE upper(email)=upper(?) AND NOT(status='DELETED' AND withdrawn_at IS NOT NULL)
                FOR UPDATE
                """, (rs, n) -> new Account(rs.getLong(1), rs.getString(2),
                "LOCAL".equals(rs.getString(3)) && "ACTIVE".equals(rs.getString(4)) && rs.getTimestamp(5) == null,
                rs.getTimestamp(6).toInstant()), request.email());
        if (accounts.isEmpty()) throw new AuthException(AuthErrorCode.EMAIL_NOT_REGISTERED);
        if (accounts.size() != 1) throw new AuthException(AuthErrorCode.PASSWORD_RESET_NOT_ALLOWED);
        Account member = accounts.getFirst();
        if (!member.eligible() || member.passwordHash() == null || member.passwordHash().isBlank())
            throw new AuthException(AuthErrorCode.PASSWORD_RESET_NOT_ALLOWED);
        // 탈퇴 후 이메일을 재사용한 새 계정에 이전 계정의 인증 요청을 사용하지 못하게 한다.
        if (proof.getCreatedAt() == null || proof.getCreatedAt().isBefore(member.createdAt())) throw invalid();
        String hash = passwords.hash(request.password());
        Instant completedAt = clock.instant();
        proof.consume(completedAt);
        jdbc.update("UPDATE member SET password_hash=?,updated_at=? WHERE id=?",
                hash, Timestamp.from(completedAt), member.id());
        jdbc.update("""
                UPDATE refresh_token SET revoked_at=COALESCE(revoked_at,?),updated_at=? WHERE member_id=?
                """, Timestamp.from(completedAt), Timestamp.from(completedAt), member.id());
        jdbc.update("UPDATE login_access_token SET revoked_at=COALESCE(revoked_at,?) WHERE member_id=?",
                Timestamp.from(completedAt), member.id());
        verifications.flush();
    }

    private void validate(String token, EmailVerification proof, Instant now) {
        if (proof.getConsumedAt() != null || proof.getVerifiedAt() == null || proof.getLastSentAt() == null
                || proof.getVerificationTokenHash() == null || proof.getVerificationExpiresAt() == null
                || !MessageDigest.isEqual(tokens.hash(token).getBytes(StandardCharsets.UTF_8),
                        proof.getVerificationTokenHash().getBytes(StandardCharsets.UTF_8))) throw invalid();
        if (!now.isBefore(proof.getVerificationExpiresAt()))
            throw new AuthException(AuthErrorCode.EMAIL_VERIFICATION_TOKEN_EXPIRED);
    }

    private AuthException invalid() { return new AuthException(AuthErrorCode.EMAIL_VERIFICATION_INVALID); }
    private record Account(long id, String passwordHash, boolean eligible, Instant createdAt) {}
}
