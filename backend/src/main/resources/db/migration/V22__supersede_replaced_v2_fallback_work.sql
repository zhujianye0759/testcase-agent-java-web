-- [Req-ID]: REQ-TGV2-011
-- SUPERSEDED preserves an accepted fallback result and its attempt audit while excluding it from the current V2
-- projection after an explicit, transactionally verified fact-recovery action.
ALTER TABLE structured_generation_work_item
    DROP CHECK chk_structured_generation_work_status;

ALTER TABLE structured_generation_work_item
    ADD CONSTRAINT chk_structured_generation_work_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'SPLIT', 'SUPERSEDED'));
