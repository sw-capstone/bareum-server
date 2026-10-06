package com.bareum.server.domain.auth.service;

import com.bareum.server.domain.auth.config.EmailVerificationProperties;
import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.repository.EmailVerificationSendLogRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EmailVerificationSendLimitChecker {

    private static final long SECONDS_PER_HOUR = 3_600;
    private static final long SECONDS_PER_DAY = 86_400;

    private final EmailVerificationSendLogRepository sendLogRepository;
    private final EmailVerificationProperties properties;

    public void checkCanSend(
            String email,
            VerificationPurpose purpose,
            Instant now
    ) {
        properties.requireConfigured(purpose);

        checkMinimumInterval(email, now);
        checkHourlyLimit(email, now);
        checkDailyLimit(email, now);
    }

    private void checkMinimumInterval(String email, Instant now) {
        sendLogRepository.findFirstByEmailIgnoreCaseOrderBySentAtDesc(email)
                .ifPresent(lastSend -> {
                    Instant nextAllowedAt = lastSend.getSentAt()
                            .plusSeconds(
                                    properties.getMinimumSendIntervalSeconds()
                            );

                    if (now.isBefore(nextAllowedAt)) {
                        throw sendLimitExceeded();
                    }
                });
    }

    private void checkHourlyLimit(String email, Instant now) {
        long sentCount =
                sendLogRepository.countByEmailIgnoreCaseAndSentAtGreaterThanEqual(
                        email,
                        now.minusSeconds(SECONDS_PER_HOUR)
                );

        if (sentCount >= properties.getMaxSendsPerHour()) {
            throw sendLimitExceeded();
        }
    }

    private void checkDailyLimit(String email, Instant now) {
        long sentCount =
                sendLogRepository.countByEmailIgnoreCaseAndSentAtGreaterThanEqual(
                        email,
                        now.minusSeconds(SECONDS_PER_DAY)
                );

        if (sentCount >= properties.getMaxSendsPerDay()) {
            throw sendLimitExceeded();
        }
    }

    private AuthException sendLimitExceeded() {
        return new AuthException(
                AuthErrorCode.EMAIL_SEND_LIMIT_EXCEEDED
        );
    }
}
