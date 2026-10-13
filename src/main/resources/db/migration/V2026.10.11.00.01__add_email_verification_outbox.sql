CREATE TABLE email_verification_outbox (
    id UUID PRIMARY KEY,
    verification_id BIGINT NOT NULL REFERENCES email_verification(id),
    email VARCHAR(255) NOT NULL,
    purpose VARCHAR(30) NOT NULL CHECK (purpose IN ('SIGNUP', 'PASSWORD_RESET')),
    code_hash VARCHAR(255) NOT NULL,
    encrypted_code BYTEA,
    key_id VARCHAR(32) NOT NULL,
    code_ttl_seconds BIGINT NOT NULL CHECK (code_ttl_seconds > 0),
    state VARCHAR(16) NOT NULL CHECK (state IN ('QUEUED', 'SENDING', 'RETRY', 'SENT', 'FAILED', 'CANCELLED')),
    queued_at TIMESTAMPTZ NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    accepted_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK ((state IN ('QUEUED', 'SENDING', 'RETRY') AND encrypted_code IS NOT NULL)
        OR (state IN ('SENT', 'FAILED', 'CANCELLED') AND encrypted_code IS NULL)),
    CHECK ((state = 'SENDING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (state <> 'SENDING' AND lease_token IS NULL AND lease_until IS NULL))
);

CREATE UNIQUE INDEX email_verification_outbox_active_email_key
    ON email_verification_outbox (upper(email))
    WHERE state IN ('QUEUED', 'SENDING', 'RETRY');
CREATE INDEX email_verification_outbox_poll_idx
    ON email_verification_outbox (next_attempt_at, queued_at)
    WHERE state IN ('QUEUED', 'SENDING', 'RETRY');
CREATE INDEX email_verification_outbox_email_queued_idx
    ON email_verification_outbox (upper(email), queued_at);

ALTER TABLE email_verification_send_log ADD COLUMN outbox_id UUID
    REFERENCES email_verification_outbox(id);
CREATE UNIQUE INDEX email_verification_send_log_outbox_key
    ON email_verification_send_log(outbox_id) WHERE outbox_id IS NOT NULL;
