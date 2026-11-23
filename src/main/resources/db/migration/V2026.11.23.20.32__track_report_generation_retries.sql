ALTER TABLE report_analysis_step
    ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_report_analysis_step_retry_count CHECK (
        retry_count = 0 OR (step_code = 'REPORT_GENERATION' AND retry_count = 1)
    );
