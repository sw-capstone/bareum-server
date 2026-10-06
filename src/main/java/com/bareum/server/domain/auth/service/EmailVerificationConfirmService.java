package com.bareum.server.domain.auth.service;

import com.bareum.server.domain.auth.config.EmailVerificationProperties;
import com.bareum.server.domain.auth.dto.request.EmailVerificationConfirmRequest;
import com.bareum.server.domain.auth.dto.response.EmailVerificationConfirmResponse;
import com.bareum.server.domain.auth.entity.EmailVerification;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.exception.EmailVerificationCodeMismatchException;
import com.bareum.server.domain.auth.repository.EmailVerificationRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EmailVerificationConfirmService {

    private final EmailVerificationProperties properties;
    private final EmailVerificationRequestLock requestLock;
    private final EmailVerificationRepository verificationRepository;
    private final EmailVerificationCodeHasher codeHasher;
    private final EmailVerificationTokenGenerator tokenGenerator;
    private final Clock emailVerificationClock;

    @Transactional(isolation = Isolation.READ_COMMITTED,
            noRollbackFor = EmailVerificationCodeMismatchException.class)
    public EmailVerificationConfirmResponse confirm(EmailVerificationConfirmRequest request) {
        long ttl = properties.verificationTtlSeconds(request.purpose());
        requestLock.lockForEmail(request.email());
        EmailVerification verification = verificationRepository
                .findFirstByEmailIgnoreCaseAndPurposeOrderByIdDesc(request.email(), request.purpose())
                .orElseThrow(() -> new AuthException(AuthErrorCode.EMAIL_VERIFICATION_INVALID));
        Instant now = emailVerificationClock.instant();

        if (verification.getConsumedAt() != null || verification.getLastSentAt() == null) {
            throw new AuthException(AuthErrorCode.EMAIL_VERIFICATION_INVALID);
        }
        if (verification.getVerifiedAt() != null) {
            throw new AuthException(AuthErrorCode.EMAIL_VERIFICATION_ALREADY_VERIFIED);
        }
        if (!now.isBefore(verification.getExpiresAt())) {
            throw new AuthException(AuthErrorCode.EMAIL_VERIFICATION_EXPIRED);
        }
        if (verification.getAttemptCount() >= properties.getMaxVerificationAttempts()) {
            throw new AuthException(AuthErrorCode.EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED);
        }
        if (!codeHasher.matches(request.email(), request.purpose(), request.code(), verification.getCodeHash())) {
            verification.recordFailedAttempt();
            throw new EmailVerificationCodeMismatchException();
        }

        String token = tokenGenerator.generate();
        verification.markVerified(tokenGenerator.hash(token), now, now.plusSeconds(ttl));
        return new EmailVerificationConfirmResponse(token, ttl);
    }
}
