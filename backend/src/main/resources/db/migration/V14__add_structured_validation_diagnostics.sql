ALTER TABLE generation_task
    ADD COLUMN validation_error_code VARCHAR(64) NULL,
    ADD COLUMN validation_error_path VARCHAR(512) NULL,
    ADD COLUMN validation_error_message VARCHAR(255) NULL,
    ADD CONSTRAINT chk_generation_task_validation_error CHECK (
        (validation_error_code IS NULL AND validation_error_path IS NULL AND validation_error_message IS NULL)
        OR
        (validation_error_code IS NOT NULL AND validation_error_path IS NOT NULL AND validation_error_message IS NOT NULL));

ALTER TABLE structured_generation_work_item
    ADD COLUMN validation_error_code VARCHAR(64) NULL,
    ADD COLUMN validation_error_path VARCHAR(512) NULL,
    ADD COLUMN validation_error_message VARCHAR(255) NULL,
    ADD CONSTRAINT chk_structured_work_validation_error CHECK (
        (validation_error_code IS NULL AND validation_error_path IS NULL AND validation_error_message IS NULL)
        OR
        (validation_error_code IS NOT NULL AND validation_error_path IS NOT NULL AND validation_error_message IS NOT NULL));

ALTER TABLE structured_generation_attempt
    ADD COLUMN validation_error_code VARCHAR(64) NULL,
    ADD COLUMN validation_error_path VARCHAR(512) NULL,
    ADD COLUMN validation_error_message VARCHAR(255) NULL,
    ADD CONSTRAINT chk_structured_attempt_validation_error CHECK (
        (validation_error_code IS NULL AND validation_error_path IS NULL AND validation_error_message IS NULL)
        OR
        (validation_error_code IS NOT NULL AND validation_error_path IS NOT NULL AND validation_error_message IS NOT NULL));
