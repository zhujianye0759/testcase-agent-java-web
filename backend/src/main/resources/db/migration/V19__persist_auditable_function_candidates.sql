CREATE TABLE structured_function_source_outcome (
    work_item_id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    unit_key VARCHAR(128) NOT NULL,
    source_ordinal INT UNSIGNED NOT NULL,
    kee_disposition VARCHAR(32) NOT NULL,
    java_final_decision VARCHAR(32) NOT NULL,
    reason_code VARCHAR(128) NOT NULL,
    PRIMARY KEY (work_item_id, unit_key),
    CONSTRAINT uq_structured_function_source_task_unit UNIQUE (task_id, unit_key),
    CONSTRAINT chk_structured_function_source_ordinal CHECK (source_ordinal > 0),
    CONSTRAINT chk_structured_function_source_disposition CHECK (
        kee_disposition IN ('LINKED', 'NO_FUNCTION', 'UNRESOLVED')),
    CONSTRAINT chk_structured_function_source_decision CHECK (
        java_final_decision IN ('ACCEPTED', 'PENDING_CONFIRMATION', 'REJECTED')),
    CONSTRAINT fk_structured_function_source_work FOREIGN KEY (work_item_id)
        REFERENCES structured_generation_work_item (id) ON DELETE CASCADE,
    CONSTRAINT fk_structured_function_source_task FOREIGN KEY (task_id)
        REFERENCES generation_task (id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE structured_function_candidate (
    work_item_id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    candidate_ref CHAR(64) NOT NULL,
    path_text TEXT NOT NULL,
    description TEXT NOT NULL,
    target_quote TEXT NOT NULL,
    recommended_status VARCHAR(32) NOT NULL,
    java_final_decision VARCHAR(32) NOT NULL,
    reason_code VARCHAR(128) NOT NULL,
    missing_information_json JSON NOT NULL,
    function_item_key VARCHAR(128) NULL,
    PRIMARY KEY (work_item_id, candidate_ref),
    CONSTRAINT uq_structured_function_candidate_task_ref UNIQUE (task_id, candidate_ref),
    CONSTRAINT chk_structured_function_candidate_status CHECK (
        recommended_status IN ('ACCEPTED', 'PENDING_CONFIRMATION')),
    CONSTRAINT chk_structured_function_candidate_decision CHECK (
        java_final_decision IN ('ACCEPTED', 'PENDING_CONFIRMATION', 'REJECTED')),
    CONSTRAINT chk_structured_function_candidate_missing CHECK (
        JSON_TYPE(missing_information_json) = 'ARRAY'),
    CONSTRAINT fk_structured_function_candidate_work FOREIGN KEY (work_item_id)
        REFERENCES structured_generation_work_item (id) ON DELETE CASCADE,
    CONSTRAINT fk_structured_function_candidate_task FOREIGN KEY (task_id)
        REFERENCES generation_task (id),
    CONSTRAINT fk_structured_function_candidate_item FOREIGN KEY (task_id, function_item_key)
        REFERENCES structured_function_list_item (task_id, item_key)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE structured_function_outcome_candidate (
    work_item_id CHAR(36) NOT NULL,
    unit_key VARCHAR(128) NOT NULL,
    candidate_ref CHAR(64) NOT NULL,
    PRIMARY KEY (work_item_id, unit_key, candidate_ref),
    CONSTRAINT fk_structured_function_link_source FOREIGN KEY (work_item_id, unit_key)
        REFERENCES structured_function_source_outcome (work_item_id, unit_key) ON DELETE CASCADE,
    CONSTRAINT fk_structured_function_link_candidate FOREIGN KEY (work_item_id, candidate_ref)
        REFERENCES structured_function_candidate (work_item_id, candidate_ref) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
