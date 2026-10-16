ALTER TABLE email_verification
    ADD COLUMN attempts_blocked_until TIMESTAMPTZ NULL;
