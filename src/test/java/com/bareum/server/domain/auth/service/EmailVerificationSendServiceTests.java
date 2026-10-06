package com.bareum.server.domain.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bareum.server.domain.auth.config.EmailVerificationProperties;
import com.bareum.server.domain.auth.dto.request.EmailVerificationRequest;
import com.bareum.server.domain.auth.dto.response.EmailVerificationSendResponse;
import com.bareum.server.domain.auth.entity.EmailVerification;
import com.bareum.server.domain.auth.entity.EmailVerificationSendLog;
import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.repository.EmailVerificationRepository;
import com.bareum.server.domain.auth.repository.EmailVerificationSendLogRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EmailVerificationSendServiceTests {

    private static final String EMAIL = "user@example.com";
    private static final String CODE = "123456";
    private static final String CODE_HASH = "test-code-hash";
    private static final Instant NOW =
            Instant.parse("2026-10-04T07:00:00Z");

    private final EmailVerificationRequestLock requestLock =
            mock(EmailVerificationRequestLock.class);

    private final EmailVerificationAccountValidator accountValidator =
            mock(EmailVerificationAccountValidator.class);

    private final EmailVerificationSendLimitChecker limitChecker =
            mock(EmailVerificationSendLimitChecker.class);

    private final EmailVerificationCodeGenerator codeGenerator =
            mock(EmailVerificationCodeGenerator.class);

    private final EmailVerificationCodeHasher codeHasher =
            mock(EmailVerificationCodeHasher.class);

    private final EmailVerificationRepository verificationRepository =
            mock(EmailVerificationRepository.class);

    private final EmailVerificationSendLogRepository sendLogRepository =
            mock(EmailVerificationSendLogRepository.class);

    private final EmailVerificationMailSender mailSender =
            mock(EmailVerificationMailSender.class);

    private final EmailVerificationRequest request =
            new EmailVerificationRequest(
                    EMAIL,
                    VerificationPurpose.SIGNUP
            );

    private EmailVerificationProperties properties;
    private EmailVerificationSendService service;

    @BeforeEach
    void setUp() {
        properties = new EmailVerificationProperties();

        // 테스트 전용 제한 값이며 실제 서비스 정책이 아닙니다.
        properties.setMinimumSendIntervalSeconds(60L);
        properties.setMaxSendsPerHour(5);
        properties.setMaxSendsPerDay(20);

        when(codeGenerator.generate()).thenReturn(CODE);

        when(codeHasher.hash(
                EMAIL,
                VerificationPurpose.SIGNUP,
                CODE
        )).thenReturn(CODE_HASH);

        when(verificationRepository
                .findFirstByEmailIgnoreCaseAndPurposeOrderByIdDesc(
                        EMAIL,
                        VerificationPurpose.SIGNUP
                )).thenReturn(Optional.empty());

        service = createService(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void successfulSendingStoresHashAndSendLog() {
        EmailVerificationSendResponse response = service.send(request);

        ArgumentCaptor<EmailVerification> verificationCaptor =
                ArgumentCaptor.forClass(EmailVerification.class);

        verify(verificationRepository)
                .saveAndFlush(verificationCaptor.capture());

        EmailVerification saved = verificationCaptor.getValue();

        assertEquals(EMAIL, saved.getEmail());
        assertEquals(VerificationPurpose.SIGNUP, saved.getPurpose());
        assertEquals(CODE_HASH, saved.getCodeHash());
        assertNotEquals(CODE, saved.getCodeHash());
        assertEquals(NOW.plusSeconds(300), saved.getExpiresAt());
        assertEquals(NOW, saved.getLastSentAt());
        assertEquals(0, saved.getAttemptCount());

        ArgumentCaptor<EmailVerificationSendLog> logCaptor =
                ArgumentCaptor.forClass(EmailVerificationSendLog.class);

        verify(sendLogRepository).saveAndFlush(logCaptor.capture());

        EmailVerificationSendLog log = logCaptor.getValue();

        assertEquals(EMAIL, log.getEmail());
        assertEquals(VerificationPurpose.SIGNUP, log.getPurpose());
        assertEquals(NOW, log.getSentAt());
        assertEquals(300L, response.expiresIn());

        verify(requestLock).lockForEmail(EMAIL);

        verify(accountValidator).checkCanSend(
                EMAIL,
                VerificationPurpose.SIGNUP
        );

        verify(limitChecker).checkCanSend(
                EMAIL,
                VerificationPurpose.SIGNUP,
                NOW
        );

        verify(mailSender).sendVerificationCode(
                EMAIL,
                CODE,
                VerificationPurpose.SIGNUP
        );
    }

    @Test
    void missingConfigurationStopsBeforeLockAndSending() {
        properties.setMinimumSendIntervalSeconds(null);

        assertSendFails(
                AuthErrorCode.EMAIL_VERIFICATION_NOT_CONFIGURED
        );

        verifyNoInteractions(
                requestLock,
                accountValidator,
                limitChecker,
                mailSender,
                verificationRepository,
                sendLogRepository
        );
    }

    @Test
    void accountRejectionStopsBeforeCodeGenerationAndSending() {
        doThrow(new AuthException(
                AuthErrorCode.EMAIL_ALREADY_REGISTERED
        )).when(accountValidator).checkCanSend(
                EMAIL,
                VerificationPurpose.SIGNUP
        );

        assertSendFails(AuthErrorCode.EMAIL_ALREADY_REGISTERED);

        verifyNoInteractions(
                limitChecker,
                codeGenerator,
                codeHasher,
                verificationRepository,
                sendLogRepository,
                mailSender
        );
    }

    @Test
    void sendLimitRejectionStopsBeforeCodeGenerationAndSending() {
        doThrow(new AuthException(
                AuthErrorCode.EMAIL_SEND_LIMIT_EXCEEDED
        )).when(limitChecker).checkCanSend(
                EMAIL,
                VerificationPurpose.SIGNUP,
                NOW
        );

        assertSendFails(AuthErrorCode.EMAIL_SEND_LIMIT_EXCEEDED);

        verifyNoInteractions(
                codeGenerator,
                codeHasher,
                sendLogRepository,
                mailSender
        );
    }

    @Test
    void mailFailureIsPropagated() {
        doThrow(new AuthException(
                AuthErrorCode.EMAIL_SEND_FAILED
        )).when(mailSender).sendVerificationCode(
                EMAIL,
                CODE,
                VerificationPurpose.SIGNUP
        );

        assertSendFails(AuthErrorCode.EMAIL_SEND_FAILED);
    }

    @Test
    void newRequestPreservesPreviousAttemptCount() {
        EmailVerification previous = EmailVerification.request(
                EMAIL,
                VerificationPurpose.SIGNUP,
                "previous-test-hash",
                NOW.minusSeconds(1),
                2
        );

        when(verificationRepository
                .findFirstByEmailIgnoreCaseAndPurposeOrderByIdDesc(
                        EMAIL,
                        VerificationPurpose.SIGNUP
                )).thenReturn(Optional.of(previous));

        service.send(request);

        ArgumentCaptor<EmailVerification> captor =
                ArgumentCaptor.forClass(EmailVerification.class);

        verify(verificationRepository).saveAndFlush(captor.capture());

        assertEquals(2, captor.getValue().getAttemptCount());
    }

    @Test
    void expirationDuringSendingRejectsSuccessResponse() {
        Clock advancingClock = mock(Clock.class);

        when(advancingClock.instant()).thenReturn(
                NOW,
                NOW,
                NOW.plusSeconds(300)
        );

        service = createService(advancingClock);

        assertSendFails(AuthErrorCode.EMAIL_SEND_FAILED);

        verify(mailSender).sendVerificationCode(
                EMAIL,
                CODE,
                VerificationPurpose.SIGNUP
        );
    }

    private EmailVerificationSendService createService(Clock clock) {
        return new EmailVerificationSendService(
                properties,
                requestLock,
                accountValidator,
                limitChecker,
                codeGenerator,
                codeHasher,
                verificationRepository,
                sendLogRepository,
                mailSender,
                clock
        );
    }

    private void assertSendFails(AuthErrorCode expectedCode) {
        AuthException exception = assertThrows(
                AuthException.class,
                () -> service.send(request)
        );

        assertSame(expectedCode, exception.getBaseErrorCode());
    }
}
