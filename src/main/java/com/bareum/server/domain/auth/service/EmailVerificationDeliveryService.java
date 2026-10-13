package com.bareum.server.domain.auth.service;

import com.bareum.server.domain.auth.dto.response.EmailVerificationDeliveryResponse;
import com.bareum.server.domain.auth.repository.EmailOutboxRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EmailVerificationDeliveryService {
    private final EmailOutboxRepository outbox;
    private final Clock emailVerificationClock;

    @Transactional(readOnly = true)
    public Optional<EmailVerificationDeliveryResponse> status(UUID requestId) {
        return outbox.status(requestId, emailVerificationClock.instant());
    }
}
