CREATE TABLE application_schema_metadata (
    id TINYINT UNSIGNED NOT NULL,
    schema_owner VARCHAR(100) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT chk_application_schema_metadata_singleton CHECK (id = 1)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO application_schema_metadata (id, schema_owner)
VALUES (1, 'testcase-agent-java-web');
