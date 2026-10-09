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
        // 테스트 전용 제한 값이며 실제 서비스 정책이 아닙니다.
        properties = new EmailVerificationProperties(300, 420L, 60L, 5, 20);
        when(accountValidator.resolveRecipient(EMAIL, VerificationPurpose.SIGNUP))
                .thenReturn(EMAIL);

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

        verify(accountValidator).resolveRecipient(
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
        properties = new EmailVerificationProperties(300, null, null, 5, 20);
        service = createService(Clock.fixed(NOW, ZoneOffset.UTC));

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
        )).when(accountValidator).resolveRecipient(
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
    void slowSmtpStartsValidityAtAcceptanceWithoutExpirationRollback() {
        Clock advancingClock = mock(Clock.class);

        when(advancingClock.instant()).thenReturn(
                NOW,
                NOW,
                NOW.plusSeconds(300),
                NOW.plusSeconds(300)
        );

        service = createService(advancingClock);

        EmailVerificationSendResponse response = service.send(request);
        assertEquals(300L, response.expiresIn());
        ArgumentCaptor<EmailVerification> saved = ArgumentCaptor.forClass(EmailVerification.class);
        verify(verificationRepository).saveAndFlush(saved.capture());
        assertEquals(NOW.plusSeconds(600), saved.getValue().getExpiresAt());
        assertEquals(NOW.plusSeconds(300), saved.getValue().getLastSentAt());

        verify(mailSender).sendVerificationCode(
                EMAIL,
                CODE,
                VerificationPurpose.SIGNUP
        );
    }

    @Test
    void clockJumpAfterAcceptanceReturnsZeroWithoutSendFailure() {
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(NOW, NOW, NOW, NOW.plusSeconds(301));
        service = createService(clock);
        assertEquals(0L, service.send(request).expiresIn());
    }

    @Test
    void passwordResetUsesRegisteredEmailForHashStorageAndDelivery() {
        String registered = "Registered@Example.com";
        when(accountValidator.resolveRecipient(EMAIL, VerificationPurpose.PASSWORD_RESET))
                .thenReturn(registered);
        when(codeHasher.hash(registered, VerificationPurpose.PASSWORD_RESET, CODE))
                .thenReturn(CODE_HASH);
        service.send(new EmailVerificationRequest(EMAIL, VerificationPurpose.PASSWORD_RESET));
        ArgumentCaptor<EmailVerification> saved = ArgumentCaptor.forClass(EmailVerification.class);
        verify(verificationRepository).saveAndFlush(saved.capture());
        assertEquals(registered, saved.getValue().getEmail());
        assertEquals(CODE_HASH, saved.getValue().getCodeHash());
        ArgumentCaptor<EmailVerificationSendLog> log = ArgumentCaptor.forClass(EmailVerificationSendLog.class);
        verify(sendLogRepository).saveAndFlush(log.capture());
        assertEquals(registered, log.getValue().getEmail());
        verify(mailSender).sendVerificationCode(registered, CODE, VerificationPurpose.PASSWORD_RESET);
        verify(requestLock).lockForEmail(EMAIL);
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
