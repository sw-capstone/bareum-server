package com.bareum.server.domain.auth.service;

import com.bareum.server.domain.auth.config.EmailOutboxProperties;
import com.bareum.server.domain.auth.config.EmailVerificationProperties;
import com.bareum.server.domain.auth.config.EmailVerificationRecoveryProperties;
import com.bareum.server.domain.auth.dto.request.EmailVerificationRequest;
import com.bareum.server.domain.auth.dto.response.EmailVerificationDeliveryResponse;
import com.bareum.server.domain.auth.entity.EmailVerification;
import com.bareum.server.domain.auth.repository.EmailOutboxRepository;
import com.bareum.server.domain.auth.repository.EmailVerificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
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
    private final EmailOutboxRepository outbox;
    private final EmailOutboxCipher cipher;
    private final EmailOutboxProperties outboxProperties;
    private final EmailVerificationMailSender mailSender;
    private final EmailVerificationRecoveryProperties recovery;
    private final Clock emailVerificationClock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EmailVerificationDeliveryResponse send(EmailVerificationRequest request) {
        long ttlSeconds = properties.codeTtlSeconds(request.purpose());
        cipher.requireConfigured();
        mailSender.requireConfigured();
        requestLock.lockForEmail(request.email());
        String recipient = accountValidator.resolveRecipient(request.email(), request.purpose());
        Instant now = emailVerificationClock.instant();
        limitChecker.checkCanSend(recipient, request.purpose(), now);
        outbox.checkCanQueue(recipient, now, properties);
        int previousAttemptCount = verificationRepository
                .findFirstByEmailIgnoreCaseAndPurposeOrderByIdDesc(recipient, request.purpose())
                .map(previous -> previous.attemptsForNewCode(now,
                        properties.getMaxVerificationAttempts(), recovery.attemptCooldownSeconds()))
                .orElse(0);
        String code = codeGenerator.generate();
        String codeHash = codeHasher.hash(recipient, request.purpose(), code);
        // Pending requests are ineligible for code confirmation: lastSentAt stays null.
        EmailVerification verification = EmailVerification.request(
                recipient, request.purpose(), codeHash, now, previousAttemptCount);
        verificationRepository.saveAndFlush(verification);
        UUID id = UUID.randomUUID();
        byte[] encrypted = cipher.encrypt(id, verification.getId(),
                EmailOutboxCipher.context(recipient, request.purpose(), ttlSeconds, codeHash), code);
        outbox.enqueue(id, verification, encrypted, outboxProperties.keyId(), ttlSeconds, now);
        return EmailVerificationDeliveryResponse.pending(id);
    }
}
