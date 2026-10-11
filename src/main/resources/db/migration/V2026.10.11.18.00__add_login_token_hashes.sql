-- 기존 토큰 메타데이터는 보존한다. 해시가 없는 행은 새 인증 경로에서 사용하지 않는다.
ALTER TABLE refresh_token
    ADD COLUMN access_token_hash CHAR(64),
    ADD COLUMN refresh_token_hash CHAR(64),
    ADD COLUMN access_expires_at TIMESTAMPTZ,
    ADD COLUMN revoked_at TIMESTAMPTZ,
    ADD CONSTRAINT refresh_token_hash_pair CHECK (
        (access_token_hash IS NULL AND refresh_token_hash IS NULL AND access_expires_at IS NULL)
        OR (access_token_hash IS NOT NULL AND refresh_token_hash IS NOT NULL
            AND access_expires_at IS NOT NULL AND access_expires_at <= expires_at)
    );
CREATE UNIQUE INDEX refresh_token_access_hash_key ON refresh_token(access_token_hash);
CREATE UNIQUE INDEX refresh_token_refresh_hash_key ON refresh_token(refresh_token_hash);

CREATE TABLE login_request_window (
    email_key CHAR(64) PRIMARY KEY,
    window_started_at TIMESTAMPTZ NOT NULL,
    request_count INTEGER NOT NULL CHECK (request_count > 0)
);

CREATE INDEX login_request_window_started_at_idx ON login_request_window(window_started_at);
