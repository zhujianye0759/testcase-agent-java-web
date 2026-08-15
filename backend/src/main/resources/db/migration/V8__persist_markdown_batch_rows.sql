ALTER TABLE generation_batch
    ADD COLUMN batch_sequence INT UNSIGNED NULL AFTER feature_id,
    ADD COLUMN raw_completed_markdown MEDIUMTEXT NULL AFTER accepted_result;

WITH ranked_batches AS (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY task_id ORDER BY created_at, id) AS batch_sequence
    FROM generation_batch
)
UPDATE generation_batch batch
JOIN ranked_batches ranked ON ranked.id = batch.id
SET batch.batch_sequence = ranked.batch_sequence;

ALTER TABLE generation_batch
    MODIFY COLUMN batch_sequence INT UNSIGNED NOT NULL,
    ADD CONSTRAINT chk_generation_batch_sequence CHECK (batch_sequence > 0),
    ADD CONSTRAINT uq_generation_batch_task_sequence UNIQUE (task_id, batch_sequence);

CREATE TABLE generation_audit_row (
    batch_id CHAR(36) NOT NULL,
    row_sequence INT UNSIGNED NOT NULL,
    subject_or_feature TEXT NOT NULL,
    issue_category TEXT NOT NULL,
    evidence_comparison TEXT NOT NULL,
    PRIMARY KEY (batch_id, row_sequence),
    CONSTRAINT chk_generation_audit_row_sequence CHECK (row_sequence > 0),
    CONSTRAINT fk_generation_audit_row_batch FOREIGN KEY (batch_id) REFERENCES generation_batch (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE generation_test_case_row (
    batch_id CHAR(36) NOT NULL,
    row_sequence INT UNSIGNED NOT NULL,
    case_name TEXT NOT NULL,
    feature_module TEXT NOT NULL,
    preconditions TEXT NOT NULL,
    execution_steps TEXT NOT NULL,
    expected_result TEXT NOT NULL,
    requirement_content TEXT NOT NULL,
    PRIMARY KEY (batch_id, row_sequence),
    CONSTRAINT chk_generation_test_case_row_sequence CHECK (row_sequence > 0),
    CONSTRAINT fk_generation_test_case_row_batch FOREIGN KEY (batch_id) REFERENCES generation_batch (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
