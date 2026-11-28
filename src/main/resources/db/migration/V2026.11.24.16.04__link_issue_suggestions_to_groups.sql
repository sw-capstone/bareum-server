ALTER TABLE issue_suggestion ADD COLUMN issue_group_id BIGINT;

UPDATE issue_suggestion suggestion
SET issue_group_id = issue.issue_group_id
FROM issue
WHERE suggestion.issue_id = issue.id;

-- 한 그룹에 여러 기존 수정안이 있으면 이관을 중단해 원본을 보존한다.
ALTER TABLE issue_suggestion
    ALTER COLUMN issue_group_id SET NOT NULL,
    ADD CONSTRAINT fk_issue_suggestion_group FOREIGN KEY (issue_group_id) REFERENCES issue_group (id),
    ADD CONSTRAINT uk_issue_suggestion_group UNIQUE (issue_group_id),
    DROP COLUMN issue_id;

ALTER TABLE issue DROP COLUMN status;
