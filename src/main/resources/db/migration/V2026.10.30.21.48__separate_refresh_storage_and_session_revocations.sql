-- Existing Access tables, columns and data are retained but no longer used by application code.
CREATE TABLE revoked_login_session (
    family_id UUID PRIMARY KEY,
    member_id BIGINT NOT NULL REFERENCES member(id) ON DELETE CASCADE,
    revoked_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX revoked_login_session_expiry_idx ON revoked_login_session(expires_at);

INSERT INTO revoked_login_session(family_id,member_id,revoked_at,expires_at)
SELECT family_id,member_id,MIN(revoked_at),MAX(expires_at)
FROM (
    SELECT family_id,member_id,revoked_at,expires_at FROM login_access_token WHERE revoked_at IS NOT NULL
    UNION ALL
    SELECT jti,member_id,revoked_at,expires_at FROM refresh_token WHERE revoked_at IS NOT NULL
) revoked
GROUP BY family_id,member_id;

-- New Refresh rows no longer require an Access hash or expiry. This does not delete existing values.
ALTER TABLE refresh_token DROP CONSTRAINT refresh_token_hash_pair;
