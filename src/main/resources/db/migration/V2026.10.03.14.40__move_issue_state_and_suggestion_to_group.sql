DO $$
BEGIN
    IF EXISTS (
        SELECT issue_group_id FROM issue
        GROUP BY issue_group_id HAVING COUNT(DISTINCT status) > 1
    ) THEN
        RAISE EXCEPTION 'Resolve mixed issue statuses within each group before migrating';
    END IF;
    IF EXISTS (
        SELECT i.issue_group_id FROM issue_suggestion s
        JOIN issue i ON i.id = s.issue_id
        GROUP BY i.issue_group_id HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'Resolve multiple suggestions within each group before migrating';
    END IF;
END $$;

ALTER TABLE issue_group
    ADD COLUMN status VARCHAR(30) NOT NULL DEFAULT 'UNPROCESSED';

UPDATE issue_group g
SET status = i.status
FROM issue i
WHERE i.issue_group_id = g.id;

ALTER TABLE issue_suggestion ADD COLUMN issue_group_id BIGINT;

UPDATE issue_suggestion s
SET issue_group_id = i.issue_group_id
FROM issue i
WHERE i.id = s.issue_id;

ALTER TABLE issue_suggestion
    ALTER COLUMN issue_group_id SET NOT NULL,
    ADD FOREIGN KEY (issue_group_id) REFERENCES issue_group(id),
    ADD UNIQUE (issue_group_id),
    DROP COLUMN issue_id;

ALTER TABLE issue DROP COLUMN status;
