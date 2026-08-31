-- [Req-ID]: REQ-TGV2-013
-- Artifact ids and paths are identities; the content hash may legitimately repeat for identical workbooks.
ALTER TABLE generation_task
    ADD COLUMN artifact_path_sha256 BINARY(32)
        GENERATED ALWAYS AS (IF(artifact_path IS NULL, NULL, UNHEX(SHA2(artifact_path, 256)))) STORED,
    ADD CONSTRAINT uq_generation_task_artifact_id UNIQUE (artifact_id),
    ADD CONSTRAINT uq_generation_task_artifact_path_sha256 UNIQUE (artifact_path_sha256);
