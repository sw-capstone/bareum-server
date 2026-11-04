ALTER TABLE report_intake_request ADD COLUMN processing_deadline_at TIMESTAMPTZ;
UPDATE report_intake_request SET processing_deadline_at = requested_at + INTERVAL '340 seconds';
ALTER TABLE report_intake_request
    ALTER COLUMN processing_deadline_at SET NOT NULL,
    ADD CONSTRAINT ck_report_intake_processing_deadline CHECK (processing_deadline_at > requested_at);

CREATE INDEX idx_report_intake_processing_deadline ON report_intake_request (processing_deadline_at, id)
    WHERE status = 'PROCESSING';
