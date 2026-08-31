-- [Req-ID]: REQ-TGV2-012
-- Store only the validated public V2 result, never the KEE envelope, prompt, credentials, or model diagnostics.
-- This makes a retained completed sibling replayable during an explicit recovery decision.
ALTER TABLE v2_work_publication
    ADD COLUMN validated_result_replay_json JSON NULL AFTER result_sha256;
