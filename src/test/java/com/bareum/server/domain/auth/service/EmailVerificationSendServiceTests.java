package com.bareum.server.domain.auth.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.bareum.server.domain.auth.config.EmailOutboxProperties;
import com.bareum.server.domain.auth.config.EmailVerificationProperties;
import com.bareum.server.domain.auth.dto.request.EmailVerificationRequest;
import com.bareum.server.domain.auth.entity.EmailVerification;
import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.domain.auth.exception.AuthErrorCode;
import com.bareum.server.domain.auth.exception.AuthException;
import com.bareum.server.domain.auth.repository.EmailOutboxRepository;
import com.bareum.server.domain.auth.repository.EmailVerificationRepository;

class EmailVerificationSendServiceTests {
	private static final String EMAIL = "user@example.invalid";
	private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");
	private final EmailVerificationRequestLock lock = mock(EmailVerificationRequestLock.class);
	private final EmailVerificationAccountValidator accounts = mock(EmailVerificationAccountValidator.class);
	private final EmailVerificationSendLimitChecker limits = mock(EmailVerificationSendLimitChecker.class);
	private final EmailVerificationCodeGenerator codes = mock(EmailVerificationCodeGenerator.class);
	private final EmailVerificationCodeHasher hasher = mock(EmailVerificationCodeHasher.class);
	private final EmailVerificationRepository verifications = mock(EmailVerificationRepository.class);
	private final EmailOutboxRepository outbox = mock(EmailOutboxRepository.class);
	private final EmailOutboxCipher cipher = mock(EmailOutboxCipher.class);
	private final EmailVerificationMailSender sender = mock(EmailVerificationMailSender.class);
	private EmailVerificationProperties policy = new EmailVerificationProperties(300, 420L, 60L, 5, 20);
	private EmailVerificationSendService service;

	@BeforeEach
	void setUp() {
		when(accounts.resolveRecipient(anyString(), any())).thenReturn(EMAIL);
		when(codes.generate()).thenReturn("123456");
		when(hasher.hash(anyString(), any(), anyString())).thenReturn("hashed-code");
		when(verifications.findFirstByEmailIgnoreCaseAndPurposeOrderByIdDesc(anyString(), any()))
				.thenReturn(Optional.empty());
		doAnswer(invocation -> {
			var verification = (EmailVerification)invocation.getArgument(0);
			org.springframework.test.util.ReflectionTestUtils.setField(verification, "id", 1L);
			return verification;
		}).when(verifications).saveAndFlush(any());
		when(cipher.encrypt(any(), anyLong(), anyString(), anyString())).thenReturn(new byte[] {1, 2, 3});
		service = service();
	}

	@Test
	void storesPendingHashAndEncryptedJobWithoutCallingSmtp() {
		var response = service.send(request());
		var saved = ArgumentCaptor.forClass(EmailVerification.class);
		verify(verifications).saveAndFlush(saved.capture());
		assertEquals("hashed-code", saved.getValue().getCodeHash());
		assertEquals(NOW, saved.getValue().getExpiresAt());
		assertNull(saved.getValue().getLastSentAt());
		assertEquals("PENDING", response.status());
		assertEquals(0, response.expiresIn());
		verify(outbox).enqueue(eq(response.requestId()), eq(saved.getValue()), eq(new byte[] {1, 2, 3}),
				eq("v1"),
				eq(300L), eq(NOW));
		verify(sender, never()).sendVerificationCode(anyString(), anyString(), any());
	}

	@Test
	void missingPolicyStopsBeforeLockAndQueue() {
		policy = new EmailVerificationProperties(300, null, null, 5, 20);
		service = service();
		assertFailure(AuthErrorCode.EMAIL_VERIFICATION_NOT_CONFIGURED);
		verifyNoInteractions(lock, outbox, verifications, codes);
	}

	@Test
	void missingEncryptionKeyStopsBeforeLockAndQueue() {
		doThrow(new AuthException(AuthErrorCode.EMAIL_VERIFICATION_NOT_CONFIGURED)).when(cipher).requireConfigured();
		assertFailure(AuthErrorCode.EMAIL_VERIFICATION_NOT_CONFIGURED);
		verifyNoInteractions(lock, outbox, verifications, codes);
	}

	@Test
	void missingSmtpConfigurationStopsBeforeLockAndQueue() {
		doThrow(new AuthException(AuthErrorCode.EMAIL_VERIFICATION_NOT_CONFIGURED)).when(sender).requireConfigured();
		assertFailure(AuthErrorCode.EMAIL_VERIFICATION_NOT_CONFIGURED);
		verifyNoInteractions(lock, outbox, verifications, codes);
	}

	@Test
	void accountRejectionStopsBeforeCodeGeneration() {
		when(accounts.resolveRecipient(EMAIL, VerificationPurpose.SIGNUP))
				.thenThrow(new AuthException(AuthErrorCode.EMAIL_ALREADY_REGISTERED));
		assertFailure(AuthErrorCode.EMAIL_ALREADY_REGISTERED);
		verifyNoInteractions(codes, outbox, verifications);
	}

	@Test
	void rateLimitStopsBeforeCodeGeneration() {
		doThrow(new AuthException(AuthErrorCode.EMAIL_SEND_LIMIT_EXCEEDED))
				.when(limits)
				.checkCanSend(EMAIL, VerificationPurpose.SIGNUP, NOW);
		assertFailure(AuthErrorCode.EMAIL_SEND_LIMIT_EXCEEDED);
		verifyNoInteractions(codes, outbox, verifications);
	}

	@Test
	void pendingReservationStopsBeforeCodeGeneration() {
		doThrow(new AuthException(AuthErrorCode.EMAIL_SEND_LIMIT_EXCEEDED))
				.when(outbox)
				.checkCanQueue(EMAIL, NOW, policy);
		assertFailure(AuthErrorCode.EMAIL_SEND_LIMIT_EXCEEDED);
		verifyNoInteractions(codes, verifications);
	}

	@Test
	void preservesPreviousAttemptCount() {
		when(verifications.findFirstByEmailIgnoreCaseAndPurposeOrderByIdDesc(EMAIL, VerificationPurpose.SIGNUP))
				.thenReturn(Optional.of(EmailVerification.request(EMAIL, VerificationPurpose.SIGNUP, "old", NOW, 2)));
		service.send(request());
		var saved = ArgumentCaptor.forClass(EmailVerification.class);
		verify(verifications).saveAndFlush(saved.capture());
		assertEquals(2, saved.getValue().getAttemptCount());
	}

	@Test
	void passwordResetQueuesRegisteredAddressForStorageHashAndDelivery() {
		var request = new EmailVerificationRequest("USER@EXAMPLE.INVALID", VerificationPurpose.PASSWORD_RESET);
		var response = service.send(request);
		verify(lock).lockForEmail(request.email());
		verify(hasher).hash(EMAIL, VerificationPurpose.PASSWORD_RESET, "123456");
		var saved = ArgumentCaptor.forClass(EmailVerification.class);
		verify(verifications).saveAndFlush(saved.capture());
		assertEquals(EMAIL, saved.getValue().getEmail());
		verify(outbox).enqueue(eq(response.requestId()), eq(saved.getValue()), any(), eq("v1"), eq(420L), eq(NOW));
	}

	private EmailVerificationRequest request() {
		return new EmailVerificationRequest(EMAIL, VerificationPurpose.SIGNUP);
	}

	private void assertFailure(AuthErrorCode code) {
		assertSame(code, assertThrows(AuthException.class, () -> service.send(request())).getBaseErrorCode());
	}

	private EmailVerificationSendService service() {
		return new EmailVerificationSendService(policy, lock, accounts, limits, codes, hasher, verifications, outbox,
				cipher, new EmailOutboxProperties(true, 3, 5, 120, 300, 10, "v1", "test"), sender,
				Clock.fixed(NOW, ZoneOffset.UTC));
	}
}
