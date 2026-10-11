CREATE TABLE google_signup_proof (
    token_hash CHAR(64) PRIMARY KEY,
    google_subject VARCHAR(255) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT google_signup_proof_expiry CHECK (expires_at > created_at),
    CONSTRAINT google_signup_proof_consumed CHECK (consumed_at IS NULL OR consumed_at >= created_at)
);
CREATE INDEX google_signup_proof_expiry_idx ON google_signup_proof(expires_at);
