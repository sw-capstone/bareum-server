CREATE TABLE login_access_token (
    token_hash CHAR(64) PRIMARY KEY,
    member_id BIGINT NOT NULL REFERENCES member(id) ON DELETE CASCADE,
    family_id UUID NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_login_access_token_member_family ON login_access_token(member_id, family_id);
CREATE INDEX idx_login_access_token_expiry ON login_access_token(expires_at);
