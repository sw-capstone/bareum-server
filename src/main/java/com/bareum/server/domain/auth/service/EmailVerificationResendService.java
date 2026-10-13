package com.bareum.server.domain.auth.service;

import com.bareum.server.domain.auth.config.EmailOutboxProperties;
import com.bareum.server.domain.auth.config.EmailVerificationProperties;
import com.bareum.server.domain.auth.dto.request.EmailVerificationRequest;
import com.bareum.server.domain.auth.dto.response.EmailVerificationDeliveryResponse;
import com.bareum.server.domain.auth.entity.EmailVerification;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.repository.EmailOutboxRepository;
import com.bareum.server.domain.auth.repository.EmailVerificationRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

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
    private final EmailOutboxRepository outbox;
    private final EmailOutboxCipher cipher;
    private final EmailOutboxProperties outboxProperties;
    private final EmailVerificationMailSender mailSender;
    private final Clock emailVerificationClock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EmailVerificationDeliveryResponse resend(EmailVerificationRequest request) {
        long ttlSeconds = properties.codeTtlSeconds(request.purpose());
        cipher.requireConfigured();
        mailSender.requireConfigured();
        requestLock.lockForEmail(request.email());
        EmailVerification verification =
                verificationRepository
                        .findFirstByEmailIgnoreCaseAndPurposeOrderByIdDesc(
                                request.email(), request.purpose())
                        .orElseThrow(
                                () -> new AuthException(AuthErrorCode.EMAIL_VERIFICATION_INVALID));
        if (verification.getConsumedAt() != null) {
            throw new AuthException(AuthErrorCode.EMAIL_VERIFICATION_INVALID);
        }
        if (verification.getVerifiedAt() != null
                || verification.getVerificationTokenHash() != null
                || verification.getVerificationExpiresAt() != null) {
            // Completed proof replacement remains blocked until the recovery policy is agreed.
            throw new AuthException(AuthErrorCode.EMAIL_VERIFICATION_NOT_CONFIGURED);
        }
        String recipient =
                accountValidator.resolveRecipient(verification.getEmail(), request.purpose());
        Instant now = emailVerificationClock.instant();
        limitChecker.checkCanSend(recipient, request.purpose(), now);
        // Reservations include in-flight jobs across initial send and resend; no live job is
        // replaced.
        outbox.checkCanQueue(recipient, now, properties);
        if (verification.getLastSentAt() == null && !outbox.hasTerminalDelivery(verification)) {
            throw new AuthException(AuthErrorCode.EMAIL_VERIFICATION_INVALID);
        }
        String code = codeGenerator.generate();
        if (codeHasher.matches(
                verification.getEmail(), request.purpose(), code, verification.getCodeHash())) {
            throw new AuthException(AuthErrorCode.EMAIL_SEND_FAILED);
        }
        String hash = codeHasher.hash(recipient, request.purpose(), code);
        verification.queueReplacement(recipient, hash, now);
        verificationRepository.flush();
        UUID id = UUID.randomUUID();
        byte[] encrypted =
                cipher.encrypt(
                        id,
                        verification.getId(),
                        EmailOutboxCipher.context(recipient, request.purpose(), ttlSeconds, hash),
                        code);
        outbox.enqueue(id, verification, encrypted, outboxProperties.keyId(), ttlSeconds, now);
        return EmailVerificationDeliveryResponse.pending(id);
    }
}
