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
public class EmailVerificationSendService {

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
    public EmailVerificationSendResponse send(
            EmailVerificationRequest request
    ) {
        long ttlSeconds = properties.codeTtlSeconds(request.purpose());

        requestLock.lockForEmail(request.email());

        accountValidator.checkCanSend(
                request.email(),
                request.purpose()
        );

        int previousAttemptCount = verificationRepository
                .findFirstByEmailIgnoreCaseAndPurposeOrderByIdDesc(
                        request.email(),
                        request.purpose()
                )
                .map(EmailVerification::getAttemptCount)
                .orElse(0);

        Instant now = emailVerificationClock.instant();

        limitChecker.checkCanSend(
                request.email(),
                request.purpose(),
                now
        );

        String code = codeGenerator.generate();
        String codeHash = codeHasher.hash(
                request.email(),
                request.purpose(),
                code
        );

        EmailVerification verification = EmailVerification.request(
                request.email(),
                request.purpose(),
                codeHash,
                now.plusSeconds(ttlSeconds),
                previousAttemptCount
        );

        verificationRepository.saveAndFlush(verification);

        Instant sentAt = emailVerificationClock.instant();
        verification.markSent(sentAt);

        sendLogRepository.saveAndFlush(
                EmailVerificationSendLog.sent(
                        request.email(),
                        request.purpose(),
                        sentAt
                )
        );

        mailSender.sendVerificationCode(
                request.email(),
                code,
                request.purpose()
        );

        Duration remaining = Duration.between(
                emailVerificationClock.instant(),
                verification.getExpiresAt()
        );

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
