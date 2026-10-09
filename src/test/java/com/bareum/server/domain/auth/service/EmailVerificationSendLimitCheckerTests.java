package com.bareum.server.domain.auth.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bareum.server.domain.auth.config.EmailVerificationProperties;
import com.bareum.server.domain.auth.entity.EmailVerificationSendLog;
import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.repository.EmailVerificationSendLogRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmailVerificationSendLimitCheckerTests {

    private static final String EMAIL = "user@example.com";
    private static final Instant NOW =
            Instant.parse("2026-10-04T06:00:00Z");

    private final EmailVerificationSendLogRepository repository =
            mock(EmailVerificationSendLogRepository.class);

    private EmailVerificationSendLimitChecker checker;

    @BeforeEach
    void setUp() {
        EmailVerificationProperties properties =
                new EmailVerificationProperties();

        // 테스트 전용 값이며 실제 서비스 정책이 아닙니다.
        properties.setMinimumSendIntervalSeconds(60L);
        properties.setMaxSendsPerHour(5);
        properties.setMaxSendsPerDay(20);

        checker = new EmailVerificationSendLimitChecker(
                repository,
                properties
        );
    }

    @Test
    void missingConfigurationRejectsBeforeDatabaseQueries() {
        EmailVerificationProperties emptyProperties =
                new EmailVerificationProperties();

        EmailVerificationSendLimitChecker unconfiguredChecker =
                new EmailVerificationSendLimitChecker(
                        repository,
                        emptyProperties
                );

        AuthException exception = assertThrows(
                AuthException.class,
                () -> unconfiguredChecker.checkCanSend(
                        EMAIL,
                        VerificationPurpose.SIGNUP,
                        NOW
                )
        );

        assertSame(
                AuthErrorCode.EMAIL_VERIFICATION_NOT_CONFIGURED,
                exception.getBaseErrorCode()
        );

        verifyNoInteractions(repository);
    }

    @Test
    void countsBelowBothLimitsAllowSending() {
        givenNoPreviousSend();

        when(repository.countByEmailIgnoreCaseAndSentAtGreaterThanEqual(
                EMAIL,
                NOW.minusSeconds(3_600)
        )).thenReturn(4L);

        when(repository.countByEmailIgnoreCaseAndSentAtGreaterThanEqual(
                EMAIL,
                NOW.minusSeconds(86_400)
        )).thenReturn(19L);

        assertDoesNotThrow(() -> checker.checkCanSend(
                EMAIL,
                VerificationPurpose.SIGNUP,
                NOW
        ));

        verify(repository)
                .countByEmailIgnoreCaseAndSentAtGreaterThanEqual(
                        EMAIL,
                        NOW.minusSeconds(3_600)
                );

        verify(repository)
                .countByEmailIgnoreCaseAndSentAtGreaterThanEqual(
                        EMAIL,
                        NOW.minusSeconds(86_400)
                );
    }

    @Test
    void sendingBeforeMinimumIntervalIsRejected() {
        EmailVerificationSendLog lastSend =
                EmailVerificationSendLog.sent(
                        EMAIL,
                        VerificationPurpose.SIGNUP,
                        NOW.minusSeconds(59)
                );

        when(repository.findFirstByEmailIgnoreCaseOrderBySentAtDesc(EMAIL))
                .thenReturn(Optional.of(lastSend));

        assertSendLimitRejected();
    }

    @Test
    void sendingExactlyAtMinimumIntervalIsAllowed() {
        EmailVerificationSendLog lastSend =
                EmailVerificationSendLog.sent(
                        EMAIL,
                        VerificationPurpose.SIGNUP,
                        NOW.minusSeconds(60)
                );

        when(repository.findFirstByEmailIgnoreCaseOrderBySentAtDesc(EMAIL))
                .thenReturn(Optional.of(lastSend));

        // その他 조회의 발송 횟수는 mock 기본값인 0입니다.
        assertDoesNotThrow(() -> checker.checkCanSend(
                EMAIL,
                VerificationPurpose.SIGNUP,
                NOW
        ));
    }

    @Test
    void reachingHourlyLimitRejectsSending() {
        givenNoPreviousSend();

        when(repository.countByEmailIgnoreCaseAndSentAtGreaterThanEqual(
                EMAIL,
                NOW.minusSeconds(3_600)
        )).thenReturn(5L);

        assertSendLimitRejected();
    }

    @Test
    void reachingDailyLimitRejectsSending() {
        givenNoPreviousSend();

        when(repository.countByEmailIgnoreCaseAndSentAtGreaterThanEqual(
                EMAIL,
                NOW.minusSeconds(3_600)
        )).thenReturn(0L);

        when(repository.countByEmailIgnoreCaseAndSentAtGreaterThanEqual(
                EMAIL,
                NOW.minusSeconds(86_400)
        )).thenReturn(20L);

        assertSendLimitRejected();
    }

    private void givenNoPreviousSend() {
        when(repository.findFirstByEmailIgnoreCaseOrderBySentAtDesc(EMAIL))
                .thenReturn(Optional.empty());
    }

    private void assertSendLimitRejected() {
        AuthException exception = assertThrows(
                AuthException.class,
                () -> checker.checkCanSend(
                        EMAIL,
                        VerificationPurpose.SIGNUP,
                        NOW
                )
        );

        assertSame(
                AuthErrorCode.EMAIL_SEND_LIMIT_EXCEEDED,
                exception.getBaseErrorCode()
        );
    }
}
