package com.bareum.server.domain.auth.service;

import com.bareum.server.domain.auth.dto.request.EmailSignupRequest;
import com.bareum.server.domain.auth.dto.response.EmailSignupResponse;
import com.bareum.server.domain.auth.entity.EmailVerification;
import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.repository.EmailVerificationRepository;
import com.bareum.server.domain.member.entity.Member;
import com.bareum.server.domain.member.repository.MemberRepository;
import com.bareum.server.domain.term.dto.request.TermConsentRequest;
import com.bareum.server.domain.term.entity.MemberTerm;
import com.bareum.server.domain.term.entity.Term;
import com.bareum.server.domain.term.repository.MemberTermRepository;
import com.bareum.server.domain.term.service.SignupTermConsentValidator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EmailSignupService {

    private final EmailSignupPasswordPolicy passwordPolicy;
    private final PasswordHashService passwordHashes;
    private final EmailVerificationRequestLock requestLock;
    private final EmailVerificationRepository verificationRepository;
    private final EmailVerificationTokenGenerator tokens;
    private final MemberRepository memberRepository;
    private final SignupTermConsentValidator termValidator;
    private final MemberTermRepository memberTermRepository;
    private final Clock emailVerificationClock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EmailSignupResponse signup(EmailSignupRequest request) {
        if (!passwordPolicy.isValid(request.password())) {
            throw new AuthException(AuthErrorCode.INVALID_SIGNUP_PASSWORD);
        }
        if (request.verificationToken() == null
                || !request.verificationToken().matches("[A-Za-z0-9_-]{43}")) {
            throw new AuthException(AuthErrorCode.EMAIL_VERIFICATION_INVALID);
        }
        requestLock.lockForEmail(request.email());
        EmailVerification verification = verificationRepository
                .findFirstByEmailIgnoreCaseAndPurposeOrderByIdDesc(request.email(), VerificationPurpose.SIGNUP)
                .orElseThrow(() -> new AuthException(AuthErrorCode.EMAIL_VERIFICATION_INVALID));
        Instant now = emailVerificationClock.instant();
        validateProof(request.verificationToken(), verification, now);
        if (memberRepository.existsByEmailIgnoreCase(request.email())) {
            throw new AuthException(AuthErrorCode.EMAIL_ALREADY_REGISTERED);
        }
        List<Term> terms = termValidator.validate(request.terms());
        String passwordHash = passwordHashes.hash(request.password());
        Instant completedAt = emailVerificationClock.instant();
        verification.consume(completedAt);
        Member member = Member.createLocal(request.email().toLowerCase(Locale.ROOT), request.name(), passwordHash);
        try {
            memberRepository.saveAndFlush(member);
        } catch (DataIntegrityViolationException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                        && "member_email_key".equals(violation.getConstraintName())) {
                    throw new AuthException(AuthErrorCode.EMAIL_ALREADY_REGISTERED);
                }
            }
            throw exception;
        }
        List<MemberTerm> consents = new ArrayList<>();
        for (int index = 0; index < terms.size(); index++) {
            TermConsentRequest consent = request.terms().get(index);
            consents.add(MemberTerm.create(member, terms.get(index),
                    consent.agreed() ? completedAt : null, null, consent.agreed(), completedAt));
        }
        memberTermRepository.saveAllAndFlush(consents);
        verificationRepository.flush();
        return EmailSignupResponse.of(member.getId());
    }

    private void validateProof(String token, EmailVerification verification, Instant now) {
        if (verification.getConsumedAt() != null || verification.getVerifiedAt() == null
                || verification.getVerificationTokenHash() == null || verification.getVerificationExpiresAt() == null
                || verification.getLastSentAt() == null
                || !MessageDigest.isEqual(tokens.hash(token).getBytes(StandardCharsets.UTF_8),
                        verification.getVerificationTokenHash().getBytes(StandardCharsets.UTF_8))) {
            throw new AuthException(AuthErrorCode.EMAIL_VERIFICATION_INVALID);
        }
        if (!now.isBefore(verification.getVerificationExpiresAt())) {
            throw new AuthException(AuthErrorCode.EMAIL_VERIFICATION_TOKEN_EXPIRED);
        }
    }
}
