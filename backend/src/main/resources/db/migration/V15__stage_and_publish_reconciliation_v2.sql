ALTER TABLE structured_feature_reconciliation
    ADD COLUMN protocol_version VARCHAR(8) NULL AFTER reconciliation_key,
    ADD COLUMN run_key VARCHAR(128) NULL AFTER protocol_version,
    ADD COLUMN owner_source_type VARCHAR(32) NULL AFTER run_key,
    ADD COLUMN owner_source_key VARCHAR(128) NULL AFTER owner_source_type,
    ADD CONSTRAINT chk_structured_reconciliation_protocol CHECK (
        (protocol_version IS NULL AND run_key IS NULL AND owner_source_type IS NULL AND owner_source_key IS NULL)
        OR
        (protocol_version = '2' AND run_key IS NOT NULL
            AND owner_source_type IN ('function_list_item', 'requirement_fact')
            AND owner_source_key IS NOT NULL));

CREATE TABLE structured_reconciliation_run (
    work_item_id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    run_key VARCHAR(128) NOT NULL,
    catalog_sha256 CHAR(64) NOT NULL,
    function_item_count INT UNSIGNED NOT NULL,
    requirement_fact_count INT UNSIGNED NOT NULL,
    catalog_source_refs_json JSON NOT NULL,
    initial_page_keys_json JSON NOT NULL,
    status VARCHAR(24) NOT NULL,
    accepted_result_sha256 CHAR(64) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (work_item_id),
    CONSTRAINT uq_structured_reconciliation_run_task_key UNIQUE (task_id, run_key),
    CONSTRAINT chk_structured_reconciliation_run_status CHECK (status IN ('STAGING', 'PUBLISHED')),
    CONSTRAINT fk_structured_reconciliation_run_work FOREIGN KEY (work_item_id)
        REFERENCES structured_generation_work_item (id) ON DELETE CASCADE,
    CONSTRAINT fk_structured_reconciliation_run_task FOREIGN KEY (task_id)
        REFERENCES generation_task (id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE structured_reconciliation_page_stage (
    work_item_id CHAR(36) NOT NULL,
    page_key CHAR(64) NOT NULL,
    run_key VARCHAR(128) NOT NULL,
    catalog_sha256 CHAR(64) NOT NULL,
    parent_page_key CHAR(64) NULL,
    status VARCHAR(24) NOT NULL,
    first_source_type VARCHAR(32) NOT NULL,
    first_source_key VARCHAR(128) NOT NULL,
    owner_source_refs_json JSON NOT NULL,
    completed_owner_source_refs_json JSON NULL,
    result_sha256 CHAR(64) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at TIMESTAMP(6) NULL,
    PRIMARY KEY (work_item_id, page_key),
    CONSTRAINT chk_structured_reconciliation_page_status CHECK (status IN ('PLANNED', 'COMPLETED', 'SPLIT')),
    CONSTRAINT chk_structured_reconciliation_page_completion CHECK (
        (status = 'COMPLETED' AND completed_owner_source_refs_json IS NOT NULL AND result_sha256 IS NOT NULL AND completed_at IS NOT NULL)
        OR
        (status IN ('PLANNED', 'SPLIT') AND completed_owner_source_refs_json IS NULL AND result_sha256 IS NULL AND completed_at IS NULL)),
    CONSTRAINT fk_structured_reconciliation_page_work FOREIGN KEY (work_item_id)
        REFERENCES structured_generation_work_item (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_structured_reconciliation_page_resume
    ON structured_reconciliation_page_stage (work_item_id, status, first_source_type, first_source_key);

CREATE TABLE structured_reconciliation_relation_stage (
    work_item_id CHAR(36) NOT NULL,
    page_key CHAR(64) NOT NULL,
    reconciliation_key CHAR(64) NOT NULL,
    owner_source_type VARCHAR(32) NOT NULL,
    owner_source_key VARCHAR(128) NOT NULL,
    classification VARCHAR(32) NOT NULL,
    scope_recommendation TEXT NOT NULL,
    confirmation_status VARCHAR(32) NOT NULL,
    PRIMARY KEY (work_item_id, reconciliation_key),
    INDEX ix_structured_reconciliation_relation_page (work_item_id, page_key),
    CONSTRAINT fk_structured_reconciliation_relation_page FOREIGN KEY (work_item_id, page_key)
        REFERENCES structured_reconciliation_page_stage (work_item_id, page_key) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE structured_reconciliation_relation_stage_binding (
    work_item_id CHAR(36) NOT NULL,
    reconciliation_key CHAR(64) NOT NULL,
    reference_type VARCHAR(32) NOT NULL,
    reference_key VARCHAR(128) NOT NULL,
    PRIMARY KEY (work_item_id, reconciliation_key, reference_type, reference_key),
    CONSTRAINT chk_structured_reconciliation_stage_binding_type CHECK (
        reference_type IN ('FUNCTION_LIST_ITEM', 'REQUIREMENT_FACT', 'EVIDENCE')),
    CONSTRAINT fk_structured_reconciliation_stage_binding_relation FOREIGN KEY (work_item_id, reconciliation_key)
        REFERENCES structured_reconciliation_relation_stage (work_item_id, reconciliation_key) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE structured_reconciliation_source_terminal (
    task_id CHAR(36) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_key VARCHAR(128) NOT NULL,
    work_item_id CHAR(36) NOT NULL,
    run_key VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (task_id, source_type, source_key),
    CONSTRAINT chk_structured_reconciliation_terminal_type CHECK (
        source_type IN ('function_list_item', 'requirement_fact')),
    CONSTRAINT fk_structured_reconciliation_terminal_task FOREIGN KEY (task_id)
        REFERENCES generation_task (id),
    CONSTRAINT fk_structured_reconciliation_terminal_work FOREIGN KEY (work_item_id)
        REFERENCES structured_generation_work_item (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX ix_structured_reconciliation_terminal_work
    ON structured_reconciliation_source_terminal (work_item_id, run_key);
