-- [Req-ID]: REQ-TGV2-001, REQ-TGV2-004, REQ-TGV2-005, REQ-TGV2-008, REQ-TGV2-010
-- V20 is additive: nullable task versions preserve every historical V1 snapshot and V14-V19 row unchanged.
ALTER TABLE generation_task
    ADD COLUMN workflow_version VARCHAR(16) NULL AFTER structured_coverage_status,
    ADD COLUMN input_version VARCHAR(16) NULL AFTER workflow_version,
    ADD COLUMN artifact_version VARCHAR(16) NULL AFTER input_version,
    ADD COLUMN approved_scope_version VARCHAR(128) NULL AFTER artifact_version;

CREATE TABLE v2_approved_function (
    task_id CHAR(36) NOT NULL,
    function_key VARCHAR(128) NOT NULL,
    stable_sequence INT UNSIGNED NOT NULL,
    scope_version VARCHAR(128) NOT NULL,
    name_text TEXT NOT NULL,
    path_text TEXT NOT NULL,
    description_text TEXT NOT NULL,
    PRIMARY KEY (task_id, function_key),
    CONSTRAINT uq_v2_approved_function_sequence UNIQUE (task_id, stable_sequence),
    CONSTRAINT chk_v2_approved_function_sequence CHECK (stable_sequence > 0),
    CONSTRAINT fk_v2_approved_function_task FOREIGN KEY (task_id) REFERENCES generation_task (id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE v2_requirement_fact (
    task_id CHAR(36) NOT NULL,
    fact_key VARCHAR(128) NOT NULL,
    first_work_item_id CHAR(36) NOT NULL,
    function_key VARCHAR(128) NOT NULL,
    fact_type VARCHAR(32) NOT NULL,
    statement_text TEXT NOT NULL,
    PRIMARY KEY (task_id, fact_key),
    CONSTRAINT chk_v2_requirement_fact_type CHECK (fact_type IN (
        'role', 'trigger_condition', 'input', 'business_rule', 'output', 'permission',
        'state_change', 'exception_handling', 'external_dependency')),
    CONSTRAINT fk_v2_requirement_fact_task FOREIGN KEY (task_id) REFERENCES generation_task (id),
    CONSTRAINT fk_v2_requirement_fact_work FOREIGN KEY (first_work_item_id)
        REFERENCES structured_generation_work_item (id),
    CONSTRAINT fk_v2_requirement_fact_function FOREIGN KEY (task_id, function_key)
        REFERENCES v2_approved_function (task_id, function_key)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE v2_requirement_fact_quote (
    task_id CHAR(36) NOT NULL,
    fact_key VARCHAR(128) NOT NULL,
    evidence_key VARCHAR(128) NOT NULL,
    quote_sha256 CHAR(64) NOT NULL,
    quote_text TEXT NOT NULL,
    PRIMARY KEY (task_id, fact_key, evidence_key, quote_sha256),
    CONSTRAINT uq_v2_fact_quote_identity UNIQUE (task_id, fact_key, evidence_key, quote_sha256),
    CONSTRAINT fk_v2_fact_quote_fact FOREIGN KEY (task_id, fact_key)
        REFERENCES v2_requirement_fact (task_id, fact_key) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE v2_testability_feedback (
    task_id CHAR(36) NOT NULL,
    feedback_key VARCHAR(128) NOT NULL,
    work_item_id CHAR(36) NOT NULL,
    function_key VARCHAR(128) NOT NULL,
    window_key VARCHAR(128) NOT NULL,
    observation_type VARCHAR(32) NOT NULL,
    description_text TEXT NOT NULL,
    affected_fact_types_json JSON NOT NULL,
    PRIMARY KEY (task_id, feedback_key),
    CONSTRAINT uq_v2_feedback_task_key UNIQUE (task_id, feedback_key),
    CONSTRAINT chk_v2_feedback_type CHECK (observation_type IN (
        'ambiguous', 'contradictory', 'unquantified', 'unobservable_result', 'placeholder_or_todo')),
    CONSTRAINT fk_v2_feedback_task FOREIGN KEY (task_id) REFERENCES generation_task (id),
    CONSTRAINT fk_v2_feedback_work FOREIGN KEY (work_item_id) REFERENCES structured_generation_work_item (id),
    CONSTRAINT fk_v2_feedback_function FOREIGN KEY (task_id, function_key)
        REFERENCES v2_approved_function (task_id, function_key)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE v2_testability_feedback_quote (
    task_id CHAR(36) NOT NULL,
    feedback_key VARCHAR(128) NOT NULL,
    evidence_key VARCHAR(128) NOT NULL,
    quote_sha256 CHAR(64) NOT NULL,
    quote_text TEXT NOT NULL,
    PRIMARY KEY (task_id, feedback_key, evidence_key, quote_sha256),
    CONSTRAINT fk_v2_feedback_quote_feedback FOREIGN KEY (task_id, feedback_key)
        REFERENCES v2_testability_feedback (task_id, feedback_key) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE v2_generation_outcome (
    work_item_id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    test_point_key VARCHAR(128) NOT NULL,
    function_key VARCHAR(128) NOT NULL,
    generation_outcome VARCHAR(32) NOT NULL,
    missing_information_json JSON NOT NULL,
    formal_coverage_satisfied BOOLEAN NOT NULL,
    PRIMARY KEY (work_item_id),
    CONSTRAINT uq_v2_outcome_task_point UNIQUE (task_id, test_point_key),
    CONSTRAINT chk_v2_generation_outcome CHECK (generation_outcome IN (
        'generated', 'pending_only', 'unable_to_generate')),
    CONSTRAINT fk_v2_outcome_work FOREIGN KEY (work_item_id)
        REFERENCES structured_generation_work_item (id) ON DELETE CASCADE,
    CONSTRAINT fk_v2_outcome_task FOREIGN KEY (task_id) REFERENCES generation_task (id),
    CONSTRAINT fk_v2_outcome_function FOREIGN KEY (task_id, function_key)
        REFERENCES v2_approved_function (task_id, function_key)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE v2_work_publication (
    work_item_id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    publication_type VARCHAR(32) NOT NULL,
    input_sha256 CHAR(64) NOT NULL,
    result_sha256 CHAR(64) NOT NULL,
    published_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (work_item_id),
    CONSTRAINT chk_v2_publication_type CHECK (publication_type IN ('requirement_facts', 'testcase_design')),
    CONSTRAINT fk_v2_publication_work FOREIGN KEY (work_item_id)
        REFERENCES structured_generation_work_item (id) ON DELETE CASCADE,
    CONSTRAINT fk_v2_publication_task FOREIGN KEY (task_id) REFERENCES generation_task (id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
