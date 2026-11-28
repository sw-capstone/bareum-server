-- 이전 모델에서 리포트 자체는 완료된 결과 외에 생성 시도도 보관할 수 있었다.
-- 새 모델로 확정적으로 옮길 수 없는 행이 있으면 기존 데이터를 변경하지 않고 중단한다.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM report_result r
        JOIN report_analysis a ON a.id = r.report_analysis_id
        WHERE r.status <> 'SUCCEEDED'
            OR r.completed_at IS NULL
            OR a.status <> 'SUCCEEDED'
            OR r.content_id <> a.content_id
            OR COALESCE(a.total_score, r.final_score) IS NULL
            OR (a.total_score IS NOT NULL AND r.final_score IS NOT NULL AND a.total_score <> r.final_score)
    ) THEN
        RAISE EXCEPTION 'Existing report results must be reconciled with their analyses before migration';
    END IF;
END $$;

ALTER TABLE report_result RENAME TO analysis_result_report;
ALTER TABLE analysis_result_report
    RENAME CONSTRAINT report_result_pkey TO analysis_result_report_pkey;
ALTER TABLE analysis_result_report
    RENAME CONSTRAINT report_result_report_analysis_id_fkey TO fk_analysis_result_report_analysis;
ALTER SEQUENCE report_result_id_seq RENAME TO analysis_result_report_id_seq;

ALTER TABLE analysis_result_report
    ADD CONSTRAINT uk_analysis_result_report_analysis UNIQUE (report_analysis_id);

UPDATE report_analysis a
SET total_score = r.final_score
FROM analysis_result_report r
WHERE r.report_analysis_id = a.id AND a.total_score IS NULL;

ALTER TABLE analysis_result_report RENAME COLUMN completed_at TO generated_at;
ALTER TABLE analysis_result_report
    ALTER COLUMN generated_at SET NOT NULL,
    ADD COLUMN summary TEXT,
    DROP COLUMN content_id,
    DROP COLUMN final_score,
    DROP COLUMN status,
    DROP COLUMN failure_code;
