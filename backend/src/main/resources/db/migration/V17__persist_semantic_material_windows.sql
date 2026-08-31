-- [Req-ID]: REQ-FTG-013
-- A material work row owns target evidence; adjacent context and split lineage are durable but never evidence.
ALTER TABLE structured_generation_work_item
    ADD COLUMN material_document_id VARCHAR(255) NULL AFTER material_key,
    ADD COLUMN context_evidence_keys_json JSON NULL AFTER allowed_evidence_keys_json,
    ADD COLUMN parent_work_item_id CHAR(36) NULL AFTER context_evidence_keys_json,
    ADD COLUMN split_depth INT UNSIGNED NOT NULL DEFAULT 0 AFTER parent_work_item_id;

CREATE INDEX idx_structured_work_parent
    ON structured_generation_work_item (parent_work_item_id, ordinal_start, id);

ALTER TABLE structured_generation_work_item
    ADD CONSTRAINT fk_structured_work_parent
        FOREIGN KEY (parent_work_item_id) REFERENCES structured_generation_work_item (id);
