package com.bareum.server.domain.auth.service;

import com.bareum.server.domain.auth.config.EmailVerificationProperties;
import com.bareum.server.domain.auth.dto.request.EmailVerificationRequest;
import com.bareum.server.domain.auth.dto.response.EmailVerificationSendResponse;
import com.bareum.server.domain.auth.entity.EmailVerification;
import com.bareum.server.domain.auth.entity.EmailVerificationSendLog;
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

        String recipient = accountValidator.resolveRecipient(
                request.email(), request.purpose()
        );

        int previousAttemptCount = verificationRepository
                .findFirstByEmailIgnoreCaseAndPurposeOrderByIdDesc(
                        recipient,
                        request.purpose()
                )
                .map(EmailVerification::getAttemptCount)
                .orElse(0);

        Instant now = emailVerificationClock.instant();

        limitChecker.checkCanSend(
                recipient,
                request.purpose(),
                now
        );

        String code = codeGenerator.generate();
        String codeHash = codeHasher.hash(
                recipient,
                request.purpose(),
                code
        );

        EmailVerification verification = EmailVerification.request(
                recipient,
                request.purpose(),
                codeHash,
                now.plusSeconds(ttlSeconds),
                previousAttemptCount
        );

        verificationRepository.saveAndFlush(verification);

        EmailVerificationSendLog sendLog = EmailVerificationSendLog.sent(
                recipient, request.purpose(), emailVerificationClock.instant()
        );
        sendLogRepository.saveAndFlush(sendLog);

        mailSender.sendVerificationCode(recipient, code, request.purpose());

        Instant acceptedAt = emailVerificationClock.instant();
        verification.markSent(acceptedAt, ttlSeconds);
        sendLog.markAccepted(acceptedAt);

        Duration remaining = Duration.between(
                emailVerificationClock.instant(),
                verification.getExpiresAt()
        );

        if (remaining.isNegative() || remaining.isZero()) {
            return EmailVerificationSendResponse.of(0);
        }

        long expiresIn = remaining.getSeconds();

        if (remaining.getNano() > 0) {
            expiresIn++;
        }

        return EmailVerificationSendResponse.of(expiresIn);
    }
}
