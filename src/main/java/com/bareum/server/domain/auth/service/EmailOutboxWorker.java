package com.bareum.server.domain.auth.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.bareum.server.domain.auth.config.EmailOutboxProperties;
import com.bareum.server.domain.auth.entity.EmailVerification;
import com.bareum.server.domain.auth.repository.EmailOutboxRepository;
import com.bareum.server.domain.auth.repository.EmailOutboxRepository.Job;
import com.bareum.server.domain.auth.repository.EmailVerificationRepository;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class EmailOutboxWorker {
	private final EmailOutboxProperties properties;
	private final EmailOutboxRepository outbox;
	private final EmailVerificationRepository verifications;
	private final EmailVerificationRequestLock requestLock;
	private final EmailVerificationAccountValidator accounts;
	private final EmailVerificationMailSender mailSender;
	private final EmailOutboxCipher cipher;
	private final Clock clock;
	private final TransactionTemplate transaction;
	private final java.util.concurrent.atomic.AtomicBoolean configurationWarning =
			new java.util.concurrent.atomic.AtomicBoolean();

	public EmailOutboxWorker(
		EmailOutboxProperties properties,
		EmailOutboxRepository outbox,
		EmailVerificationRepository verifications,
		EmailVerificationRequestLock requestLock,
		EmailVerificationAccountValidator accounts,
		EmailVerificationMailSender mailSender,
		EmailOutboxCipher cipher,
		Clock emailVerificationClock,
		PlatformTransactionManager transactionManager
	) {
		this.properties = properties;
		this.outbox = outbox;
		this.verifications = verifications;
		this.requestLock = requestLock;
		this.accounts = accounts;
		this.mailSender = mailSender;
		this.cipher = cipher;
		this.clock = emailVerificationClock;
		this.transaction = new TransactionTemplate(transactionManager);
		transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
	}

	@Scheduled(fixedDelayString = "${auth.email-verification.outbox.poll-delay-ms:1000}",
		initialDelayString = "${auth.email-verification.outbox.poll-delay-ms:1000}")
	public void poll() {
		if (!properties.enabled()) {
			return;
		}
		try {
			cipher.requireConfigured();
			configurationWarning.set(false);
			for (int index = 0; index < properties.batchSize() && runOnce(); index++) {
				// Each job releases its connection before contacting SMTP.
			}
		} catch (com.bareum.server.domain.auth.exception.AuthException exception) {
			if (configurationWarning.compareAndSet(false, true)) {
				log.warn("Email outbox configuration is unavailable; pending jobs are retained.");
			}
		} catch (RuntimeException exception) {
			log.warn("Email outbox polling interrupted; committed jobs remain recoverable.");
		}
	}

	public boolean runOnce() {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("Email outbox worker must run outside a transaction");
		}
		cipher.requireConfigured();
		Optional<Job> claimed = transaction.execute(status -> outbox.claim(clock.instant(), properties));
		if (claimed == null || claimed.isEmpty()) {
			return false;
		}
		Job job = claimed.get();
		boolean eligible = Boolean.TRUE.equals(transaction.execute(status -> prepare(job)));
		if (!eligible) {
			return true;
		}
		// Account validation has its own read transaction. Catching its business rejection
		// inside the preparation transaction would leave that transaction rollback-only.
		try {
			if (!job.email().equals(accounts.resolveRecipient(job.email(), job.purpose()))) {
				cancel(job);
				return true;
			}
		} catch (com.bareum.server.domain.auth.exception.AuthException exception) {
			cancel(job);
			return true;
		}
		try {
			String code = cipher.decrypt(job.id(), job.verificationId(),
					EmailOutboxCipher.context(job.email(), job.purpose(), job.ttlSeconds(), job.codeHash()),
					job.keyId(), job.encryptedCode());
			// No transaction, advisory lock, or pooled connection is held during the network call.
			mailSender.sendVerificationCode(job.email(), code, job.purpose());
		} catch (RuntimeException exception) {
			transaction.executeWithoutResult(status -> {
				if (outbox.lockOwned(job)) {
					outbox.retry(job, clock.instant(), properties);
				}
			});
			return true;
		}
		Instant acceptedAt = clock.instant();
		// A failed commit leaves the claimed job intact for recovery after its lease expires.
		transaction.executeWithoutResult(status -> {
			requestLock.lockForEmail(job.email());
			if (!outbox.lockOwned(job)) {
				return;
			}
			Optional<EmailVerification> current = current(job);
			if (current.isEmpty()) {
				outbox.finish(job, "CANCELLED", acceptedAt);
				return;
			}
			current.get().markSent(acceptedAt, job.ttlSeconds());
			outbox.recordSent(job, acceptedAt);
			outbox.finish(job, "SENT", acceptedAt);
		});
		return true;
	}

	private void cancel(Job job) {
		transaction.executeWithoutResult(status -> {
			requestLock.lockForEmail(job.email());
			if (outbox.lockOwned(job)) {
				outbox.finish(job, "CANCELLED", clock.instant());
			}
		});
	}

	private boolean prepare(Job job) {
		requestLock.lockForEmail(job.email());
		if (!outbox.lockOwned(job)) {
			return false;
		}
		if (current(job).isEmpty()) {
			outbox.finish(job, "CANCELLED", clock.instant());
			return false;
		}
		return true;
	}

	private Optional<EmailVerification> current(Job job) {
		return verifications.findFirstByEmailIgnoreCaseAndPurposeOrderByIdDesc(job.email(), job.purpose())
				.filter(v -> v.getId() == job.verificationId() && job.codeHash().equals(v.getCodeHash())
						&& v.getConsumedAt() == null && v.getVerifiedAt() == null);
	}
}
