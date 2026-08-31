-- [Req-ID]: REQ-TGV2-003
-- V1 wrote only complete document rows. V2 must durably stage bounded pages before atomically opening the
-- completion gate; the NOT NULL boolean remains the authoritative two-state constraint.
ALTER TABLE material_inventory_document
    DROP CHECK chk_material_inventory_document_complete;
