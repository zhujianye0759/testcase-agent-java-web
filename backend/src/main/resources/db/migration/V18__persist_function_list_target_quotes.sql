-- [Req-ID]: REQ-FTG-014
-- New extraction results retain their target-owned source quote; historical accepted rows remain readable as NULL.
ALTER TABLE structured_function_list_item
    ADD COLUMN target_quotes_json JSON NULL AFTER description,
    ADD CONSTRAINT chk_structured_function_target_quotes
        CHECK (target_quotes_json IS NULL OR JSON_TYPE(target_quotes_json) = 'ARRAY');
