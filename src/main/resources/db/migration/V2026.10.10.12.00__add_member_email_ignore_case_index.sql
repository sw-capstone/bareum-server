-- Keep writes blocked until the check and replacement index are committed.
LOCK TABLE member IN SHARE ROW EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (
        SELECT upper(email)
        FROM member
        WHERE status <> 'DELETED' OR withdrawn_at IS NULL
        GROUP BY upper(email)
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23505',
            MESSAGE = 'Resolve case-insensitive member email duplicates before migration.';
    END IF;
END $$;

-- Preserve the name used by the signup constraint-error mapping.
ALTER TABLE member DROP CONSTRAINT member_email_key;
CREATE UNIQUE INDEX member_email_key ON member (upper(email))
    WHERE status <> 'DELETED' OR withdrawn_at IS NULL;
