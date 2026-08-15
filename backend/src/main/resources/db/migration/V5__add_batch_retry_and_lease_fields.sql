ALTER TABLE generation_task
    ADD COLUMN idempotency_key CHAR(64) NULL AFTER status,
    ADD UNIQUE KEY uq_generation_task_idempotency_key (idempotency_key);

ALTER TABLE generation_batch
    ADD COLUMN lease_owner VARCHAR(128) NULL AFTER status,
    ADD COLUMN lease_expires_at TIMESTAMP(6) NULL AFTER lease_owner;
