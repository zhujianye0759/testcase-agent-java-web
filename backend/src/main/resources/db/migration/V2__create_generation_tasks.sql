CREATE TABLE generation_task (
    id CHAR(36) NOT NULL,
    task_mode VARCHAR(16) NOT NULL,
    status VARCHAR(24) NOT NULL,
    request_snapshot JSON NOT NULL,
    result_snapshot JSON NULL,
    artifact_id CHAR(36) NULL,
    artifact_sha256 CHAR(64) NULL,
    artifact_path VARCHAR(1024) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT chk_generation_task_mode CHECK (task_mode IN ('FEATURE', 'ALL'))
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE generation_batch (
    id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    feature_id VARCHAR(255) NOT NULL,
    status VARCHAR(24) NOT NULL,
    accepted_result JSON NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_generation_batch_task FOREIGN KEY (task_id) REFERENCES generation_task (id),
    CONSTRAINT uq_generation_batch_task_feature UNIQUE (task_id, feature_id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE generation_attempt (
    id CHAR(36) NOT NULL,
    batch_id CHAR(36) NOT NULL,
    attempt_number INT UNSIGNED NOT NULL,
    status VARCHAR(24) NOT NULL,
    failure_reason VARCHAR(2048) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_generation_attempt_batch FOREIGN KEY (batch_id) REFERENCES generation_batch (id),
    CONSTRAINT uq_generation_attempt_number UNIQUE (batch_id, attempt_number)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
