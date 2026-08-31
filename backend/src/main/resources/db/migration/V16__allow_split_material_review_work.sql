-- [Req-ID]: REQ-FTG-010
-- SPLIT is a durable parent marker: it has no accepted hash and is excluded from leaf progress.
ALTER TABLE structured_generation_work_item
    DROP CHECK chk_structured_generation_work_status;

ALTER TABLE structured_generation_work_item
    ADD CONSTRAINT chk_structured_generation_work_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'SPLIT'));
