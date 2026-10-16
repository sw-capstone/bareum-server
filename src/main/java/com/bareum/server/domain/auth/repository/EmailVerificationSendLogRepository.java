package com.bareum.server.domain.auth.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bareum.server.domain.auth.entity.EmailVerificationSendLog;

public interface EmailVerificationSendLogRepository
		extends JpaRepository<EmailVerificationSendLog, Long> {

	Optional<EmailVerificationSendLog>
			findFirstByEmailIgnoreCaseOrderBySentAtDesc(String email);

	long countByEmailIgnoreCaseAndSentAtGreaterThanEqual(
		String email,
		Instant since
	);
}
