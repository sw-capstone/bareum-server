package com.bareum.server.domain.auth.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Immutable;

import com.bareum.server.domain.auth.enums.EmailOutboxState;
import com.bareum.server.domain.auth.enums.VerificationPurpose;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Read-only mapping; job writes and leases are managed by EmailOutboxRepository. */
@Entity
@Table(name = "email_verification_outbox")
@Immutable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmailOutbox {
	@Id
	@Column(name = "id", nullable = false)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "verification_id", nullable = false)
	private EmailVerification verification;

	@Column(name = "email", nullable = false, length = 255)
	private String email;

	@Enumerated(EnumType.STRING)
	@Column(name = "purpose", nullable = false, length = 30)
	private VerificationPurpose purpose;

	@Column(name = "code_hash", nullable = false, length = 255)
	private String codeHash;

	@Enumerated(EnumType.STRING)
	@Column(name = "state", nullable = false, length = 16)
	private EmailOutboxState state;

	@Column(name = "queued_at", nullable = false)
	private Instant queuedAt;
}
