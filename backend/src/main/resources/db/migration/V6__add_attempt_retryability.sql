ALTER TABLE generation_attempt
    ADD COLUMN retryable BOOLEAN NOT NULL DEFAULT FALSE AFTER failure_reason;
