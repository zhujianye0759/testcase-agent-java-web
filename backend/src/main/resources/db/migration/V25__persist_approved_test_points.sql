-- [Req-ID]: REQ-TGV2-016
-- V25 is append-only: historical V2 tasks without reviewed points remain valid empty collections.
CREATE TABLE v2_approved_test_point (
    task_id CHAR(36) NOT NULL,
    test_point_key VARCHAR(128) NOT NULL,
    stable_sequence INT UNSIGNED NOT NULL,
    scope_version VARCHAR(128) NOT NULL,
    function_key VARCHAR(128) NOT NULL,
    test_point_type VARCHAR(32) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    review_status VARCHAR(32) NOT NULL,
    description_text TEXT NOT NULL,
    missing_information_json JSON NOT NULL,
    PRIMARY KEY (task_id, test_point_key),
    CONSTRAINT uq_v2_approved_test_point_sequence UNIQUE (task_id, stable_sequence),
    CONSTRAINT chk_v2_approved_test_point_sequence CHECK (stable_sequence > 0),
    CONSTRAINT chk_v2_approved_test_point_type CHECK (test_point_type IN (
        'NORMAL_BEHAVIOR', 'INPUT_VALIDATION', 'BOUNDARY_VALUE', 'PERMISSION', 'STATE_TRANSITION',
        'BUSINESS_EXCEPTION', 'DEPENDENCY_FAILURE')),
    CONSTRAINT chk_v2_approved_test_point_source CHECK (source_type = 'GENERAL_EXPERIENCE'),
    CONSTRAINT chk_v2_approved_test_point_status CHECK (review_status = 'PENDING_CONFIRMATION'),
    CONSTRAINT fk_v2_approved_test_point_function FOREIGN KEY (task_id, function_key)
        REFERENCES v2_approved_function (task_id, function_key),
    CONSTRAINT fk_v2_approved_test_point_task FOREIGN KEY (task_id)
        REFERENCES generation_task (id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
