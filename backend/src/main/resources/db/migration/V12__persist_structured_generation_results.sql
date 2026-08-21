-- V10 and V11 are reserved by already-applied task recovery and isolated-skill evidence changes.
ALTER TABLE generation_task
    ADD COLUMN structured_processing_status VARCHAR(24) NULL AFTER status,
    ADD COLUMN structured_coverage_status VARCHAR(24) NULL AFTER structured_processing_status;

CREATE TABLE structured_generation_work_item (
    id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    identity_key CHAR(64) NOT NULL,
    skill_name VARCHAR(64) NOT NULL,
    operation_name VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    ordinal_start INT UNSIGNED NULL,
    ordinal_end INT UNSIGNED NULL,
    material_key VARCHAR(128) NULL,
    source_label TEXT NULL,
    allowed_evidence_keys_json JSON NULL,
    function_key VARCHAR(128) NULL,
    test_point_key VARCHAR(128) NULL,
    accepted_result_sha256 CHAR(64) NULL,
    coverage_status VARCHAR(24) NULL,
    lease_owner VARCHAR(128) NULL,
    lease_expires_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_structured_generation_work_identity UNIQUE (task_id, identity_key),
    CONSTRAINT chk_structured_generation_work_status CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT chk_structured_generation_work_ordinal_range CHECK (
        (ordinal_start IS NULL AND ordinal_end IS NULL) OR (ordinal_start > 0 AND ordinal_end >= ordinal_start)),
    CONSTRAINT chk_structured_generation_work_coverage CHECK (coverage_status IS NULL OR coverage_status IN ('SATISFIED', 'NOT_APPLICABLE')),
    CONSTRAINT fk_structured_generation_work_task FOREIGN KEY (task_id) REFERENCES generation_task (id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_structured_generation_work_claim
    ON structured_generation_work_item (task_id, status, created_at, id);

CREATE TABLE structured_generation_attempt (
    id CHAR(36) NOT NULL,
    work_item_id CHAR(36) NOT NULL,
    attempt_number INT UNSIGNED NOT NULL,
    status VARCHAR(24) NOT NULL,
    failure_type VARCHAR(64) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_structured_generation_attempt_number UNIQUE (work_item_id, attempt_number),
    CONSTRAINT chk_structured_generation_attempt_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT fk_structured_generation_attempt_work FOREIGN KEY (work_item_id) REFERENCES structured_generation_work_item (id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE structured_requirement_fact (
    work_item_id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    fact_key VARCHAR(128) NOT NULL,
    function_name TEXT NOT NULL,
    roles_json JSON NOT NULL,
    trigger_conditions_json JSON NOT NULL,
    inputs_json JSON NOT NULL,
    business_rules_json JSON NOT NULL,
    outputs_json JSON NOT NULL,
    permissions_json JSON NOT NULL,
    state_changes_json JSON NOT NULL,
    exception_handling_json JSON NOT NULL,
    external_dependencies_json JSON NOT NULL,
    PRIMARY KEY (work_item_id, fact_key),
    CONSTRAINT uq_structured_requirement_fact_task_key UNIQUE (task_id, fact_key),
    CONSTRAINT fk_structured_requirement_fact_work FOREIGN KEY (work_item_id) REFERENCES structured_generation_work_item (id) ON DELETE CASCADE,
    CONSTRAINT fk_structured_requirement_fact_task FOREIGN KEY (task_id) REFERENCES generation_task (id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE structured_review_finding (
    work_item_id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    finding_key VARCHAR(128) NOT NULL,
    issue_type VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    test_design_impact TEXT NOT NULL,
    current_project_recommendation TEXT NOT NULL,
    design_center_guideline_recommendation TEXT NOT NULL,
    handling_level VARCHAR(32) NOT NULL,
    PRIMARY KEY (work_item_id, finding_key),
    CONSTRAINT uq_structured_review_finding_task_key UNIQUE (task_id, finding_key),
    CONSTRAINT fk_structured_review_finding_work FOREIGN KEY (work_item_id) REFERENCES structured_generation_work_item (id) ON DELETE CASCADE,
    CONSTRAINT fk_structured_review_finding_task FOREIGN KEY (task_id) REFERENCES generation_task (id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE structured_feature_reconciliation (
    work_item_id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    reconciliation_key VARCHAR(128) NOT NULL,
    classification VARCHAR(32) NOT NULL,
    scope_recommendation TEXT NOT NULL,
    confirmation_status VARCHAR(32) NOT NULL,
    PRIMARY KEY (work_item_id, reconciliation_key),
    CONSTRAINT uq_structured_reconciliation_task_key UNIQUE (task_id, reconciliation_key),
    CONSTRAINT fk_structured_feature_reconciliation_work FOREIGN KEY (work_item_id) REFERENCES structured_generation_work_item (id) ON DELETE CASCADE,
    CONSTRAINT fk_structured_feature_reconciliation_task FOREIGN KEY (task_id) REFERENCES generation_task (id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE structured_function_list_item (
    work_item_id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    item_key VARCHAR(128) NOT NULL,
    path_text TEXT NOT NULL,
    description TEXT NOT NULL,
    PRIMARY KEY (work_item_id, item_key),
    CONSTRAINT uq_structured_function_list_task_item UNIQUE (task_id, item_key),
    CONSTRAINT fk_structured_function_list_item_work FOREIGN KEY (work_item_id) REFERENCES structured_generation_work_item (id) ON DELETE CASCADE,
    CONSTRAINT fk_structured_function_list_item_task FOREIGN KEY (task_id) REFERENCES generation_task (id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE structured_test_point (
    work_item_id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    test_point_key VARCHAR(128) NOT NULL,
    function_key VARCHAR(128) NOT NULL,
    function_name TEXT NOT NULL,
    test_point_type VARCHAR(32) NOT NULL,
    basis VARCHAR(32) NOT NULL,
    description TEXT NOT NULL,
    missing_information_json JSON NOT NULL,
    formal_coverage_satisfied BOOLEAN NOT NULL,
    PRIMARY KEY (work_item_id, test_point_key),
    CONSTRAINT uq_structured_test_point_task_key UNIQUE (task_id, test_point_key),
    CONSTRAINT fk_structured_test_point_work FOREIGN KEY (work_item_id) REFERENCES structured_generation_work_item (id) ON DELETE CASCADE,
    CONSTRAINT fk_structured_test_point_task FOREIGN KEY (task_id) REFERENCES generation_task (id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE structured_test_case (
    work_item_id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    case_key VARCHAR(128) NOT NULL,
    title TEXT NOT NULL,
    preconditions_json JSON NOT NULL,
    case_status VARCHAR(32) NOT NULL,
    missing_information_json JSON NOT NULL,
    PRIMARY KEY (work_item_id, case_key),
    CONSTRAINT uq_structured_test_case_task_key UNIQUE (task_id, case_key),
    CONSTRAINT fk_structured_test_case_work FOREIGN KEY (work_item_id) REFERENCES structured_generation_work_item (id) ON DELETE CASCADE,
    CONSTRAINT fk_structured_test_case_task FOREIGN KEY (task_id) REFERENCES generation_task (id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE structured_test_case_step (
    work_item_id CHAR(36) NOT NULL,
    case_key VARCHAR(128) NOT NULL,
    step_no INT UNSIGNED NOT NULL,
    action_text TEXT NOT NULL,
    expected_text TEXT NOT NULL,
    PRIMARY KEY (work_item_id, case_key, step_no),
    CONSTRAINT chk_structured_test_case_step_no CHECK (step_no > 0),
    CONSTRAINT fk_structured_test_case_step_case FOREIGN KEY (work_item_id, case_key)
        REFERENCES structured_test_case (work_item_id, case_key) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE structured_reference_binding (
    work_item_id CHAR(36) NOT NULL,
    subject_key VARCHAR(128) NOT NULL,
    subject_type VARCHAR(32) NOT NULL,
    reference_type VARCHAR(32) NOT NULL,
    reference_key VARCHAR(128) NOT NULL,
    PRIMARY KEY (work_item_id, subject_key, subject_type, reference_type, reference_key),
    CONSTRAINT chk_structured_reference_binding_subject_type CHECK (subject_type IN ('REQUIREMENT_FACT', 'REVIEW_FINDING', 'RECONCILIATION', 'TEST_POINT', 'TEST_CASE', 'FUNCTION_LIST_ITEM')),
    CONSTRAINT chk_structured_reference_binding_type CHECK (reference_type IN ('EVIDENCE', 'REQUIREMENT_FACT', 'FUNCTION_LIST_ITEM')),
    CONSTRAINT fk_structured_reference_binding_work FOREIGN KEY (work_item_id) REFERENCES structured_generation_work_item (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_structured_reference_binding_lookup
    ON structured_reference_binding (work_item_id, reference_type, reference_key);
