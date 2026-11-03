-- Revoked families were copied by V2026.10.30.21.48 before removing the legacy storage.
-- Do not use CASCADE: unexpected dependents must block the migration.
DROP TABLE login_access_token;

ALTER TABLE refresh_token
    DROP COLUMN access_token_hash,
    DROP COLUMN access_expires_at;
