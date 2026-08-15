CREATE TABLE task_execution_slot (
    slot_number TINYINT UNSIGNED NOT NULL,
    task_id CHAR(36) NULL,
    PRIMARY KEY (slot_number),
    UNIQUE KEY uq_task_execution_slot_task (task_id),
    CONSTRAINT chk_task_execution_slot_number CHECK (slot_number BETWEEN 1 AND 5)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO task_execution_slot (slot_number, task_id)
VALUES (1, NULL), (2, NULL), (3, NULL), (4, NULL), (5, NULL);
