ALTER TABLE report_analysis ADD COLUMN deleted_at TIMESTAMPTZ;

UPDATE report_analysis analysis
SET deleted_at = result.deleted_at
FROM analysis_result_report result
WHERE analysis.id = result.report_analysis_id AND result.deleted_at IS NOT NULL;

ALTER TABLE report_analysis
    ADD CONSTRAINT ck_report_analysis_deleted_when_finished
        CHECK (deleted_at IS NULL OR status NOT IN ('PENDING', 'PROCESSING'));

DROP INDEX uk_report_analysis_success;
CREATE UNIQUE INDEX uk_report_analysis_success ON report_analysis (content_id)
    WHERE status = 'SUCCEEDED' AND deleted_at IS NULL;

ALTER TABLE analysis_result_report DROP COLUMN deleted_at;
