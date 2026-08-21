ALTER TABLE structured_review_finding
    ADD COLUMN root_cause_kind VARCHAR(64) NULL AFTER finding_key,
    ADD COLUMN affected_unit_keys_json JSON NULL AFTER handling_level,
    ADD COLUMN affected_scope_summary TEXT NULL AFTER affected_unit_keys_json,
    ADD COLUMN bad_source_evidence_key VARCHAR(128) NULL AFTER affected_scope_summary,
    ADD COLUMN bad_source_quote TEXT NULL AFTER bad_source_evidence_key,
    ADD COLUMN proposed_good_status VARCHAR(32) NULL AFTER bad_source_quote,
    ADD COLUMN proposed_good_text TEXT NULL AFTER proposed_good_status,
    ADD CONSTRAINT chk_structured_review_root_cause CHECK (
        root_cause_kind IS NULL OR root_cause_kind IN (
            'MISSING_DOCUMENT_TRACEABILITY', 'MISSING_FUNCTION_SCOPE', 'MISSING_ROLE_PERMISSION_MATRIX',
            'MISSING_PROCESS_OR_STATE', 'MISSING_INPUT_OR_DATA_DICTIONARY', 'MISSING_BUSINESS_RULE',
            'MISSING_OUTPUT', 'MISSING_EXCEPTION_HANDLING', 'MISSING_EXTERNAL_DEPENDENCY',
            'MISSING_SECURITY_OR_AUDIT', 'MISSING_ENVIRONMENT_OR_CONFIGURATION',
            'CONFLICTING_REQUIREMENT', 'AMBIGUOUS_REQUIREMENT')),
    ADD CONSTRAINT chk_structured_review_proposed_status CHECK (
        proposed_good_status IS NULL OR proposed_good_status = 'PENDING_CONFIRMATION');

CREATE UNIQUE INDEX uq_structured_review_finding_task_root_cause
    ON structured_review_finding (task_id, root_cause_kind);

ALTER TABLE structured_test_case
    ADD COLUMN name_text TEXT NULL AFTER case_key,
    ADD COLUMN priority VARCHAR(16) NULL AFTER title,
    ADD COLUMN hardware_configuration_json JSON NULL AFTER preconditions_json,
    ADD COLUMN software_configuration_json JSON NULL AFTER hardware_configuration_json,
    ADD COLUMN test_configuration_json JSON NULL AFTER software_configuration_json,
    ADD COLUMN parameter_configuration_json JSON NULL AFTER test_configuration_json,
    ADD COLUMN inputs_json JSON NULL AFTER parameter_configuration_json,
    ADD COLUMN expected_results_json JSON NULL AFTER inputs_json,
    ADD COLUMN evaluation_criteria TEXT NULL AFTER expected_results_json,
    ADD COLUMN result_evaluation_criteria TEXT NULL AFTER evaluation_criteria,
    ADD COLUMN termination_conditions_json JSON NULL AFTER result_evaluation_criteria,
    ADD COLUMN result_collection TEXT NULL AFTER termination_conditions_json,
    ADD COLUMN author_name TEXT NULL AFTER result_collection,
    ADD COLUMN author_date VARCHAR(64) NULL AFTER author_name,
    ADD CONSTRAINT chk_structured_test_case_priority CHECK (
        priority IS NULL OR priority IN ('HIGH', 'MEDIUM', 'LOW'));

ALTER TABLE structured_test_case_step
    ADD COLUMN evaluation_criteria TEXT NULL AFTER expected_text,
    ADD COLUMN termination_or_error TEXT NULL AFTER evaluation_criteria,
    ADD COLUMN result_collection TEXT NULL AFTER termination_or_error;
