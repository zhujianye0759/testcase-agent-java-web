CREATE TABLE material_inventory_document (
    task_id CHAR(36) NOT NULL,
    document_id VARCHAR(255) NOT NULL,
    knowledge_id VARCHAR(255) NOT NULL,
    document_role VARCHAR(64) NOT NULL,
    total_units INT UNSIGNED NOT NULL,
    complete BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (task_id, document_id),
    CONSTRAINT chk_material_inventory_document_complete CHECK (complete = TRUE),
    CONSTRAINT fk_material_inventory_document_task FOREIGN KEY (task_id) REFERENCES generation_task (id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE material_inventory_unit (
    task_id CHAR(36) NOT NULL,
    document_id VARCHAR(255) NOT NULL,
    unit_id VARCHAR(255) NOT NULL,
    document_role VARCHAR(64) NOT NULL,
    chunk_index INT UNSIGNED NOT NULL,
    ordinal INT UNSIGNED NOT NULL,
    content MEDIUMTEXT NOT NULL,
    start_at BIGINT UNSIGNED NOT NULL,
    end_at BIGINT UNSIGNED NOT NULL,
    processing_status VARCHAR(24) NOT NULL DEFAULT 'RECEIVED',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (task_id, document_id, unit_id),
    CONSTRAINT chk_material_inventory_unit_ordinal CHECK (ordinal > 0),
    CONSTRAINT chk_material_inventory_unit_coordinates CHECK (end_at >= start_at),
    CONSTRAINT fk_material_inventory_unit_task FOREIGN KEY (task_id) REFERENCES generation_task (id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE material_audit_work (
    id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    document_id VARCHAR(255) NOT NULL,
    unit_id VARCHAR(255) NOT NULL,
    audit_pass INT UNSIGNED NOT NULL,
    audit_stage VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    lease_owner VARCHAR(128) NULL,
    lease_expires_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_material_audit_work_identity UNIQUE (task_id, document_id, unit_id, audit_pass, audit_stage),
    CONSTRAINT chk_material_audit_work_pass CHECK (audit_pass > 0),
    CONSTRAINT chk_material_audit_work_status CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT fk_material_audit_work_task FOREIGN KEY (task_id) REFERENCES generation_task (id),
    CONSTRAINT fk_material_audit_work_unit FOREIGN KEY (task_id, document_id, unit_id)
        REFERENCES material_inventory_unit (task_id, document_id, unit_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE material_audit_attempt (
    id CHAR(36) NOT NULL,
    work_id CHAR(36) NOT NULL,
    attempt_number INT UNSIGNED NOT NULL,
    status VARCHAR(24) NOT NULL,
    failure_summary VARCHAR(2048) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_material_audit_attempt_number UNIQUE (work_id, attempt_number),
    CONSTRAINT chk_material_audit_attempt_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT fk_material_audit_attempt_work FOREIGN KEY (work_id) REFERENCES material_audit_work (id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE feature_source_candidate (
    id CHAR(64) NOT NULL,
    task_id CHAR(36) NOT NULL,
    document_id VARCHAR(255) NOT NULL,
    unit_id VARCHAR(255) NOT NULL,
    source_ordinal INT UNSIGNED NOT NULL,
    model_sequence INT UNSIGNED NOT NULL,
    candidate_kind VARCHAR(32) NOT NULL,
    candidate_text TEXT NOT NULL,
    candidate_category VARCHAR(255) NOT NULL,
    evidence_text TEXT NOT NULL,
    audit_pass INT UNSIGNED NOT NULL,
    source_row_position INT UNSIGNED NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (task_id, id),
    CONSTRAINT uq_feature_source_candidate_occurrence UNIQUE (task_id, document_id, unit_id, audit_pass, source_row_position),
    CONSTRAINT chk_feature_source_candidate_coordinates CHECK (
        source_ordinal > 0 AND model_sequence > 0 AND audit_pass > 0 AND source_row_position > 0),
    CONSTRAINT fk_feature_source_candidate_task FOREIGN KEY (task_id) REFERENCES generation_task (id),
    CONSTRAINT fk_feature_source_candidate_unit FOREIGN KEY (task_id, document_id, unit_id)
        REFERENCES material_inventory_unit (task_id, document_id, unit_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE feature_review_conclusion (
    id CHAR(64) NOT NULL,
    task_id CHAR(36) NOT NULL,
    conclusion_sequence INT UNSIGNED NOT NULL,
    conclusion_type VARCHAR(64) NOT NULL,
    explanation TEXT NOT NULL,
    evidence_text TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (task_id, id),
    CONSTRAINT uq_feature_review_conclusion_sequence UNIQUE (task_id, conclusion_sequence),
    CONSTRAINT chk_feature_review_conclusion_sequence CHECK (conclusion_sequence > 0),
    CONSTRAINT fk_feature_review_conclusion_task FOREIGN KEY (task_id) REFERENCES generation_task (id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE feature_review_conclusion_candidate (
    task_id CHAR(36) NOT NULL,
    conclusion_id CHAR(64) NOT NULL,
    source_candidate_id CHAR(64) NOT NULL,
    PRIMARY KEY (task_id, conclusion_id, source_candidate_id),
    CONSTRAINT uq_feature_review_conclusion_candidate UNIQUE (task_id, source_candidate_id),
    CONSTRAINT fk_feature_review_conclusion_candidate_conclusion FOREIGN KEY (task_id, conclusion_id)
        REFERENCES feature_review_conclusion (task_id, id),
    CONSTRAINT fk_feature_review_conclusion_candidate_source FOREIGN KEY (task_id, source_candidate_id)
        REFERENCES feature_source_candidate (task_id, id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE material_audit_scan_outcome (
    work_id CHAR(36) NOT NULL,
    accepted_candidate_count INT UNSIGNED NOT NULL,
    duplicate_occurrence_count INT UNSIGNED NOT NULL,
    converged BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (work_id),
    CONSTRAINT fk_material_audit_scan_outcome_work FOREIGN KEY (work_id) REFERENCES material_audit_work (id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE material_audit_duplicate_occurrence (
    work_id CHAR(36) NOT NULL,
    occurrence_id CHAR(64) NOT NULL,
    model_sequence INT UNSIGNED NOT NULL,
    source_row_position INT UNSIGNED NOT NULL,
    candidate_text TEXT NOT NULL,
    candidate_category VARCHAR(255) NOT NULL,
    evidence_text TEXT NOT NULL,
    PRIMARY KEY (work_id, occurrence_id),
    CONSTRAINT chk_material_audit_duplicate_coordinates CHECK (model_sequence > 0 AND source_row_position > 0),
    CONSTRAINT fk_material_audit_duplicate_occurrence_work FOREIGN KEY (work_id) REFERENCES material_audit_work (id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE frozen_feature_target (
    id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    stable_feature_id VARCHAR(255) NOT NULL,
    stable_sequence INT UNSIGNED NOT NULL,
    feature_name VARCHAR(1024) NOT NULL,
    generation_eligible BOOLEAN NOT NULL,
    source_summary JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_frozen_feature_target_id UNIQUE (task_id, stable_feature_id),
    CONSTRAINT uq_frozen_feature_target_sequence UNIQUE (task_id, stable_sequence),
    CONSTRAINT chk_frozen_feature_target_sequence CHECK (stable_sequence > 0),
    CONSTRAINT fk_frozen_feature_target_task FOREIGN KEY (task_id) REFERENCES generation_task (id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
