package com.bareum.server.domain.auth.service;

import com.bareum.server.domain.auth.config.EmailVerificationProperties;
import com.bareum.server.domain.auth.dto.request.EmailVerificationRequest;
import com.bareum.server.domain.auth.dto.response.EmailVerificationSendResponse;
import com.bareum.server.domain.auth.entity.EmailVerification;
import com.bareum.server.domain.auth.entity.EmailVerificationSendLog;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.repository.EmailVerificationRepository;
import com.bareum.server.domain.auth.repository.EmailVerificationSendLogRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EmailVerificationResendService {

    private final EmailVerificationProperties properties;
    private final EmailVerificationRequestLock requestLock;
    private final EmailVerificationAccountValidator accountValidator;
    private final EmailVerificationSendLimitChecker limitChecker;
    private final EmailVerificationCodeGenerator codeGenerator;
    private final EmailVerificationCodeHasher codeHasher;
    private final EmailVerificationRepository verificationRepository;
    private final EmailVerificationSendLogRepository sendLogRepository;
    private final EmailVerificationMailSender mailSender;
    private final Clock emailVerificationClock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EmailVerificationSendResponse resend(EmailVerificationRequest request) {
        long ttlSeconds = properties.codeTtlSeconds(request.purpose());
        requestLock.lockForEmail(request.email());

        EmailVerification verification = verificationRepository
                .findFirstByEmailIgnoreCaseAndPurposeOrderByIdDesc(request.email(), request.purpose())
                .orElseThrow(() -> new AuthException(AuthErrorCode.EMAIL_VERIFICATION_INVALID));

        if (verification.getLastSentAt() == null || verification.getConsumedAt() != null) {
            throw new AuthException(AuthErrorCode.EMAIL_VERIFICATION_INVALID);
        }
        if (verification.getVerifiedAt() != null
                || verification.getVerificationTokenHash() != null
                || verification.getVerificationExpiresAt() != null) {
            // 인증 완료 후 재발송·기존 증명 처리 정책이 정해지기 전에는 변경하지 않습니다.
            throw new AuthException(AuthErrorCode.EMAIL_VERIFICATION_NOT_CONFIGURED);
        }

        accountValidator.checkCanSend(request.email(), request.purpose());
        Instant now = emailVerificationClock.instant();
        limitChecker.checkCanSend(request.email(), request.purpose(), now);

        String code = codeGenerator.generate();
        if (codeHasher.matches(verification.getEmail(), request.purpose(), code, verification.getCodeHash())) {
            throw new AuthException(AuthErrorCode.EMAIL_SEND_FAILED);
        }
        String codeHash = codeHasher.hash(verification.getEmail(), request.purpose(), code);
        verification.replaceCode(codeHash, now.plusSeconds(ttlSeconds));
        Instant sentAt = emailVerificationClock.instant();
        verification.markSent(sentAt);
        verificationRepository.flush();
        sendLogRepository.saveAndFlush(
                EmailVerificationSendLog.sent(verification.getEmail(), request.purpose(), sentAt));
        mailSender.sendVerificationCode(verification.getEmail(), code, request.purpose());

        Duration remaining = Duration.between(emailVerificationClock.instant(), verification.getExpiresAt());
        if (remaining.isNegative() || remaining.isZero()) {
            throw new AuthException(AuthErrorCode.EMAIL_SEND_FAILED);
        }
        long expiresIn = remaining.getSeconds();
        if (remaining.getNano() > 0) {
            expiresIn++;
        }
        return EmailVerificationSendResponse.of(expiresIn);
    }
}
