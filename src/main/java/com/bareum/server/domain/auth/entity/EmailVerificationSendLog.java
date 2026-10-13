package com.bareum.server.domain.auth.entity;

import com.bareum.server.domain.auth.enums.VerificationPurpose;
import com.bareum.server.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "email_verification_send_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmailVerificationSendLog extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 30)
    private VerificationPurpose purpose;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    private EmailVerificationSendLog(
            String email,
            VerificationPurpose purpose,
            Instant sentAt
    ) {
        this.email = Objects.requireNonNull(email, "email");
        this.purpose = Objects.requireNonNull(purpose, "purpose");
        this.sentAt = Objects.requireNonNull(sentAt, "sentAt");
    }

    public static EmailVerificationSendLog sent(
            String email,
            VerificationPurpose purpose,
            Instant sentAt
    ) {
        return new EmailVerificationSendLog(email, purpose, sentAt);
    }

    public void markAccepted(Instant acceptedAt) {
        this.sentAt = Objects.requireNonNull(acceptedAt, "acceptedAt");
    }
}
