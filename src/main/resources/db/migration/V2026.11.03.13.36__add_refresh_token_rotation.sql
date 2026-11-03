-- Refresh Token reuse detection is scoped to the original login family (jti).
CREATE TABLE used_refresh_token (
    token_hash CHAR(64) PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES refresh_token(id) ON DELETE CASCADE,
    family_id UUID NOT NULL,
    consumed_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT used_refresh_token_expiry CHECK (expires_at > consumed_at)
);
CREATE INDEX used_refresh_token_session_idx ON used_refresh_token(session_id,family_id);
CREATE INDEX used_refresh_token_expiry_idx ON used_refresh_token(expires_at);

CREATE TABLE token_renew_window (
    session_id BIGINT PRIMARY KEY REFERENCES refresh_token(id) ON DELETE CASCADE,
    family_id UUID NOT NULL,
    window_started_at TIMESTAMPTZ NOT NULL,
    request_count INTEGER NOT NULL CHECK (request_count > 0)
);
CREATE INDEX token_renew_window_started_idx ON token_renew_window(window_started_at);
