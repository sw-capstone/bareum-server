package com.bareum.server.domain.auth.repository;

import com.bareum.server.domain.auth.entity.EmailVerification;
import com.bareum.server.domain.auth.enums.VerificationPurpose;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailVerificationRepository
        extends JpaRepository<EmailVerification, Long> {

    Optional<EmailVerification>
    findFirstByEmailIgnoreCaseAndPurposeOrderByIdDesc(
            String email,
            VerificationPurpose purpose
    );

    Optional<EmailVerification>
    findFirstByEmailIgnoreCaseAndLastSentAtIsNotNullOrderByLastSentAtDesc(
            String email
    );

    long countByEmailIgnoreCaseAndLastSentAtGreaterThanEqual(
            String email,
            Instant since
    );
}
