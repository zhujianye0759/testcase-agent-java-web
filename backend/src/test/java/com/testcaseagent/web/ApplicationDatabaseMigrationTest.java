package com.testcaseagent.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.testcaseagent.featureaudit.RequirementMaterialTraversalService;
import com.testcaseagent.task.GenerationWorkflow;
import com.testcaseagent.task.GenerationTaskRepository;
import com.testcaseagent.task.StructuredAllGenerationCoordinator;
import com.testcaseagent.structuredgeneration.StructuredCoverageStatus;
import com.testcaseagent.structuredgeneration.StructuredGenerationAcceptanceStore;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the application-owned MySQL schema boundary.
 *
 * [Test-Ref]: ApplicationDatabaseMigrationTest
 * [Req-ID]: REQ-TSK-006, REQ-EXP-005
 */
@Testcontainers
@SpringBootTest
class ApplicationDatabaseMigrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("testcase_agent")
            .withUsername("testcase_agent")
            .withPassword("test-only-password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("app.artifacts.root", () -> "./var/test-artifacts");
        registry.add("app.knowledge-agent.enabled", () -> "true");
        registry.add("app.knowledge-agent.api-base-url", () -> "http://127.0.0.1:1");
        registry.add("app.knowledge-agent.api-key", () -> "test-only-api-key");
    }

    @Test
    void appliesTheBaselineToAnIndependentMySql8Database(
            @Autowired DataSource dataSource,
            @Autowired JdbcTemplate jdbcTemplate,
            @Autowired Environment environment,
            @Autowired RequirementMaterialTraversalService materialTraversalService) throws SQLException {
        var datasourceUrl = environment.getRequiredProperty("spring.datasource.url");

        try (var connection = dataSource.getConnection()) {
            var metadata = connection.getMetaData();
            assertThat(metadata.getDatabaseProductName()).isEqualTo("MySQL");
            assertThat(metadata.getDatabaseMajorVersion()).isEqualTo(8);
        }
        assertThat(datasourceUrl).startsWith("jdbc:mysql:");
        assertThat(datasourceUrl).doesNotContainIgnoringCase("knowledge");
        assertThat(jdbcTemplate.queryForObject(
                "select schema_owner from application_schema_metadata where id = 1",
                String.class)).isEqualTo("testcase-agent-java-web");
        assertThat(environment.getRequiredProperty("app.artifacts.root"))
                .isEqualTo("./var/test-artifacts");
        assertThat(materialTraversalService).isNotNull();
    }

    /** [Req-ID]: REQ-STG-001, REQ-STG-006, REQ-STG-007 */
    @Test
    void addsOnlyApplicationOwnedStructuredGenerationState(@Autowired JdbcTemplate jdbcTemplate) {
        assertThat(tableNames(jdbcTemplate)).contains(
                "structured_generation_work_item",
                "structured_generation_attempt",
                "structured_requirement_fact",
                "structured_review_finding",
                "structured_feature_reconciliation",
                "structured_test_point",
                "structured_test_case",
                "structured_test_case_step",
                "structured_reference_binding");

        assertThat(columnNames(jdbcTemplate, "generation_task"))
                .contains("structured_processing_status", "structured_coverage_status");
        assertThat(columnNames(jdbcTemplate, "structured_generation_work_item"))
                .contains("identity_key", "ordinal_start", "ordinal_end", "accepted_result_sha256")
                .doesNotContain("raw_model_json", "raw_markdown");
        for (String table : java.util.List.of("structured_requirement_fact", "structured_review_finding",
                "structured_feature_reconciliation", "structured_function_list_item", "structured_test_point",
                "structured_test_case")) {
            assertThat(columnNames(jdbcTemplate, table)).contains("task_id");
        }
        assertThat(uniqueIndexNames(jdbcTemplate)).contains(
                "uq_structured_requirement_fact_task_key",
                "uq_structured_review_finding_task_key",
                "uq_structured_reconciliation_task_key",
                "uq_structured_function_list_task_item",
                "uq_structured_test_point_task_key",
                "uq_structured_test_case_task_key");
    }

    /** [Req-ID]: REQ-STG-001 */
    @Test
    void structuredSchemaUsesTheFirstFreeSharedMigrationVersion(@Autowired JdbcTemplate jdbcTemplate) {
        assertThat(jdbcTemplate.queryForMap("""
                SELECT version, script
                FROM flyway_schema_history
                WHERE script = 'V12__persist_structured_generation_results.sql'
                """))
                .containsEntry("version", "12")
                .containsEntry("script", "V12__persist_structured_generation_results.sql");
    }

    /** [Req-ID]: REQ-FTG-008, REQ-FTG-009 */
    @Test
    void addsOnlyAdditiveHighGranularityStructuredDeliveryFields(@Autowired JdbcTemplate jdbcTemplate) {
        assertThat(columnNames(jdbcTemplate, "structured_review_finding")).contains(
                "root_cause_kind", "affected_unit_keys_json", "affected_scope_summary",
                "bad_source_evidence_key", "bad_source_quote", "proposed_good_status", "proposed_good_text");
        assertThat(columnNames(jdbcTemplate, "structured_test_case")).contains(
                "name_text", "priority", "hardware_configuration_json", "software_configuration_json",
                "test_configuration_json", "parameter_configuration_json", "inputs_json",
                "expected_results_json", "evaluation_criteria", "result_evaluation_criteria",
                "termination_conditions_json", "result_collection", "author_name", "author_date");
        assertThat(columnNames(jdbcTemplate, "structured_test_case_step")).contains(
                "evaluation_criteria", "termination_or_error", "result_collection");
        assertThat(uniqueIndexNames(jdbcTemplate)).contains("uq_structured_review_finding_task_root_cause");
    }

    /** [Req-ID]: REQ-FTG-008, REQ-FTG-009 */
    @Test
    void highGranularitySchemaUsesTheNextFreeMigrationVersion(@Autowired JdbcTemplate jdbcTemplate) {
        assertThat(jdbcTemplate.queryForMap("""
                SELECT version, script
                FROM flyway_schema_history
                WHERE script = 'V13__extend_structured_delivery_contract.sql'
                """))
                .containsEntry("version", "13")
                .containsEntry("script", "V13__extend_structured_delivery_contract.sql");
    }

    /** [Req-ID]: REQ-FSC-007 */
    @Test
    void addsOnlyNullableSafeStructuredValidationDiagnosticFields(@Autowired JdbcTemplate jdbcTemplate) {
        for (String table : java.util.List.of("generation_task", "structured_generation_work_item",
                "structured_generation_attempt")) {
            assertThat(columnNames(jdbcTemplate, table)).contains(
                    "validation_error_code", "validation_error_path", "validation_error_message");
        }
        assertThat(jdbcTemplate.queryForMap("""
                SELECT version, script FROM flyway_schema_history
                WHERE script = 'V14__add_structured_validation_diagnostics.sql'
                """))
                .containsEntry("version", "14")
                .containsEntry("script", "V14__add_structured_validation_diagnostics.sql");
    }

    /** [Req-ID]: REQ-FSC-008 */
    @Test
    void addsV2ReconciliationStagingAndAtomicPublicationSchema(@Autowired JdbcTemplate jdbcTemplate) {
        assertThat(tableNames(jdbcTemplate)).contains(
                "structured_reconciliation_run",
                "structured_reconciliation_page_stage",
                "structured_reconciliation_relation_stage",
                "structured_reconciliation_relation_stage_binding",
                "structured_reconciliation_source_terminal");
        assertThat(columnNames(jdbcTemplate, "structured_feature_reconciliation")).contains(
                "protocol_version", "run_key", "owner_source_type", "owner_source_key");
        assertThat(jdbcTemplate.queryForMap("""
                SELECT version, script FROM flyway_schema_history
                WHERE script = 'V15__stage_and_publish_reconciliation_v2.sql'
                """))
                .containsEntry("version", "15")
                .containsEntry("script", "V15__stage_and_publish_reconciliation_v2.sql");
    }

    /** [Req-ID]: REQ-FTG-010 */
    @Test
    void allowsTheDurableSplitReviewParentStatusThroughAnAdditiveMigration(
            @Autowired JdbcTemplate jdbcTemplate) {
        assertThat(jdbcTemplate.queryForMap("""
                SELECT version, script FROM flyway_schema_history
                WHERE script = 'V16__allow_split_material_review_work.sql'
                """))
                .containsEntry("version", "16")
                .containsEntry("script", "V16__allow_split_material_review_work.sql");
    }

    /** [Req-ID]: REQ-FTG-013 */
    @Test
    void persistsSemanticMaterialWindowContextAndSplitLineageAdditively(
            @Autowired JdbcTemplate jdbcTemplate) {
        assertThat(columnNames(jdbcTemplate, "structured_generation_work_item")).contains(
                "material_document_id", "context_evidence_keys_json", "parent_work_item_id", "split_depth");
        assertThat(jdbcTemplate.queryForMap("""
                SELECT version, script FROM flyway_schema_history
                WHERE script = 'V17__persist_semantic_material_windows.sql'
                """))
                .containsEntry("version", "17")
                .containsEntry("script", "V17__persist_semantic_material_windows.sql");
    }

    /** [Req-ID]: REQ-FTG-014 */
    @Test
    void persistsFunctionListTargetQuotesWithoutRewritingHistoricalRows(
            @Autowired JdbcTemplate jdbcTemplate) {
        assertThat(columnNames(jdbcTemplate, "structured_function_list_item")).contains("target_quotes_json");
        assertThat(jdbcTemplate.queryForMap("""
                SELECT version, script FROM flyway_schema_history
                WHERE script = 'V18__persist_function_list_target_quotes.sql'
                """))
                .containsEntry("version", "18")
                .containsEntry("script", "V18__persist_function_list_target_quotes.sql");
    }

    /** [Req-ID]: REQ-AFCE-005, REQ-AFCE-008 */
    @Test
    void addsTheAuditableFunctionCandidateLedgerThroughOneForwardMigration(
            @Autowired JdbcTemplate jdbcTemplate) {
        assertThat(tableNames(jdbcTemplate)).contains(
                "structured_function_source_outcome",
                "structured_function_candidate",
                "structured_function_outcome_candidate");
        assertThat(columnNames(jdbcTemplate, "structured_function_source_outcome")).containsExactlyInAnyOrder(
                "work_item_id", "task_id", "unit_key", "source_ordinal", "kee_disposition",
                "java_final_decision", "reason_code");
        assertThat(columnNames(jdbcTemplate, "structured_function_candidate")).containsExactlyInAnyOrder(
                "work_item_id", "task_id", "candidate_ref", "path_text", "description", "target_quote",
                "recommended_status", "java_final_decision", "reason_code", "missing_information_json",
                "function_item_key");
        assertThat(columnNames(jdbcTemplate, "structured_function_outcome_candidate")).containsExactlyInAnyOrder(
                "work_item_id", "unit_key", "candidate_ref");
        assertThat(uniqueIndexNames(jdbcTemplate)).contains(
                "uq_structured_function_source_task_unit",
                "uq_structured_function_candidate_task_ref");
        assertThat(foreignKeyNames(jdbcTemplate)).contains(
                "fk_structured_function_source_work",
                "fk_structured_function_candidate_work",
                "fk_structured_function_candidate_item",
                "fk_structured_function_link_source",
                "fk_structured_function_link_candidate");
        assertThat(jdbcTemplate.queryForMap("""
                SELECT version, script FROM flyway_schema_history
                WHERE script = 'V19__persist_auditable_function_candidates.sql'
                """))
                .containsEntry("version", "19")
                .containsEntry("script", "V19__persist_auditable_function_candidates.sql");
    }

    /** [Req-ID]: REQ-TGV2-001, REQ-TGV2-004, REQ-TGV2-005, REQ-TGV2-008 */
    @Test
    void addsGenerationV2SnapshotsFactsFeedbackOutcomesAndPublicationLedgerWithoutRewritingHistory(
            @Autowired JdbcTemplate jdbcTemplate) {
        assertThat(columnNames(jdbcTemplate, "generation_task")).contains(
                "workflow_version", "input_version", "artifact_version", "approved_scope_version");
        assertThat(tableNames(jdbcTemplate)).contains(
                "v2_approved_function", "v2_requirement_fact", "v2_requirement_fact_quote",
                "v2_testability_feedback", "v2_testability_feedback_quote", "v2_generation_outcome",
                "v2_work_publication");
        assertThat(uniqueIndexNames(jdbcTemplate)).contains(
                "uq_v2_approved_function_sequence", "uq_v2_fact_quote_identity",
                "uq_v2_feedback_task_key", "uq_v2_outcome_task_point");
        assertThat(jdbcTemplate.queryForMap("""
                SELECT version, script FROM flyway_schema_history
                WHERE script = 'V20__persist_generation_v2_workflow.sql'
                """))
                .containsEntry("version", "20")
                .containsEntry("script", "V20__persist_generation_v2_workflow.sql");
    }

    /** [Req-ID]: REQ-TGV2-003 */
    @Test
    void allowsIncompleteMaterialRowsOnlyAsExplicitV2TraversalStaging(
            @Autowired JdbcTemplate jdbcTemplate) {
        assertThat(jdbcTemplate.queryForMap("""
                SELECT version, script FROM flyway_schema_history
                WHERE script = 'V21__allow_staged_material_inventory.sql'
                """))
                .containsEntry("version", "21")
                .containsEntry("script", "V21__allow_staged_material_inventory.sql");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE constraint_schema = DATABASE()
                  AND table_name = 'material_inventory_document'
                  AND constraint_name = 'chk_material_inventory_document_complete'
                """, Integer.class)).isZero();
    }

    /** [Req-ID]: REQ-TGV2-011 */
    @Test
    void addsSupersededWorkStatusWithoutRewritingAcceptedFallbackAudit(
            @Autowired JdbcTemplate jdbcTemplate) {
        assertThat(jdbcTemplate.queryForMap("""
                SELECT version, script FROM flyway_schema_history
                WHERE script = 'V22__supersede_replaced_v2_fallback_work.sql'
                """))
                .containsEntry("version", "22")
                .containsEntry("script", "V22__supersede_replaced_v2_fallback_work.sql");
        assertThat(jdbcTemplate.queryForMap("""
                SELECT version, script FROM flyway_schema_history
                WHERE script = 'V23__persist_validated_v2_result_replay.sql'
                """))
                .containsEntry("version", "23")
                .containsEntry("script", "V23__persist_validated_v2_result_replay.sql");
        assertThat(columnNames(jdbcTemplate, "v2_work_publication")).contains("validated_result_replay_json");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT check_clause FROM information_schema.check_constraints
                WHERE constraint_schema = DATABASE()
                  AND constraint_name = 'chk_structured_generation_work_status'
                """, String.class)).contains("SUPERSEDED");
    }

    /** [Req-ID]: REQ-TGV2-013 */
    @Test
    void indexesArtifactIdentityWithoutMakingEqualWorkbookContentIllegal(
            @Autowired JdbcTemplate jdbcTemplate) {
        assertThat(jdbcTemplate.queryForMap("""
                SELECT version, script FROM flyway_schema_history
                WHERE script = 'V24__enforce_generation_artifact_identity.sql'
                """))
                .containsEntry("version", "24")
                .containsEntry("script", "V24__enforce_generation_artifact_identity.sql");
        assertThat(columnNames(jdbcTemplate, "generation_task")).contains("artifact_path_sha256");
        assertThat(jdbcTemplate.queryForList("""
                SELECT index_name FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name='generation_task' AND non_unique=0
                  AND index_name IN ('uq_generation_task_artifact_id', 'uq_generation_task_artifact_path_sha256')
                ORDER BY index_name
                """, String.class)).containsExactly(
                        "uq_generation_task_artifact_id", "uq_generation_task_artifact_path_sha256");
    }

    /** [Req-ID]: REQ-STG-001 */
    @Test
    void productionContextRequiresTheRealStructuredAllCoordinator(
            @Autowired GenerationWorkflow workflow,
            @Autowired StructuredAllGenerationCoordinator coordinator,
            @Autowired StructuredGenerationAcceptanceStore acceptanceStore) {
        assertThat(workflow).isNotNull();
        assertThat(coordinator).isInstanceOf(com.testcaseagent.task.VersionedStructuredAllGenerationCoordinator.class);
        assertThat(acceptanceStore).isNotNull();
    }

    /** [Req-ID]: REQ-STG-007 */
    @Test
    void persistsStructuredCancellationOnTheIndependentProcessingAxis(
            @Autowired JdbcTemplate jdbcTemplate, @Autowired GenerationTaskRepository repository) {
        jdbcTemplate.update("DELETE FROM generation_task WHERE id = 'task-structured-cancel'");
        jdbcTemplate.update("""
                INSERT INTO generation_task (id, task_mode, status, request_snapshot,
                    structured_processing_status, structured_coverage_status)
                VALUES ('task-structured-cancel', 'ALL', 'GENERATING', JSON_OBJECT(), 'RUNNING', 'PENDING')
                """);

        repository.cancelStructuredTask("task-structured-cancel", StructuredCoverageStatus.PENDING);

        assertThat(jdbcTemplate.queryForMap("""
                SELECT status, structured_processing_status, structured_coverage_status, artifact_id
                FROM generation_task WHERE id = 'task-structured-cancel'
                """))
                .containsEntry("status", "CANCELLED")
                .containsEntry("structured_processing_status", "CANCELLED")
                .containsEntry("structured_coverage_status", "PENDING")
                .containsEntry("artifact_id", null);
    }

    private static java.util.List<String> tableNames(JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                """, String.class);
    }

    private static java.util.List<String> columnNames(JdbcTemplate jdbcTemplate, String tableName) {
        return jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ?
                """, String.class, tableName);
    }

    private static java.util.List<String> uniqueIndexNames(JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT index_name
                FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND non_unique = 0
                """, String.class);
    }

    private static java.util.List<String> foreignKeyNames(JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT constraint_name
                FROM information_schema.referential_constraints
                WHERE constraint_schema = DATABASE()
                """, String.class);
    }
}
