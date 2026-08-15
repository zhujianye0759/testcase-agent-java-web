ALTER TABLE generation_task
    ADD COLUMN cancellation_requested_at TIMESTAMP(6) NULL AFTER artifact_path;
