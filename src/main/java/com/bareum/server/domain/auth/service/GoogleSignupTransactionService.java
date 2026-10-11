package com.bareum.server.domain.auth.service;

import com.bareum.server.domain.auth.dto.request.GoogleSignupRequest;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.repository.GoogleSignupProofRepository;
import com.bareum.server.domain.member.entity.Member;
import com.bareum.server.domain.member.entity.MemberAuth;
import com.bareum.server.domain.member.enums.OAuthProvider;
import com.bareum.server.domain.member.repository.MemberAuthRepository;
import com.bareum.server.domain.member.repository.MemberRepository;
import com.bareum.server.domain.term.entity.MemberTerm;
import com.bareum.server.domain.term.repository.MemberTermRepository;
import com.bareum.server.domain.term.service.SignupTermConsentValidator;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
public class GoogleSignupTransactionService {
    private final GoogleSignupProofRepository proofs;
    private final LoginTokenGenerator tokens;
    private final EmailVerificationRequestLock emailLock;
    private final MemberRepository members;
    private final MemberAuthRepository auths;
    private final MemberTermRepository memberTerms;
    private final SignupTermConsentValidator terms;
    private final LoginSessionService sessions;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public GoogleSignupTransactionService(
            GoogleSignupProofRepository proofs,
            LoginTokenGenerator tokens,
            EmailVerificationRequestLock emailLock,
            MemberRepository members,
            MemberAuthRepository auths,
            MemberTermRepository memberTerms,
            SignupTermConsentValidator terms,
            LoginSessionService sessions,
            JdbcTemplate jdbc,
            @Qualifier("loginClock") Clock clock) {
        this.proofs = proofs;
        this.tokens = tokens;
        this.emailLock = emailLock;
        this.members = members;
        this.auths = auths;
        this.memberTerms = memberTerms;
        this.terms = terms;
        this.sessions = sessions;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LoginSessionService.IssuedLogin signup(GoogleSignupRequest request) {
        String raw = request.signupToken();
        if (raw == null || !raw.matches("gs_[A-Za-z0-9_-]{43}")) throw invalid();
        String hash = tokens.hash("google-signup", raw);
        String subject = proofs.subject(hash).orElseThrow(this::invalid);
        // AUTH-004와 동일하게 Google 식별자 → 증명 행 → 이메일 순서로 잠급니다.
        jdbc.execute(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                (PreparedStatementCallback<Void>)
                        statement -> {
                            statement.setString(1, "bareum:google:" + subject);
                            statement.execute();
                            return null;
                        });
        var proof = proofs.lock(hash).orElseThrow(this::invalid);
        if (!subject.equals(proof.subject())
                || proof.consumedAt() != null
                || !clock.instant().isBefore(proof.expiresAt())) throw invalid();
        emailLock.lockForEmail(proof.email());
        if (Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM member_auth WHERE provider='GOOGLE' AND"
                            + " provider_user_id=?)",
                        Boolean.class,
                        subject))) throw invalid();
        var existing =
                jdbc.query(
                        "SELECT signup_method FROM member WHERE upper(email)=upper(?) AND NOT"
                            + " (status='DELETED' AND withdrawn_at IS NOT NULL)",
                        (rs, n) -> rs.getString(1),
                        proof.email());
        if (!existing.isEmpty()) {
            throw new AuthException(
                    existing.contains("LOCAL")
                            ? AuthErrorCode.EMAIL_LOGIN_REQUIRED
                            : AuthErrorCode.EMAIL_ALREADY_REGISTERED);
        }
        var consents = terms.validate(request.agreements());
        var completedAt = clock.instant();
        if (!completedAt.isBefore(proof.expiresAt())) throw invalid();
        // 이메일·이름·Google 식별자는 검증된 증명에서만 가져옵니다.
        var member = members.saveAndFlush(Member.createGoogle(proof.email(), proof.name()));
        auths.saveAndFlush(
                MemberAuth.create(
                        member, OAuthProvider.GOOGLE, subject, proof.email(), completedAt));
        memberTerms.saveAllAndFlush(
                consents.stream()
                        .map(
                                consent ->
                                        MemberTerm.create(
                                                member,
                                                consent.term(),
                                                consent.agreed() ? completedAt : null,
                                                null,
                                                consent.agreed(),
                                                completedAt))
                        .toList());
        if (!proofs.consume(hash, clock.instant())) throw invalid();
        jdbc.queryForObject(
                "SELECT id FROM member WHERE id=? FOR UPDATE", Long.class, member.getId());
        return sessions.issueForLockedMember(member.getId());
    }

    private AuthException invalid() {
        return new AuthException(AuthErrorCode.INVALID_GOOGLE_SIGNUP_PROOF);
    }
}
