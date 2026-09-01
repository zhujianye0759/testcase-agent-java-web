package com.testcaseagent.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testcaseagent.export.WorkbookArtifact;
import com.testcaseagent.export.StructuredReviewRow;
import com.testcaseagent.export.StructuredTestCaseRow;
import com.testcaseagent.export.StructuredTestStep;
import com.testcaseagent.export.StructuredWorkbookExportRequest;
import com.testcaseagent.export.StructuredWorkbookRowSource;
import com.testcaseagent.featureaudit.AuditWorkClaim;
import com.testcaseagent.featureaudit.FeatureReviewConclusion;
import com.testcaseagent.featureaudit.FeatureReviewConclusionType;
import com.testcaseagent.featureaudit.FeatureSourceCandidate;
import com.testcaseagent.featureaudit.FrozenFeatureSource;
import com.testcaseagent.featureaudit.FrozenFeatureTarget;
import com.testcaseagent.featureaudit.MaterialInventoryDocument;
import com.testcaseagent.featureaudit.MaterialInventoryPage;
import com.testcaseagent.featureaudit.MaterialInventoryUnit;
import com.testcaseagent.markdown.MarkdownAuditRow;
import com.testcaseagent.markdown.MarkdownGenerationResult;
import com.testcaseagent.markdown.MarkdownTestCaseRow;
import com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignV2Input;
import com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignV2Result;
import com.testcaseagent.knowledgeagent.MaterialContentTypeKey;
import com.testcaseagent.knowledgeagent.RequirementFactExtractionV2Input;
import com.testcaseagent.knowledgeagent.RequirementFactExtractionV2Result;
import com.testcaseagent.knowledgeagent.StructuredSourceQuoteV2;
import com.testcaseagent.testcase.GenerationTaskMode;
import com.testcaseagent.structuredgeneration.StructuredCoverageStatus;
import com.testcaseagent.structuredgeneration.StructuredGenerationAcceptanceStore;
import com.testcaseagent.structuredgeneration.StructuredProcessingStatus;
import com.testcaseagent.structuredgeneration.V2GenerationPlanner;
import com.testcaseagent.validation.FunctionalTestcaseV2Validator;
import com.testcaseagent.validation.ReaderFacingTextPolicy;
import com.testcaseagent.validation.StructuredValidationFailure;
import com.testcaseagent.scope.RequirementScope;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.HexFormat;
import java.util.function.IntSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MySQL persistence boundary for durable task, batch, and attempt state.
 *
 * [Req-ID]: REQ-TSK-001, REQ-TSK-002, REQ-TSK-004, REQ-TSK-005, REQ-TSK-006, REQ-TSK-007,
 * REQ-ANA-007, REQ-CWR-003, REQ-FTG-007, REQ-FTG-008, REQ-FTG-009
 */
public final class GenerationTaskRepository {

    private static final int MAX_ATTEMPTS = 3;
    private static final int V2_FACT_RECOVERY_WORK_BATCH_SIZE = 9;
    // One testcase work can legitimately carry a near-limit result. Keeping recovery at the work boundary prevents
    // multiple large test-point projections from being retained together while preserving the complete fact context
    // required by that single persisted invocation.
    private static final int V2_TESTCASE_RECOVERY_WORK_BATCH_SIZE = 1;
    private static final int V2_RECOVERY_UNIT_BATCH_SIZE = 256;
    // Connector/J uses this sentinel for forward-only row streaming without buffering the whole ResultSet.
    // Export queries never issue nested SQL while the cursor is open. [Req-ID]: REQ-TGV2-009
    private static final int MYSQL_STREAMING_FETCH_SIZE = Integer.MIN_VALUE;
    private static final List<String> UNFINISHED_WORK_BUSINESS_TABLES = List.of(
            "structured_requirement_fact",
            "structured_review_finding",
            "structured_function_list_item",
            "structured_feature_reconciliation",
            "structured_test_point",
            "structured_test_case",
            "structured_test_case_step",
            "structured_reference_binding",
            "structured_function_source_outcome",
            "structured_function_candidate",
            "structured_function_outcome_candidate");
    private static final List<String> RECONCILIATION_WORK_OWNED_TABLES = List.of(
            "structured_reconciliation_run",
            "structured_reconciliation_page_stage",
            "structured_reconciliation_relation_stage",
            "structured_reconciliation_relation_stage_binding",
            "structured_reconciliation_source_terminal");
    /**
     * Technical terminal types that may be repaired outside the task and then resumed by an explicit user action.
     * Contract, authorization, structured-output and business-validation failures are intentionally absent.
     * [Req-ID]: REQ-TGV2-013
     */
    private static final Set<String> V2_ZERO_WRITE_TECHNICAL_FAILURES = Set.of(
            "request_too_large", "session_not_found", "skill_unavailable",
            "model_unavailable", "model_execution_failed", "worker_interrupted");
    /** Tables whose task-owned rows prove that the failure artifact is no longer a zero-write projection. */
    private static final List<String> V2_TECHNICAL_RECOVERY_BUSINESS_TABLES = List.of(
            "v2_requirement_fact", "v2_testability_feedback", "structured_test_point",
            "v2_generation_outcome", "structured_test_case", "v2_work_publication",
            "structured_requirement_fact", "structured_review_finding", "structured_function_list_item",
            "structured_feature_reconciliation", "structured_function_source_outcome",
            "structured_function_candidate", "structured_reconciliation_source_terminal",
            "material_audit_work", "feature_source_candidate", "feature_review_conclusion",
            "frozen_feature_target", "requirement_issue_candidate", "generation_batch");
    private static final Pattern STACK_EXCEPTION = Pattern.compile("(?m)^[\\w.$]+(?:Exception|Error)(?::[^\\r\\n]*)?$");
    private static final Pattern STACK_FRAME = Pattern.compile("(?m)^\\s*at\\s+[\\w.$]+\\([^\\r\\n]*\\)\\s*$");
    private static final Pattern V2_FACT_STATEMENT_PATH = Pattern.compile(
            "^\\$\\.requirement_facts\\[[0-9]+]\\.statement$");
    private static final Pattern V2_EXPECTED_RESULTS_PATH = Pattern.compile(
            "^\\$\\.testcases\\[[0-9]+]\\.expected_results$");
    private static final Pattern V2_IDENTITY_LABEL_PATH = Pattern.compile(
            "^\\$\\.testcases\\[[0-9]+]\\.(?:name|title)$");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate retryTransactionTemplate;
    private final TransactionTemplate detailSnapshotTemplate;

    public GenerationTaskRepository(
            JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.retryTransactionTemplate = new TransactionTemplate(transactionManager);
        this.retryTransactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.detailSnapshotTemplate = new TransactionTemplate(transactionManager);
        this.detailSnapshotTemplate.setReadOnly(true);
        this.detailSnapshotTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public void createTask(String taskId, CreateGenerationTaskRequest request) {
        transactionTemplate.executeWithoutResult(ignored -> insertTaskAndV2Scope(taskId, request, null));
    }

    /**
     * Persists one task-owned material inventory without silently accepting changed source coordinates or text.
     *
     * [Req-ID]: REQ-BFA-001
     */
    public void persistMaterialInventory(String taskId, List<MaterialInventoryUnit> units) {
        transactionTemplate.executeWithoutResult(ignored -> units.forEach(unit -> persistMaterialUnit(taskId, unit)));
    }

    /**
     * Atomically replaces a task's complete material inventory and its initial bounded scan work.
     *
     * <p>Without an explicit replacement signal, only an exact already-complete replay is accepted. This prevents
     * a later failed document read from turning a partial inventory into a green traversal gate.</p>
     *
     * [Req-ID]: REQ-SMR-002, REQ-SMR-003, REQ-BFA-001
     */
    public void replaceMaterialInventory(
            String taskId, List<MaterialInventoryDocument> documents, boolean explicitlyReplaced) {
        List<MaterialInventoryDocument> replacement = List.copyOf(documents);
        requireCompleteDistinctDocuments(replacement);
        transactionTemplate.executeWithoutResult(ignored -> {
            if (explicitlyReplaced) {
                clearMaterialAuditState(taskId);
            } else if (hasAnyMaterialInventory(taskId)) {
                if (!matchesCompleteMaterialInventory(taskId, replacement)) {
                    throw new IllegalStateException("Material inventory replay conflicts with the retained complete inventory");
                }
                return;
            }
            replacement.forEach(document -> persistMaterialDocument(taskId, document));
            replacement.forEach(document -> document.units().forEach(unit -> persistMaterialUnit(taskId, unit)));
            replacement.forEach(document -> document.units().forEach(unit -> createInitialAuditWork(taskId, unit)));
        });
    }

    /**
     * Durably stages one bounded V2 parsed-unit page without opening the inventory completion gate.
     *
     * <p>The document row is locked before any unit insert. Exact replays are idempotent; changed totals,
     * coordinates, roles or text fail closed. Staging never creates legacy audit work and never marks the document
     * complete.</p>
     *
     * [Req-ID]: REQ-TGV2-003
     */
    public void stageMaterialInventoryPage(String taskId, MaterialInventoryPage page) {
        Objects.requireNonNull(page, "page must not be null");
        transactionTemplate.executeWithoutResult(ignored -> {
            List<StagedMaterialDocumentRow> documents = jdbcTemplate.query("""
                    SELECT document_id, knowledge_id, document_role, total_units, complete
                    FROM material_inventory_document
                    WHERE task_id = ? AND document_id = ?
                    FOR UPDATE
                    """, (row, ignoredRow) -> new StagedMaterialDocumentRow(
                    row.getString("document_id"), row.getString("knowledge_id"), row.getString("document_role"),
                    row.getInt("total_units"), row.getBoolean("complete")), taskId, page.documentId());
            if (documents.isEmpty()) {
                jdbcTemplate.update("""
                        INSERT INTO material_inventory_document (
                            task_id, document_id, knowledge_id, document_role, total_units, complete)
                        VALUES (?, ?, ?, ?, ?, FALSE)
                        """, taskId, page.documentId(), page.knowledgeId(), page.documentRole(), page.totalUnits());
            } else if (documents.size() != 1 || !documents.get(0).matches(page)) {
                throw new IllegalStateException("Material inventory page conflicts with the retained document");
            }
            for (MaterialInventoryUnit unit : page.units()) {
                if (unit.ordinal() > page.totalUnits()) {
                    throw new IllegalStateException("Material inventory page ordinal exceeds the retained total");
                }
                persistMaterialUnit(taskId, unit);
            }
        });
    }

    /**
     * Atomically publishes staged V2 material only after the exact frozen document set is complete and contiguous.
     *
     * <p>Every downstream V2 query requires {@code complete=true}; consequently a process crash before this method
     * commits can leave restartable staging rows but cannot expose partial formal evidence.</p>
     *
     * [Req-ID]: REQ-TGV2-003, REQ-TGV2-008
     */
    public void publishStagedMaterialInventory(String taskId, RequirementScope scope) {
        Objects.requireNonNull(scope, "scope must not be null");
        transactionTemplate.executeWithoutResult(ignored -> {
            List<StagedMaterialDocumentRow> documents = jdbcTemplate.query("""
                    SELECT document_id, knowledge_id, document_role, total_units, complete
                    FROM material_inventory_document
                    WHERE task_id = ?
                    ORDER BY document_id
                    FOR UPDATE
                    """, (row, ignoredRow) -> new StagedMaterialDocumentRow(
                    row.getString("document_id"), row.getString("knowledge_id"), row.getString("document_role"),
                    row.getInt("total_units"), row.getBoolean("complete")), taskId);
            Map<String, com.testcaseagent.scope.RequirementDocumentCoordinate> expected = scope.documents().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            com.testcaseagent.scope.RequirementDocumentCoordinate::documentId,
                            java.util.function.Function.identity()));
            if (documents.size() != expected.size()
                    || !documents.stream().map(StagedMaterialDocumentRow::documentId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet()).equals(expected.keySet())) {
                throw new IllegalStateException("Staged material documents do not match the frozen scope");
            }
            int expectedTaskUnits = 0;
            for (StagedMaterialDocumentRow document : documents) {
                String expectedRole = documentRoleForScope(expected.get(document.documentId()));
                if (!document.documentId().equals(document.knowledgeId())
                        || !expectedRole.equals(document.documentRole())) {
                    throw new IllegalStateException("Staged material identity does not match the frozen scope");
                }
                MaterialUnitStats stats = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) AS persisted_units,
                               COUNT(DISTINCT ordinal) AS distinct_ordinals,
                               COALESCE(MIN(ordinal), 0) AS first_ordinal,
                               COALESCE(MAX(ordinal), 0) AS last_ordinal,
                               SUM(CASE WHEN document_role <> ? THEN 1 ELSE 0 END) AS role_mismatches
                        FROM material_inventory_unit
                        WHERE task_id = ? AND document_id = ?
                        """, (row, ignoredRow) -> new MaterialUnitStats(
                        row.getInt("persisted_units"), row.getInt("distinct_ordinals"),
                        row.getInt("first_ordinal"), row.getInt("last_ordinal"), row.getInt("role_mismatches")),
                        document.documentRole(), taskId, document.documentId());
                if (stats == null || !stats.complete(document.totalUnits())) {
                    throw new IllegalStateException("Staged material units are incomplete or inconsistent");
                }
                expectedTaskUnits += document.totalUnits();
            }
            Integer retainedTaskUnits = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM material_inventory_unit WHERE task_id = ?", Integer.class, taskId);
            if (retainedTaskUnits == null || retainedTaskUnits != expectedTaskUnits) {
                throw new IllegalStateException("Staged material inventory contains out-of-scope units");
            }
            jdbcTemplate.update("UPDATE material_inventory_document SET complete = TRUE WHERE task_id = ?", taskId);
        });
    }

    /**
     * Clears the previous traversal only after the caller explicitly reports source replacement.
     *
     * <p>This is intentionally separate from ordinary replay: a replacement failure must leave no prior inventory
     * eligible to satisfy the traversal-complete gate.</p>
     *
     * [Req-ID]: REQ-SMR-002, REQ-SMR-003, REQ-BFA-001
     */
    public void clearMaterialInventoryForExplicitReplacement(String taskId) {
        transactionTemplate.executeWithoutResult(ignored -> clearMaterialAuditState(taskId));
    }

    /**
     * Proves that exactly the frozen documents have complete summaries and persisted unit counts.
     *
     * [Req-ID]: REQ-SMR-002, REQ-BFA-001
     */
    public boolean hasCompleteMaterialInventory(String taskId, RequirementScope scope) {
        List<MaterialInventorySummary> documents = jdbcTemplate.query("""
                SELECT document.document_id, document.total_units, document.complete,
                       COUNT(unit.unit_id) AS persisted_units,
                       COUNT(DISTINCT unit.ordinal) AS distinct_ordinals,
                       COALESCE(MIN(unit.ordinal), 0) AS first_ordinal,
                       COALESCE(MAX(unit.ordinal), 0) AS last_ordinal
                FROM material_inventory_document document
                LEFT JOIN material_inventory_unit unit
                  ON unit.task_id = document.task_id AND unit.document_id = document.document_id
                WHERE document.task_id = ?
                GROUP BY document.document_id, document.total_units, document.complete
                ORDER BY document.document_id
        """, (row, ignored) -> new MaterialInventorySummary(row.getString("document_id"),
                row.getInt("total_units"), row.getBoolean("complete"), row.getInt("persisted_units"),
                row.getInt("distinct_ordinals"), row.getInt("first_ordinal"), row.getInt("last_ordinal")), taskId);
        if (documents.size() != scope.documents().size()) {
            return false;
        }
        java.util.Set<String> expectedIds = scope.documents().stream()
                .map(com.testcaseagent.scope.RequirementDocumentCoordinate::documentId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!documents.stream().map(MaterialInventorySummary::documentId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet()).equals(expectedIds)) {
            return false;
        }
        return documents.stream().allMatch(MaterialInventorySummary::isCompleteAndContiguous);
    }

    /** Returns only formal V2 material identities so the coordinator can load one document at a time. */
    public List<String> formalRequirementMaterialIds(String taskId) {
        return jdbcTemplate.queryForList("""
                SELECT document_id FROM material_inventory_document
                WHERE task_id = ? AND complete = TRUE
                  AND document_role IN ('REQUIREMENT','WORK_ORDER_PLAN')
                ORDER BY document_id
                """, String.class, taskId);
    }

    /**
     * Reads only complete formal-material coordinates. Parsed-unit content is deliberately excluded so document size
     * cannot determine coordinator heap usage. [Req-ID]: REQ-TGV2-003
     */
    public List<V2GenerationPlanner.MaterialDescriptor> formalRequirementMaterials(String taskId) {
        List<FormalMaterialSummary> materials = jdbcTemplate.query("""
                SELECT document.document_id, document.document_role, document.total_units, document.complete,
                       COUNT(unit.unit_id) AS persisted_units,
                       COALESCE(MIN(unit.ordinal), 0) AS first_ordinal,
                       COALESCE(MAX(unit.ordinal), 0) AS last_ordinal
                FROM material_inventory_document document
                LEFT JOIN material_inventory_unit unit
                  ON unit.task_id = document.task_id AND unit.document_id = document.document_id
                WHERE document.task_id = ? AND document.document_role IN ('REQUIREMENT','WORK_ORDER_PLAN')
                GROUP BY document.document_id, document.document_role, document.total_units, document.complete
                ORDER BY document.document_id
                """, (row, ignored) -> new FormalMaterialSummary(
                row.getString("document_id"), row.getString("document_role"), row.getInt("total_units"),
                row.getBoolean("complete"), row.getInt("persisted_units"), row.getInt("first_ordinal"),
                row.getInt("last_ordinal")), taskId);
        return materials.stream().map(material -> {
            if (!material.complete() || material.totalUnits() != material.persistedUnits()
                    || material.lastOrdinal() - material.firstOrdinal() + 1 != material.totalUnits()) {
                throw new IllegalStateException("Frozen formal material inventory is incomplete");
            }
            return new V2GenerationPlanner.MaterialDescriptor(material.documentId(), material.documentRole(),
                    material.totalUnits(), material.firstOrdinal(), material.lastOrdinal());
        }).toList();
    }

    /**
     * Loads the exact bounded neighborhood needed to plan one 8-16 unit target and at most four context units per
     * side. The method fails closed on gaps or document drift instead of truncating a large material. [Req-ID]:
     * REQ-TGV2-003
     */
    public List<MaterialInventoryUnit> materialInventoryPlanningSlice(String taskId, String documentId,
            int targetStartOrdinal, int documentFirstOrdinal, int documentLastOrdinal) {
        if (targetStartOrdinal < documentFirstOrdinal || targetStartOrdinal > documentLastOrdinal) {
            throw new IllegalArgumentException("Target cursor must belong to the frozen material");
        }
        int first = Math.max(documentFirstOrdinal, targetStartOrdinal - 4);
        int last = Math.min(documentLastOrdinal, targetStartOrdinal + 19);
        List<MaterialInventoryUnit> units = jdbcTemplate.query("""
                        SELECT unit.document_id, unit.document_role, unit.unit_id, unit.chunk_index, unit.ordinal,
                               unit.content, unit.start_at, unit.end_at
                        FROM material_inventory_unit unit
                        JOIN material_inventory_document document
                          ON document.task_id = unit.task_id AND document.document_id = unit.document_id
                         AND document.complete = TRUE
                        WHERE unit.task_id = ? AND unit.document_id = ? AND unit.ordinal BETWEEN ? AND ?
                        ORDER BY unit.ordinal, unit.unit_id
                        """, (row, ignored) -> new MaterialInventoryUnit(
                row.getString("document_id"), row.getString("document_role"), row.getString("unit_id"),
                row.getInt("chunk_index"), row.getInt("ordinal"), row.getString("content"),
                row.getLong("start_at"), row.getLong("end_at")), taskId, documentId, first, last);
        if (units.size() != last - first + 1) {
            throw new IllegalStateException("Frozen material planning neighborhood is incomplete");
        }
        for (int index = 0; index < units.size(); index++) {
            MaterialInventoryUnit unit = units.get(index);
            if (!documentId.equals(unit.documentId()) || unit.ordinal() != first + index
                    || !("REQUIREMENT".equals(unit.documentRole())
                    || "WORK_ORDER_PLAN".equals(unit.documentRole()))) {
                throw new IllegalStateException("Frozen material planning neighborhood is inconsistent");
            }
        }
        return List.copyOf(units);
    }

    /** Loads one exact frozen document for bounded V2 window planning. [Req-ID]: REQ-TGV2-003 */
    public MaterialInventoryDocument materialInventoryDocument(String taskId, String documentId) {
        List<MaterialInventoryDocument> documents = jdbcTemplate.query("""
                SELECT document_id, knowledge_id, document_role, total_units, complete
                FROM material_inventory_document
                WHERE task_id = ? AND document_id = ? AND complete = TRUE
                """, (row, ignored) -> new MaterialInventoryDocument(
                row.getString("document_id"), row.getString("knowledge_id"), row.getString("document_role"),
                row.getInt("total_units"), row.getBoolean("complete"),
                materialInventory(row.getString("document_id"), taskId)), taskId, documentId);
        if (documents.size() != 1) {
            throw new IllegalStateException("Frozen material inventory document is unavailable");
        }
        return documents.get(0);
    }

    /** Reads complete document summaries in deterministic document order. [Req-ID]: REQ-SMR-002 */
    public List<MaterialInventoryDocument> materialInventoryDocuments(String taskId) {
        return jdbcTemplate.query("""
                        SELECT document_id, knowledge_id, document_role, total_units, complete
                        FROM material_inventory_document
                        WHERE task_id = ?
                        ORDER BY document_id
                        """, (resultSet, ignored) -> new MaterialInventoryDocument(
                resultSet.getString("document_id"), resultSet.getString("knowledge_id"),
                resultSet.getString("document_role"), resultSet.getInt("total_units"), resultSet.getBoolean("complete"),
                materialInventory(resultSet.getString("document_id"), taskId)), taskId);
    }

    /**
     * Reads the task-owned inventory in deterministic document and parsed-unit order.
     *
     * [Req-ID]: REQ-BFA-001
     */
    public List<MaterialInventoryUnit> materialInventory(String taskId) {
        return jdbcTemplate.query("""
                        SELECT document_id, document_role, unit_id, chunk_index, ordinal, content, start_at, end_at
                        FROM material_inventory_unit
                        WHERE task_id = ?
                        ORDER BY document_id, ordinal, unit_id
                        """, (resultSet, ignored) -> new MaterialInventoryUnit(
                resultSet.getString("document_id"), resultSet.getString("document_role"), resultSet.getString("unit_id"),
                resultSet.getInt("chunk_index"), resultSet.getInt("ordinal"), resultSet.getString("content"),
                resultSet.getLong("start_at"), resultSet.getLong("end_at")), taskId);
    }

    private List<MaterialInventoryUnit> materialInventory(String documentId, String taskId) {
        return jdbcTemplate.query("""
                        SELECT document_id, document_role, unit_id, chunk_index, ordinal, content, start_at, end_at
                        FROM material_inventory_unit
                        WHERE task_id = ? AND document_id = ?
                        ORDER BY ordinal, unit_id
                        """, (resultSet, ignored) -> new MaterialInventoryUnit(
                resultSet.getString("document_id"), resultSet.getString("document_role"), resultSet.getString("unit_id"),
                resultSet.getInt("chunk_index"), resultSet.getInt("ordinal"), resultSet.getString("content"),
                resultSet.getLong("start_at"), resultSet.getLong("end_at")), taskId, documentId);
    }

    /**
     * Creates a task-owned audit work item exactly once for a retained material unit and audit pass/stage.
     *
     * [Req-ID]: REQ-BFA-001
     */
    public void createAuditWorkIfAbsent(
            String workId, String taskId, MaterialInventoryUnit unit, int passNumber, String stage) {
        if (passNumber <= 0 || stage == null || stage.isBlank()) {
            throw new IllegalArgumentException("Audit work pass and stage are required");
        }
        transactionTemplate.executeWithoutResult(ignored -> {
            try {
                jdbcTemplate.update("""
                                INSERT INTO material_audit_work (
                                    id, task_id, document_id, unit_id, audit_pass, audit_stage, status)
                                VALUES (?, ?, ?, ?, ?, ?, 'QUEUED')
                                """, workId, taskId, unit.documentId(), unit.unitId(), passNumber, stage);
            } catch (DuplicateKeyException duplicate) {
                String storedId = jdbcTemplate.query("""
                                SELECT id FROM material_audit_work
                                WHERE task_id = ? AND document_id = ? AND unit_id = ?
                                  AND audit_pass = ? AND audit_stage = ?
                                FOR UPDATE
                                """, (resultSet, ignoredRow) -> resultSet.getString("id"), taskId, unit.documentId(),
                        unit.unitId(), passNumber, stage).stream().findFirst().orElseThrow(() -> duplicate);
                if (!storedId.equals(workId)) {
                    throw new IllegalStateException("Audit work identity conflicts with a different work id");
                }
            }
        });
    }

    /**
     * Claims the oldest unfinished material audit item, recovering only leases that have actually expired.
     *
     * [Req-ID]: REQ-BFA-001
     */
    public Optional<AuditWorkClaim> claimNextAuditWork(String leaseOwner, Duration leaseDuration) {
        return claimNextAuditWork(null, leaseOwner, leaseDuration);
    }

    /**
     * Claims only the supplied task's next eligible audit item. A second requirement pass cannot run until the
     * first pass for its exact unit has reached its durable completed state.
     *
     * [Req-ID]: REQ-BFA-001, REQ-BFA-002
     */
    public Optional<AuditWorkClaim> claimNextAuditWork(String taskId, String leaseOwner, Duration leaseDuration) {
        if (leaseOwner == null || leaseOwner.isBlank() || leaseDuration == null || leaseDuration.isZero()
                || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("Audit work lease owner and positive duration are required");
        }
        return Optional.ofNullable(transactionTemplate.execute(ignored -> {
            recoverExpiredAuditWorkInTransaction();
            AuditWorkRow work = jdbcTemplate.query("""
                            SELECT id, task_id, document_id, unit_id, audit_pass, audit_stage
                            FROM material_audit_work w
                            WHERE w.status = 'QUEUED'
                              AND (? IS NULL OR w.task_id = ?)
                              AND (w.audit_pass = 1 OR EXISTS (
                                  SELECT 1 FROM material_audit_work first_pass
                                  WHERE first_pass.task_id = w.task_id
                                    AND first_pass.document_id = w.document_id
                                    AND first_pass.unit_id = w.unit_id
                                    AND first_pass.audit_pass = 1
                                    AND first_pass.status = 'COMPLETED'))
                            ORDER BY created_at, id
                            LIMIT 1 FOR UPDATE SKIP LOCKED
                            """, (resultSet, ignoredRow) -> new AuditWorkRow(
                    resultSet.getString("id"), resultSet.getString("task_id"), resultSet.getString("document_id"),
                    resultSet.getString("unit_id"), resultSet.getInt("audit_pass"), resultSet.getString("audit_stage")), taskId, taskId)
                    .stream().findFirst().orElse(null);
            if (work == null) {
                return null;
            }
            long leaseMicros = Math.max(1, leaseDuration.toNanos() / 1_000);
            int claimed = jdbcTemplate.update("""
                            UPDATE material_audit_work
                            SET status = 'RUNNING', lease_owner = ?,
                                lease_expires_at = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL ? MICROSECOND)
                            WHERE id = ? AND status = 'QUEUED'
                            """, leaseOwner, leaseMicros, work.id());
            if (claimed != 1) {
                throw new IllegalStateException("Unable to claim audit work " + work.id());
            }
            int attemptNumber = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(attempt_number), 0) + 1 FROM material_audit_attempt WHERE work_id = ?",
                    Integer.class, work.id());
            String previousFailureSummary = jdbcTemplate.query("""
                            SELECT failure_summary
                            FROM material_audit_attempt
                            WHERE work_id = ? AND status = 'FAILED'
                            ORDER BY attempt_number DESC
                            LIMIT 1
                            """, (resultSet, ignoredRow) -> resultSet.getString("failure_summary"), work.id())
                    .stream().findFirst().orElse(null);
            String attemptId = UUID.randomUUID().toString();
            jdbcTemplate.update("""
                            INSERT INTO material_audit_attempt (id, work_id, attempt_number, status)
                            VALUES (?, ?, ?, 'RUNNING')
                            """, attemptId, work.id(), attemptNumber);
            Instant expiresAt = jdbcTemplate.queryForObject(
                    "SELECT lease_expires_at FROM material_audit_work WHERE id = ?",
                    (resultSet, ignoredRow) -> resultSet.getTimestamp("lease_expires_at").toInstant(), work.id());
            return new AuditWorkClaim(work.id(), attemptId, attemptNumber, work.taskId(), work.documentId(), work.unitId(),
                    work.passNumber(), work.stage(), expiresAt, previousFailureSummary);
        }));
    }

    /**
     * Completes only the still-running attempt that owns the supplied durable work claim.
     *
     * [Req-ID]: REQ-BFA-001
     */
    public void completeAuditWork(AuditWorkClaim claim) {
        transactionTemplate.executeWithoutResult(ignored -> {
            completeAuditWorkInTransaction(claim);
        });
    }

    /**
     * Persists one accepted scan result and completes its same lease-owned work in one transaction. Replays must
     * match all source coordinates and text exactly; a changed model occurrence fails closed.
     *
     * [Req-ID]: REQ-BFA-001, REQ-BFA-002, REQ-BFA-004
     */
    public void persistScanAndCompleteAuditWork(
            AuditWorkClaim claim, List<FeatureSourceCandidate> candidates, List<FeatureSourceCandidate> duplicates,
            boolean converged) {
        List<FeatureSourceCandidate> accepted = List.copyOf(candidates == null ? List.of() : candidates);
        List<FeatureSourceCandidate> repeated = List.copyOf(duplicates == null ? List.of() : duplicates);
        transactionTemplate.executeWithoutResult(ignored -> {
            requireRunningAuditClaim(claim);
            accepted.forEach(candidate -> persistFeatureSourceCandidate(claim, candidate));
            repeated.forEach(candidate -> persistDuplicateOccurrence(claim, candidate));
            persistScanOutcome(claim, accepted.size(), repeated.size(), converged);
            completeAuditWorkInTransaction(claim);
        });
    }

    /** Marks one failed scan attempt retryable until its third failure, after which it is an explicit permanent failure. */
    public void failAuditWork(AuditWorkClaim claim, String failureSummary) {
        failAuditWork(claim, failureSummary, true);
    }

    /**
     * Records a bounded audit failure and immediately closes a caller-proven permanent protocol failure.
     *
     * [Req-ID]: REQ-KSI-002
     */
    public void failAuditWork(AuditWorkClaim claim, String failureSummary, boolean retryable) {
        String summary = failureSummary == null || failureSummary.isBlank() ? "Audit scan failed" : failureSummary;
        transactionTemplate.executeWithoutResult(ignored -> {
            int attemptUpdated = jdbcTemplate.update("""
                            UPDATE material_audit_attempt
                            SET status = 'FAILED', failure_summary = ?, completed_at = CURRENT_TIMESTAMP(6)
                            WHERE id = ? AND work_id = ? AND status = 'RUNNING'
                            """, summary.substring(0, Math.min(summary.length(), 2048)), claim.attemptId(), claim.workId());
            if (attemptUpdated != 1) throw new IllegalStateException("Audit work claim is no longer running: " + claim.workId());
            int workUpdated = jdbcTemplate.update("""
                            UPDATE material_audit_work
                            SET status = CASE WHEN ? = FALSE OR ? >= ? THEN 'FAILED' ELSE 'QUEUED' END,
                                lease_owner = NULL, lease_expires_at = NULL
                            WHERE id = ? AND status = 'RUNNING'
                            """, retryable, claim.attemptNumber(), MAX_ATTEMPTS, claim.workId());
            if (workUpdated != 1) throw new IllegalStateException("Audit work state changed concurrently: " + claim.workId());
        });
    }

    /** Reads retained candidates in deterministic source order without crossing task ownership. */
    public List<FeatureSourceCandidate> featureSourceCandidates(String taskId) {
        return jdbcTemplate.query(featureCandidateSelect("WHERE task_id = ? ORDER BY document_id, source_ordinal, unit_id, audit_pass, source_row_position"),
                featureCandidateRowMapper(), taskId);
    }

    /** Reads only first-pass accepted requirement candidates for the exact source unit. */
    public List<FeatureSourceCandidate> featureSourceCandidates(String taskId, String documentId, String unitId, int passNumber) {
        return jdbcTemplate.query(featureCandidateSelect("WHERE task_id = ? AND document_id = ? AND unit_id = ? AND audit_pass = ? ORDER BY source_row_position"),
                featureCandidateRowMapper(), taskId, documentId, unitId, passNumber);
    }

    /** Reads every persisted terminal conclusion with its exact task-owned candidate traceability. [Req-ID]: REQ-BFA-005 */
    public List<FeatureReviewConclusion> featureReviewConclusions(String taskId) {
        List<FeatureReviewConclusionHeader> headers = jdbcTemplate.query("""
                        SELECT id, conclusion_sequence, conclusion_type, explanation, evidence_text
                        FROM feature_review_conclusion WHERE task_id = ? ORDER BY conclusion_sequence
                        """, (resultSet, ignored) -> new FeatureReviewConclusionHeader(resultSet.getString("id"),
                resultSet.getInt("conclusion_sequence"), FeatureReviewConclusionType.valueOf(resultSet.getString("conclusion_type")),
                resultSet.getString("explanation"), resultSet.getString("evidence_text")), taskId);
        List<FeatureReviewConclusion> retained = new java.util.ArrayList<>(headers.size());
        for (FeatureReviewConclusionHeader header : headers) {
            List<String> candidateIds = jdbcTemplate.query("""
                            SELECT source_candidate_id FROM feature_review_conclusion_candidate
                            WHERE task_id = ? AND conclusion_id = ? ORDER BY source_candidate_id
                            """, (resultSet, ignored) -> resultSet.getString("source_candidate_id"), taskId, header.id());
            retained.add(new FeatureReviewConclusion(header.id(), header.sequence(), header.type(),
                    header.explanation(), header.evidenceText(), candidateIds));
        }
        return List.copyOf(retained);
    }

    /** Reads a completed immutable target set in its previously frozen order. [Req-ID]: REQ-BFA-005 */
    public List<FrozenFeatureTarget> frozenFeatureTargets(String taskId) {
        return jdbcTemplate.query("""
                        SELECT stable_feature_id, stable_sequence, feature_name, generation_eligible, source_summary
                        FROM frozen_feature_target WHERE task_id = ? ORDER BY stable_sequence
                        """, (resultSet, ignored) -> new FrozenFeatureTarget(resultSet.getString("stable_feature_id"),
                resultSet.getInt("stable_sequence"), resultSet.getString("feature_name"), resultSet.getBoolean("generation_eligible"),
                fromJson(resultSet.getString("source_summary"), FrozenFeatureSource.class)), taskId);
    }

    /**
     * Atomically persists the full immutable freeze, or accepts only an identical replay.
     *
     * [Req-ID]: REQ-BFA-005
     */
    public void persistFrozenFeatureTargets(String taskId, List<FrozenFeatureTarget> targets) {
        List<FrozenFeatureTarget> requested = List.copyOf(targets == null ? List.of() : targets);
        if (requested.isEmpty()) throw new IllegalArgumentException("Frozen feature targets must not be empty");
        transactionTemplate.executeWithoutResult(ignored -> {
            requireTaskExistsForUpdate(taskId);
            List<FrozenFeatureTarget> stored = frozenFeatureTargets(taskId);
            if (!stored.isEmpty()) {
                if (!stored.equals(requested)) {
                    throw new IllegalStateException("Frozen feature replay conflicts with retained task targets");
                }
                return;
            }
            requireFrozenTargetsMatchConclusions(taskId, requested);
            for (FrozenFeatureTarget target : requested) {
                jdbcTemplate.update("""
                                INSERT INTO frozen_feature_target (
                                    id, task_id, stable_feature_id, stable_sequence, feature_name, generation_eligible, source_summary)
                                VALUES (?, ?, ?, ?, ?, ?, ?)
                                """, UUID.nameUUIDFromBytes((taskId + "\u001f" + target.stableFeatureId())
                        .getBytes(StandardCharsets.UTF_8)).toString(), taskId, target.stableFeatureId(), target.stableSequence(),
                        target.featureName(), target.generationEligible(), asJson(target.source()));
            }
        });
    }

    /**
     * Saves the complete final conclusion set. The same set is idempotent; changed rows, unknown candidate IDs, or
     * partial coverage are rejected before any durable conclusion is accepted.
     *
     * [Req-ID]: REQ-BFA-003, REQ-BFA-004
     */
    public void persistFeatureReviewConclusions(String taskId, List<FeatureReviewConclusion> conclusions) {
        List<FeatureReviewConclusion> retained = List.copyOf(conclusions == null ? List.of() : conclusions);
        transactionTemplate.executeWithoutResult(ignored -> {
            Set<String> candidates = new java.util.LinkedHashSet<>(jdbcTemplate.query(
                    "SELECT id FROM feature_source_candidate WHERE task_id = ? FOR UPDATE",
                    (resultSet, row) -> resultSet.getString("id"), taskId));
            Set<String> covered = new java.util.LinkedHashSet<>();
            for (FeatureReviewConclusion conclusion : retained) {
                if (!candidates.containsAll(conclusion.candidateIds())) {
                    throw new IllegalStateException("Feature conclusion candidate coverage conflicts with retained task candidates");
                }
                for (String candidateId : conclusion.candidateIds()) {
                    if (!covered.add(candidateId)) {
                        throw new IllegalStateException("Feature conclusion candidate coverage conflicts with retained task candidates");
                    }
                }
                persistFeatureReviewConclusion(taskId, conclusion);
            }
            if (!covered.equals(candidates)) {
                throw new IllegalStateException("Feature conclusions must cover every retained candidate exactly once");
            }
        });
    }

    /** Returns task-owned audit and reconciliation counts used by the completion gate. */
    public FeatureAuditCounts featureAuditCounts(String taskId) {
        return jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) AS total_work,
                               SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END) AS completed_work,
                               SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed_work
                        FROM material_audit_work WHERE task_id = ?
                        """, (resultSet, ignored) -> new FeatureAuditCounts(
                resultSet.getInt("total_work"), resultSet.getInt("completed_work"), resultSet.getInt("failed_work"),
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM feature_source_candidate WHERE task_id = ?", Integer.class, taskId),
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM feature_review_conclusion WHERE task_id = ?", Integer.class, taskId),
                jdbcTemplate.queryForObject("""
                                SELECT COUNT(*) FROM feature_review_conclusion_candidate
                                WHERE task_id = ?
                                """, Integer.class, taskId)), taskId);
    }

    public Optional<String> findTaskIdByIdempotencyKey(String idempotencyKey) {
        return jdbcTemplate.query("SELECT id FROM generation_task WHERE idempotency_key = ?",
                (resultSet, ignored) -> resultSet.getString("id"), idempotencyKey).stream().findFirst();
    }

    public TaskCreation createTaskIfAbsent(
            String taskId,
            List<PlannedBatch> plannedBatches,
            CreateGenerationTaskRequest request,
            String idempotencyKey) {
        return transactionTemplate.execute(ignored -> {
            try {
                insertTaskAndV2Scope(taskId, request, idempotencyKey);
            } catch (DuplicateKeyException duplicate) {
                return new TaskCreation(findTaskIdByIdempotencyKey(idempotencyKey)
                        .orElseThrow(() -> duplicate), false);
            }
            for (int index = 0; index < plannedBatches.size(); index++) {
                PlannedBatch plannedBatch = plannedBatches.get(index);
                createBatch(plannedBatch.batchId(), taskId, plannedBatch.featureId(), index + 1);
                createAttempt(plannedBatch.attemptId(), plannedBatch.batchId());
            }
            return new TaskCreation(taskId, true);
        });
    }

    private record MaterialInventorySummary(
            String documentId, int totalUnits, boolean complete, int persistedUnits,
            int distinctOrdinals, int firstOrdinal, int lastOrdinal) {
        private boolean isCompleteAndContiguous() {
            return complete && persistedUnits == totalUnits && distinctOrdinals == totalUnits
                    && ((totalUnits == 0 && firstOrdinal == 0 && lastOrdinal == 0)
                    || (totalUnits > 0 && firstOrdinal == 1 && lastOrdinal == totalUnits));
        }
    }

    private record StagedMaterialDocumentRow(
            String documentId, String knowledgeId, String documentRole, int totalUnits, boolean complete) {
        private boolean matches(MaterialInventoryPage page) {
            return documentId.equals(page.documentId()) && knowledgeId.equals(page.knowledgeId())
                    && documentRole.equals(page.documentRole()) && totalUnits == page.totalUnits();
        }
    }

    private record MaterialUnitStats(
            int persistedUnits, int distinctOrdinals, int firstOrdinal, int lastOrdinal, int roleMismatches) {
        private boolean complete(int totalUnits) {
            return persistedUnits == totalUnits && distinctOrdinals == totalUnits && roleMismatches == 0
                    && ((totalUnits == 0 && firstOrdinal == 0 && lastOrdinal == 0)
                    || (totalUnits > 0 && firstOrdinal == 1 && lastOrdinal == totalUnits));
        }
    }

    private record FormalMaterialSummary(String documentId, String documentRole, int totalUnits, boolean complete,
            int persistedUnits, int firstOrdinal, int lastOrdinal) {}

    /**
     * Freezes a new task and its externally audited function scope in the same transaction.
     * Historical requests carry null versions and therefore create no V2 rows. [Req-ID]: REQ-TGV2-001, REQ-TGV2-008
     */
    private void insertTaskAndV2Scope(String taskId, CreateGenerationTaskRequest request, String idempotencyKey) {
        GenerationContractVersions versions = request.contractVersions();
        ApprovedFunctionScope approved = request.approvedFunctionScope();
        jdbcTemplate.update("""
                INSERT INTO generation_task
                (id, task_mode, status, idempotency_key, request_snapshot,
                 workflow_version, input_version, artifact_version, approved_scope_version)
                VALUES (?, ?, 'QUEUED', ?, ?, ?, ?, ?, ?)
                """, taskId, request.taskMode().name(), idempotencyKey, asJson(request),
                versions == null ? null : versions.workflowVersion(),
                versions == null ? null : versions.inputVersion(),
                versions == null ? null : versions.artifactVersion(),
                approved == null ? null : approved.scopeVersion());
        if (approved == null) return;
        for (int index = 0; index < approved.functions().size(); index++) {
            ApprovedFunctionScope.ApprovedFunction function = approved.functions().get(index);
            jdbcTemplate.update("""
                    INSERT INTO v2_approved_function
                    (task_id, function_key, stable_sequence, scope_version, name_text, path_text, description_text)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, taskId, function.functionKey(), index + 1, approved.scopeVersion(), function.name(),
                    function.path(), function.description());
        }
    }

    /** Reads the immutable V2 scope in its task-owned sequence; an empty list identifies a historical task. */
    public List<ApprovedFunctionScope.ApprovedFunction> approvedFunctions(String taskId) {
        return jdbcTemplate.query("""
                SELECT function_key, name_text, path_text, description_text
                FROM v2_approved_function WHERE task_id = ? ORDER BY stable_sequence
                """, (row, ignored) -> new ApprovedFunctionScope.ApprovedFunction(row.getString("function_key"),
                row.getString("name_text"), row.getString("path_text"), row.getString("description_text")), taskId);
    }

    /**
     * Plans only the task-owned eligible frozen targets, in their retained stable sequence.
     *
     * <p>The caller cannot rename, add, remove, or reorder model-discovered targets here. An identical replay
     * retains the existing batches rather than allocating duplicates.</p>
     *
     * [Req-ID]: REQ-CAG-001, REQ-BFA-005
     */
    public void planFrozenBatches(String taskId, CreateGenerationTaskRequest frozenRequest,
            List<FrozenFeatureTarget> frozenTargets, List<PlannedBatch> plannedBatches) {
        transactionTemplate.executeWithoutResult(ignored -> {
            requireTaskStatus(taskId, GenerationTaskStatus.AUDITING);
            List<FrozenFeatureTarget> retained = frozenFeatureTargets(taskId);
            if (!retained.equals(List.copyOf(frozenTargets == null ? List.of() : frozenTargets))) {
                throw new IllegalArgumentException("Batch plan must use the retained frozen target set exactly");
            }
            CreateGenerationTaskRequest expectedRequest = frozenRequest.withFrozenFeatures(retained);
            if (!expectedRequest.featureIds().equals(frozenRequest.featureIds())
                    || !expectedRequest.featurePaths().equals(frozenRequest.featurePaths())) {
                throw new IllegalArgumentException("Batch plan must preserve frozen feature IDs and names exactly");
            }
            List<String> expectedIds = retained.stream().filter(FrozenFeatureTarget::generationEligible)
                    .sorted(java.util.Comparator.comparingInt(FrozenFeatureTarget::stableSequence))
                    .map(FrozenFeatureTarget::stableFeatureId).toList();
            if (expectedIds.isEmpty()) {
                throw new IllegalArgumentException("All-ineligible frozen targets must not create a batch plan");
            }
            List<PlannedBatch> requested = List.copyOf(plannedBatches == null ? List.of() : plannedBatches);
            if (!requested.stream().map(PlannedBatch::featureId).toList().equals(expectedIds)) {
                throw new IllegalArgumentException("Batch plan must match eligible frozen target order exactly");
            }
            List<String> existingIds = jdbcTemplate.query("""
                            SELECT feature_id FROM generation_batch
                            WHERE task_id = ? ORDER BY batch_sequence
                            """, (resultSet, ignoredRow) -> resultSet.getString("feature_id"), taskId);
            if (!existingIds.isEmpty()) {
                if (!existingIds.equals(expectedIds) || !request(taskId).equals(frozenRequest)) {
                    throw new IllegalStateException("Frozen batch replay conflicts with the retained task plan");
                }
                return;
            }
            jdbcTemplate.update("UPDATE generation_task SET request_snapshot = ? WHERE id = ?", asJson(frozenRequest), taskId);
            for (int index = 0; index < requested.size(); index++) {
                PlannedBatch batch = requested.get(index);
                createBatch(batch.batchId(), taskId, batch.featureId(), index + 1);
                createAttempt(batch.attemptId(), batch.batchId());
            }
        });
    }

    /** Records an audit-stage failure without fabricating a feature batch. [Req-ID]: REQ-CWR-002 */
    public void failAuditingTask(String taskId) {
        failAuditingTask(taskId, null);
    }

    /** Stores only a browser-safe audit failure summary; raw model content never crosses this boundary. */
    public void failAuditingTask(String taskId, String failureSummary) {
        requireTaskStatus(taskId, GenerationTaskStatus.AUDITING);
        jdbcTemplate.update("UPDATE generation_task SET result_snapshot = ? WHERE id = ?",
                asJson(new TaskFailureSnapshot(safeTaskFailureSummary(failureSummary))), taskId);
        transitionTask(taskId, GenerationTaskStatus.FAILED);
        clearArtifactMetadata(taskId);
    }

    /**
     * Ends a fully audited ALL task when every retained frozen target is explicitly ineligible.
     *
     * <p>This is deliberately distinct from discovery failure: material traversal and audit coverage must already
     * be complete, no generation batch may exist, and the task is marked partial without a fabricated artifact.
     * The broader finalisation/export policy remains owned by the later completion slice.</p>
     *
     * [Req-ID]: REQ-CAG-001, REQ-BFA-005
     */
    public void finishAllFrozenFeaturesIneligible(String taskId, RequirementScope requirementScope) {
        transactionTemplate.executeWithoutResult(ignored -> {
            requireTaskStatus(taskId, GenerationTaskStatus.AUDITING);
            if (!hasCompleteAllAuditAndFreeze(taskId, requirementScope)) {
                throw new IllegalStateException("Cannot finish an ALL task before material traversal, audit, and freeze are complete");
            }
            List<FrozenFeatureTarget> targets = frozenFeatureTargets(taskId);
            if (targets.isEmpty() || targets.stream().anyMatch(FrozenFeatureTarget::generationEligible)) {
                throw new IllegalStateException("Only an all-ineligible frozen target set may finish without batches");
            }
            Integer batches = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM generation_batch WHERE task_id = ?", Integer.class, taskId);
            if (batches != null && batches != 0) {
                throw new IllegalStateException("All-ineligible tasks must not retain generation batches");
            }
            transitionTask(taskId, GenerationTaskStatus.PARTIAL);
            clearArtifactMetadata(taskId);
        });
    }

    /**
     * Temporary compatibility overload for callers that have not yet supplied the stable batch sequence.
     *
     * [Req-ID]: REQ-TSK-005
     */
    public void createBatch(String batchId, String taskId, String featureId) {
        createBatch(batchId, taskId, featureId, nextBatchSequence(taskId));
    }

    /**
     * [Req-ID]: REQ-TSK-005
     *
     * <p>Creates a batch at the caller-owned stable task sequence. New task creation uses this explicit
     * sequence so accepted Markdown accumulation never relies on feature names or insertion timing.</p>
     */
    public void createBatch(String batchId, String taskId, String featureId, int batchSequence) {
        if (batchSequence <= 0) {
            throw new IllegalArgumentException("Batch sequence must be positive");
        }
        jdbcTemplate.update("""
                        INSERT INTO generation_batch (id, task_id, feature_id, batch_sequence, status)
                        VALUES (?, ?, ?, ?, 'QUEUED')
                        """,
                batchId, taskId, featureId, batchSequence);
    }

    public void createAttempt(String attemptId, String batchId) {
        jdbcTemplate.update("""
                        INSERT INTO generation_attempt (id, batch_id, attempt_number, status)
                        VALUES (?, ?, 1, 'QUEUED')
                        """,
                attemptId, batchId);
    }

    public void transitionTask(String taskId, GenerationTaskStatus target) {
        GenerationTaskStatus current = taskStatus(taskId);
        current.requireTransitionTo(target);
        int changed = jdbcTemplate.update("UPDATE generation_task SET status = ? WHERE id = ? AND status = ?",
                target.name(), taskId, current.name());
        if (changed != 1) {
            throw new IllegalStateException("Task state changed concurrently: " + taskId);
        }
    }

    public void startBatch(String batchId, String attemptId) {
        transitionBatch(batchId, GenerationBatchStatus.RUNNING);
        jdbcTemplate.update("""
                        UPDATE generation_batch SET lease_owner = 'local-worker',
                            lease_expires_at = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 5 MINUTE)
                        WHERE id = ?
                        """, batchId);
        int changed = jdbcTemplate.update("UPDATE generation_attempt SET status = 'RUNNING' WHERE id = ? AND status = 'QUEUED'",
                attemptId);
        if (changed != 1) {
            throw new IllegalStateException("Attempt is not queued: " + attemptId);
        }
    }

    public int recoverExpiredBatchClaims() {
        jdbcTemplate.update("""
                        UPDATE generation_attempt a JOIN generation_batch b ON b.id = a.batch_id
                        SET a.status = 'QUEUED', a.completed_at = NULL
                        WHERE b.status = 'RUNNING' AND b.lease_expires_at IS NOT NULL
                          AND b.lease_expires_at < CURRENT_TIMESTAMP(6) AND a.status = 'RUNNING'
                        """);
        return jdbcTemplate.update("""
                        UPDATE generation_batch
                        SET status = 'QUEUED', lease_owner = NULL, lease_expires_at = NULL
                        WHERE status = 'RUNNING' AND lease_expires_at IS NOT NULL
                          AND lease_expires_at < CURRENT_TIMESTAMP(6)
                        """);
    }

    /**
     * Atomically recovers eligible legacy batches or one explicitly retryable structured work item.
     * Legacy batch recovery creates queued attempts in this transaction; structured recovery only queues the exact
     * work set so its next owner creates the attempt. Conditional updates and row locks keep every supported path
     * single-winner; the V2 fact-validation path may requeue multiple zero-write windows in one closed transaction.
     *
     * [Req-ID]: REQ-CAG-007, REQ-TGV2-011, REQ-TGV2-012, REQ-TGV2-013, REQ-TGV2-014, REQ-TGV2-015
     */
    public int retryFailedBatches(String taskId) {
        return retryTransactionTemplate.execute(status -> {
            StructuredRetryDecision decision = structuredRetryEligibility(taskId, true);
            // This V2 branch authorizes from complete attempt history after acquiring task/work locks. READ COMMITTED
            // gives that statement a fresh view; joining an older REPEATABLE READ snapshot must fail closed. Other
            // established retry branches retain their existing transaction semantics.
            // [Req-ID]: REQ-TGV2-013
            if ((decision.mutation() == StructuredRetryMutation.RECOVER_V2_ZERO_WRITE_TECHNICAL_FAILURE
                    || decision.mutation() == StructuredRetryMutation.RECOVER_V2_EXPECTED_RESULTS_REJECTION
                    || decision.mutation() == StructuredRetryMutation.RECOVER_V2_IDENTITY_LABEL_REJECTION)
                    && !Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                            TransactionDefinition.ISOLATION_READ_COMMITTED)) {
                return 0;
            }
            if (hasStructuredWork(taskId)) {
                return decision.eligibility().canRetry()
                        && retryExplicitStructuredWorkInTransaction(taskId, decision) ? 1 : 0;
            }
            List<String> failedBatchIds = jdbcTemplate.query("""
                            SELECT b.id FROM generation_batch b
                            JOIN generation_attempt a ON a.batch_id = b.id
                            WHERE b.task_id = ? AND b.status = 'FAILED'
                              AND a.attempt_number = (SELECT MAX(latest.attempt_number)
                                  FROM generation_attempt latest WHERE latest.batch_id = b.id)
                              AND a.retryable = TRUE AND a.attempt_number < ?
                            ORDER BY b.batch_sequence
                            """, (resultSet, ignoredResult) -> resultSet.getString("id"), taskId, MAX_ATTEMPTS);
            int retriedBatches = 0;
            for (String batchId : failedBatchIds) {
                int batchChanged = jdbcTemplate.update(
                        "UPDATE generation_batch SET status = 'QUEUED' WHERE id = ? AND status = 'FAILED'", batchId);
                if (batchChanged != 1) {
                    continue;
                }
                jdbcTemplate.update("""
                                INSERT INTO generation_attempt (id, batch_id, attempt_number, status)
                                VALUES (UUID(), ?, (SELECT COALESCE(MAX(attempt_number), 0) + 1 FROM generation_attempt existing WHERE existing.batch_id = ?), 'QUEUED')
                                """, batchId, batchId);
                retriedBatches++;
            }
            if (retriedBatches > 0) {
                GenerationTaskStatus current = taskStatus(taskId);
                if (current == GenerationTaskStatus.PARTIAL || current == GenerationTaskStatus.FAILED) {
                    clearArtifactMetadata(taskId);
                    transitionTask(taskId, GenerationTaskStatus.QUEUED);
                }
            }
            if (retriedBatches > 0) return retriedBatches;
            if (taskStatus(taskId) != GenerationTaskStatus.FAILED || !isUnbatchedAllTask(taskId)) return 0;
            jdbcTemplate.update("UPDATE generation_task SET result_snapshot = NULL WHERE id = ? AND status = 'FAILED'", taskId);
            transitionTask(taskId, GenerationTaskStatus.QUEUED);
            return 1;
        });
    }

    /**
     * Returns the current advisory eligibility for an explicit user-triggered structured retry.
     *
     * <p>The POST repeats this decision while holding the task row lock; callers must not treat this read as
     * authorization.</p>
     *
     * [Req-ID]: REQ-ESR-001, REQ-ESR-004, REQ-TGV2-011, REQ-TGV2-012, REQ-TGV2-014, REQ-TGV2-015
     */
    public StructuredRetryEligibility structuredRetryEligibility(String taskId) {
        return structuredRetryEligibility(taskId, false).eligibility();
    }

    private boolean retryExplicitStructuredWorkInTransaction(String taskId, StructuredRetryDecision decision) {
        if (decision.mutation() == StructuredRetryMutation.RECOVER_V2_ATOMICITY_REJECTION) {
            return recoverV2AtomicityRejectedTask(taskId);
        }
        if (decision.mutation() == StructuredRetryMutation.RECOVER_V2_DIRECT_EVIDENCE_REJECTION) {
            return recoverV2DirectEvidenceRejectedTask(taskId);
        }
        if (decision.mutation() == StructuredRetryMutation.RECOVER_V2_ZERO_WRITE_TECHNICAL_FAILURE) {
            return recoverV2ZeroWriteTechnicalFailureTask(taskId);
        }
        if (decision.mutation() == StructuredRetryMutation.RECOVER_V2_EXPECTED_RESULTS_REJECTION) {
            return recoverV2ExpectedResultsRejectedTask(taskId);
        }
        if (decision.mutation() == StructuredRetryMutation.RECOVER_V2_IDENTITY_LABEL_REJECTION) {
            return recoverV2IdentityLabelRejectedTask(taskId);
        }
        int workChanged = switch (decision.mutation()) {
            case REQUEUE_FAILED_WORK -> jdbcTemplate.update("""
                        UPDATE structured_generation_work_item
                        SET status = 'QUEUED', lease_owner = NULL, lease_expires_at = NULL,
                            validation_error_code = NULL, validation_error_path = NULL, validation_error_message = NULL
                        WHERE id = ? AND task_id = ? AND status = 'FAILED' AND accepted_result_sha256 IS NULL
                        """, decision.workItemId(), taskId);
            case REBUILD_INVALID_RECONCILIATION_STAGING -> rebuildInvalidReconciliationStaging(
                    taskId, decision.workItemId());
            case RESUME_QUEUED_RESIDUE, RESUME_EXPIRED_RUNNING_RESIDUE, RESUME_STAGE_GAP -> 1;
            case RECOVER_V2_ATOMICITY_REJECTION, RECOVER_V2_DIRECT_EVIDENCE_REJECTION,
                    RECOVER_V2_ZERO_WRITE_TECHNICAL_FAILURE, RECOVER_V2_EXPECTED_RESULTS_REJECTION,
                    RECOVER_V2_IDENTITY_LABEL_REJECTION ->
                    throw new IllegalStateException("V2 fact recovery must use its exact mutation");
        };
        int taskChanged = decision.mutation() == StructuredRetryMutation.RESUME_QUEUED_RESIDUE
                        || decision.mutation() == StructuredRetryMutation.RESUME_EXPIRED_RUNNING_RESIDUE
                ? jdbcTemplate.update("""
                        UPDATE generation_task
                        SET status = 'QUEUED', structured_processing_status = 'PENDING',
                            structured_coverage_status = 'PENDING', validation_error_code = NULL,
                            validation_error_path = NULL, validation_error_message = NULL
                        WHERE id = ? AND status = 'FAILED'
                        """, taskId)
                : jdbcTemplate.update("""
                        UPDATE generation_task
                        SET status = 'QUEUED', structured_processing_status = 'PENDING', structured_coverage_status = 'PENDING',
                            result_snapshot = NULL, artifact_id = NULL, artifact_sha256 = NULL, artifact_path = NULL,
                            validation_error_code = NULL, validation_error_path = NULL, validation_error_message = NULL
                        WHERE id = ? AND status = 'FAILED'
                        """, taskId);
        if (workChanged != 1 || taskChanged != 1) {
            throw new IllegalStateException("Explicit structured retry did not update exactly one task and recovery target");
        }
        return true;
    }

    private StructuredRetryDecision structuredRetryEligibility(String taskId, boolean lockTask) {
        List<RetryTaskRow> tasks = jdbcTemplate.query("""
                        SELECT task_mode, status, request_snapshot, result_snapshot, cancellation_requested_at,
                               validation_error_code, validation_error_path, validation_error_message,
                               structured_processing_status, structured_coverage_status,
                               workflow_version, input_version, artifact_version,
                               artifact_id, artifact_sha256, artifact_path
                        FROM generation_task WHERE id = ?%s
                        """.formatted(lockTask ? " FOR UPDATE" : ""),
                (row, ignored) -> new RetryTaskRow(row.getString("task_mode"), row.getString("status"),
                        row.getString("request_snapshot"), row.getString("result_snapshot"),
                        row.getTimestamp("cancellation_requested_at") != null,
                        row.getString("validation_error_code"), row.getString("validation_error_path"),
                        row.getString("validation_error_message"),
                        row.getString("structured_processing_status"), row.getString("structured_coverage_status"),
                        row.getString("workflow_version"), row.getString("input_version"),
                        row.getString("artifact_version"), row.getString("artifact_id"),
                        row.getString("artifact_sha256"), row.getString("artifact_path")), taskId);
        if (tasks.isEmpty()) throw new GenerationTaskNotFoundException(taskId);
        RetryTaskRow task = tasks.get(0);
        if (!GenerationTaskMode.ALL.name().equals(task.taskMode())) {
            return unavailableRetry("该任务不支持结构化断点重试");
        }
        boolean v2FactValidationRecoveryTask = isV2FactValidationRecoveryTask(task);
        boolean v2ZeroWriteTechnicalRecoveryTask = isV2ZeroWriteTechnicalRecoveryTask(task);
        boolean v2ExpectedResultsRecoveryTask = isV2ExpectedResultsRecoveryTask(task);
        boolean v2IdentityLabelRecoveryTask = isV2IdentityLabelRecoveryTask(task);
        if (!GenerationTaskStatus.FAILED.name().equals(task.status())
                && !v2FactValidationRecoveryTask
                && !v2ZeroWriteTechnicalRecoveryTask
                && !v2ExpectedResultsRecoveryTask
                && !v2IdentityLabelRecoveryTask) {
            return unavailableRetry("任务当前状态不允许重试");
        }
        if (task.cancellationRequested()) return unavailableRetry("任务已取消或正在取消，不能重试");
        List<RetryWorkRow> unfinished = jdbcTemplate.query("""
                        SELECT work.id, work.identity_key, work.status, work.coverage_status,
                               work.accepted_result_sha256, work.skill_name,
                               work.operation_name, work.function_key, work.test_point_key,
                               work.ordinal_start, work.ordinal_end,
                               work.validation_error_code, work.validation_error_path, work.validation_error_message,
                               JSON_TYPE(work.allowed_evidence_keys_json) AS evidence_json_type,
                               JSON_LENGTH(work.allowed_evidence_keys_json) AS evidence_key_count,
                               JSON_UNQUOTE(JSON_EXTRACT(work.allowed_evidence_keys_json, '$[0]')) AS evidence_key,
                               (work.lease_owner IS NOT NULL OR work.lease_expires_at IS NOT NULL) AS has_lease,
                               (work.lease_owner IS NOT NULL AND work.lease_expires_at IS NOT NULL) AS has_complete_lease,
                               work.lease_expires_at,
                               EXISTS (
                                   SELECT 1 FROM structured_generation_attempt running_attempt
                                   WHERE running_attempt.work_item_id = work.id
                                      AND running_attempt.status = 'RUNNING'
                               ) AS has_running_attempt,
                               (SELECT COUNT(*) FROM structured_generation_attempt running_attempt
                                WHERE running_attempt.work_item_id = work.id
                                  AND running_attempt.status = 'RUNNING') AS running_attempt_count,
                               (SELECT COUNT(*) FROM structured_generation_attempt any_attempt
                                WHERE any_attempt.work_item_id = work.id) AS attempt_count
                        FROM structured_generation_work_item work
                        WHERE work.task_id = ? AND work.status NOT IN ('COMPLETED', 'SPLIT', 'SUPERSEDED')
                        ORDER BY work.created_at, work.id%s
                        """.formatted(lockTask ? " FOR UPDATE" : ""),
                (row, ignored) -> new RetryWorkRow(row.getString("id"), row.getString("identity_key"),
                        row.getString("status"), row.getString("coverage_status"),
                        row.getString("accepted_result_sha256"), row.getString("skill_name"),
                         row.getString("operation_name"), row.getString("function_key"),
                         row.getString("test_point_key"),
                         nullableInteger(row, "ordinal_start"),
                         nullableInteger(row, "ordinal_end"), row.getString("validation_error_code"),
                         row.getString("validation_error_path"), row.getString("validation_error_message"),
                         row.getString("evidence_json_type"),
                         row.getInt("evidence_key_count"), row.getString("evidence_key"),
                         row.getBoolean("has_lease"), row.getBoolean("has_complete_lease"),
                         row.getTimestamp("lease_expires_at") == null ? null
                                 : row.getTimestamp("lease_expires_at").toInstant(),
                          row.getBoolean("has_running_attempt"),
                          row.getInt("running_attempt_count"), row.getInt("attempt_count")), taskId);
        // The mutation path locks task then unfinished work before its first consistent read. This prevents a
        // REPEATABLE READ snapshot from hiding a business row committed while the work lock was still pending.
        // [Req-ID]: REQ-ESR-002, REQ-ESR-012, REQ-ESR-013
        if (hasExecutionSlot(taskId, lockTask)) {
            return unavailableRetry("任务仍在处理中，请稍后刷新");
        }
        if (task.requestSnapshot() == null || task.requestSnapshot().isBlank()
                || "{}".equals(task.requestSnapshot().strip())
                || !hasCompleteFrozenInventory(taskId, lockTask)) {
            return unavailableRetry("任务材料范围不完整，不能重试");
        }
        if (v2FactValidationRecoveryTask) {
            return v2FactValidationRecoveryDecision(taskId, task, unfinished, lockTask);
        }
        // Identity-label failures share the same PARTIAL artifact envelope as technical zero-write failures, but they
        // intentionally retain completed fact publications. Select the more specific business-validation branch first.
        // [Req-ID]: REQ-TGV2-015
        if (v2IdentityLabelRecoveryTask) {
            return v2IdentityLabelRecoveryDecision(taskId, task, unfinished, lockTask);
        }
        if (v2ZeroWriteTechnicalRecoveryTask) {
            return v2ZeroWriteTechnicalRecoveryDecision(taskId, task, unfinished, lockTask);
        }
        if (v2ExpectedResultsRecoveryTask) {
            return v2ExpectedResultsRecoveryDecision(taskId, task, unfinished, lockTask);
        }
        if (unfinished.isEmpty()) {
            if (isSafeReconciliationStageGap(taskId)) {
                return new StructuredRetryDecision(
                        StructuredRetryEligibility.eligible(), null, StructuredRetryMutation.RESUME_STAGE_GAP);
            }
            return unavailableRetry("失败处理项数量不符合安全重试条件");
        }
        RetryWorkRow work = null;
        RetryWorkRow expiredRunningResidue = null;
        for (RetryWorkRow candidate : unfinished) {
            if ("RUNNING".equals(candidate.status())) {
                if (expiredRunningResidue != null || !isExpiredRunningReconciliationResidue(task, candidate)) {
                    return unavailableRetry("失败处理项数量不符合安全重试条件");
                }
                expiredRunningResidue = candidate;
                continue;
            }
            if (candidate.acceptedResultSha256() != null
                    || candidate.hasLease()
                    || candidate.hasRunningAttempt()
                    || !("FAILED".equals(candidate.status()) || "QUEUED".equals(candidate.status()))) {
                return unavailableRetry("失败处理项数量不符合安全重试条件");
            }
            if ("FAILED".equals(candidate.status())) {
                if (work != null) return unavailableRetry("失败处理项数量不符合安全重试条件");
                work = candidate;
            }
        }
        if (expiredRunningResidue != null) {
            if (unfinished.size() != 1
                    || unfinishedWorkOwnedBusinessRowCount(taskId, lockTask) != 0
                    || hasPublishedReconciliationOrDownstreamRows(taskId, lockTask)) {
                return unavailableRetry("失败处理项已存在部分结果，不能安全重试");
            }
            List<RetryAttemptRow> attempts = latestRetryAttempts(expiredRunningResidue.id(), lockTask);
            if (attempts.size() != 1 || !"RUNNING".equals(attempts.get(0).status())) {
                return unavailableRetry("失败处理项数量不符合安全重试条件");
            }
            return new StructuredRetryDecision(StructuredRetryEligibility.eligible(), expiredRunningResidue.id(),
                    StructuredRetryMutation.RESUME_EXPIRED_RUNNING_RESIDUE);
        }
        if (work == null) return queuedResidueRetryDecision(taskId, unfinished, lockTask);
        List<RetryAttemptRow> latestAttempts = latestRetryAttempts(work.id(), lockTask);
        if (unfinished.size() == 1
                && latestAttempts.size() == 1
                && isInvalidCompletedReconciliationStagingRetry(
                        taskId, task, work, latestAttempts.get(0), lockTask)) {
            return new StructuredRetryDecision(StructuredRetryEligibility.eligible(), work.id(),
                    StructuredRetryMutation.REBUILD_INVALID_RECONCILIATION_STAGING);
        }
        if (unfinished.size() == 1
                && latestAttempts.size() == 1
                && isZeroWriteReconciliationModelFailureRetry(
                        taskId, work, latestAttempts.get(0), lockTask)) {
            return new StructuredRetryDecision(
                    StructuredRetryEligibility.eligible(), work.id(), StructuredRetryMutation.REQUEUE_FAILED_WORK);
        }
        if (unfinished.size() == 1
                && latestAttempts.size() == 1
                && isZeroWriteReconciliationStructuralFailureRetry(
                        taskId, work, latestAttempts.get(0), lockTask)) {
            return new StructuredRetryDecision(
                    StructuredRetryEligibility.eligible(), work.id(), StructuredRetryMutation.REQUEUE_FAILED_WORK);
        }
        // A task-level invalid-run diagnosis belongs exclusively to the exact rebuild branch. Falling through to
        // a broader historical retry rule could retain the invalid pages or ignore an ambiguous second work.
        if (isInvalidReconciliationRunDiagnostic(task)) {
            return unavailableRetry("功能核对暂存状态不符合安全重建条件");
        }
        if (latestAttempts.size() != 1
                || !isExplicitlyRetryableFailure(taskId, work, latestAttempts.get(0), lockTask)) {
            return unavailableRetry("该失败类型不能通过此操作重试");
        }
        // Recursive review splitting can leave unattempted QUEUED siblings beside the only FAILED leaf. All unfinished
        // leaves must still be zero-write before the user action may resume the same frozen tree.
        if (unfinishedWorkOwnedBusinessRowCount(taskId, lockTask) != 0) {
            return unavailableRetry("失败处理项已存在部分结果，不能安全重试");
        }
        return new StructuredRetryDecision(
                StructuredRetryEligibility.eligible(), work.id(), StructuredRetryMutation.REQUEUE_FAILED_WORK);
    }

    /**
     * Recognizes only the exact residue left after a previous explicit retry queued one work but the coordinator
     * failed before claiming it. The retry transaction may then change only the owning task execution state.
     *
     * [Req-ID]: REQ-ESR-007
     */
    private StructuredRetryDecision queuedResidueRetryDecision(
            String taskId, List<RetryWorkRow> unfinished, boolean lockTask) {
        if (unfinished.size() != 1) {
            return unavailableRetry("失败处理项数量不符合安全重试条件");
        }
        RetryWorkRow queued = unfinished.get(0);
        if (!"QUEUED".equals(queued.status())
                || queued.acceptedResultSha256() != null
                || queued.hasLease()
                || queued.hasRunningAttempt()
                || unfinishedWorkOwnedBusinessRowCount(taskId, lockTask) != 0) {
            return unavailableRetry("失败处理项数量不符合安全重试条件");
        }
        List<RetryAttemptRow> latestAttempts = latestRetryAttempts(queued.id(), lockTask);
        if (latestAttempts.size() != 1
                || !isExplicitlyRetryableFailure(taskId, queued, latestAttempts.get(0), lockTask)) {
            return unavailableRetry("该历史失败类型不能通过此操作恢复");
        }
        return new StructuredRetryDecision(
                StructuredRetryEligibility.eligible(), queued.id(), StructuredRetryMutation.RESUME_QUEUED_RESIDUE);
    }

    private List<RetryAttemptRow> latestRetryAttempts(String workItemId, boolean lockTask) {
        return jdbcTemplate.query("""
                        SELECT status, failure_type, validation_error_code, validation_error_path,
                               validation_error_message
                        FROM structured_generation_attempt
                        WHERE work_item_id = ? ORDER BY attempt_number DESC LIMIT 1%s
                        """.formatted(lockTask ? " FOR UPDATE" : ""),
                (row, ignored) -> new RetryAttemptRow(
                        row.getString("status"), row.getString("failure_type"),
                        row.getString("validation_error_code"), row.getString("validation_error_path"),
                        row.getString("validation_error_message")), workItemId);
    }

    /**
     * Recognizes only the pre-reconciliation gap left after all upstream work was durably accepted.
     * The exact zero-downstream shape lets an explicit user action resume planning without making a
     * failed model work retryable or accepting duplicate business rows.
     */
    private boolean isSafeReconciliationStageGap(String taskId) {
        int completed = count("SELECT COUNT(*) FROM structured_generation_work_item WHERE task_id = ? AND status = 'COMPLETED'", taskId);
        int allWorks = count("SELECT COUNT(*) FROM structured_generation_work_item WHERE task_id = ? AND status <> 'SPLIT'", taskId);
        if (completed == 0 || completed != allWorks) return false;
        if (count("""
                SELECT COUNT(*) FROM structured_generation_work_item
                WHERE task_id = ? AND operation_name IN ('FEATURE_SCOPE_RECONCILIATION','FEATURE_SCOPE_RECONCILIATION_V2')
                """, taskId) != 0) return false;
        if (count("SELECT COUNT(*) FROM structured_function_list_item WHERE task_id = ?", taskId) == 0
                || count("SELECT COUNT(*) FROM structured_requirement_fact WHERE task_id = ?", taskId) == 0) return false;
        return count("SELECT COUNT(*) FROM structured_feature_reconciliation WHERE task_id = ?", taskId) == 0
                && count("SELECT COUNT(*) FROM structured_reconciliation_run WHERE task_id = ?", taskId) == 0
                && count("SELECT COUNT(*) FROM structured_reconciliation_source_terminal WHERE task_id = ?", taskId) == 0
                && count("SELECT COUNT(*) FROM structured_test_point WHERE task_id = ?", taskId) == 0
                && count("SELECT COUNT(*) FROM structured_test_case WHERE task_id = ?", taskId) == 0;
    }

    /**
     * Matches only the expired V2 claim residue produced by a coordinator lease-loss failure.
     * The retry transaction deliberately leaves the expired claim untouched; the existing targeted claimer owns
     * the atomic old-attempt failure and next-attempt creation after the task is queued.
     *
     * [Req-ID]: REQ-ESR-010
     */
    private boolean isExpiredRunningReconciliationResidue(RetryTaskRow task, RetryWorkRow work) {
        return StructuredValidationFailure.Code.STRUCTURED_COORDINATOR_CONCURRENCY_FAILURE.name()
                        .equals(task.validationErrorCode())
                && StructuredCoordinatorFailure.Stage.RECONCILIATION.path().equals(task.validationErrorPath())
                && "feature-scope-reconciliation".equals(work.skillName())
                && "FEATURE_SCOPE_RECONCILIATION_V2".equals(work.operationName())
                && work.acceptedResultSha256() == null
                && work.hasCompleteLease()
                // The JDBC driver resolves the stored timestamp to an absolute Instant. Comparing it here avoids
                // treating a database session's time zone as the application's lease clock.
                && work.leaseExpiresAt() != null
                && !work.leaseExpiresAt().isAfter(Instant.now())
                && work.hasRunningAttempt()
                && work.runningAttemptCount() == 1
                && work.attemptCount() == 1;
    }

    private boolean hasPublishedReconciliationOrDownstreamRows(String taskId, boolean lockRows) {
        if (lockRows) {
            return hasTaskOwnedRowCurrent("structured_feature_reconciliation", taskId)
                    || hasTaskOwnedRowCurrent("structured_reconciliation_source_terminal", taskId)
                    || hasTaskOwnedRowCurrent("structured_test_point", taskId)
                    || hasTaskOwnedRowCurrent("structured_test_case", taskId)
                    || !jdbcTemplate.queryForList("""
                            SELECT step.work_item_id FROM structured_test_case_step step
                            JOIN structured_generation_work_item work ON work.id = step.work_item_id
                            WHERE work.task_id = ? LIMIT 1 FOR UPDATE
                            """, String.class, taskId).isEmpty();
        }
        return count("SELECT COUNT(*) FROM structured_feature_reconciliation WHERE task_id = ?", taskId) != 0
                || count("SELECT COUNT(*) FROM structured_reconciliation_source_terminal WHERE task_id = ?", taskId) != 0
                || count("SELECT COUNT(*) FROM structured_test_point WHERE task_id = ?", taskId) != 0
                || count("SELECT COUNT(*) FROM structured_test_case WHERE task_id = ?", taskId) != 0
                || count("""
                        SELECT COUNT(*) FROM structured_test_case_step step
                        JOIN structured_generation_work_item work ON work.id = step.work_item_id
                        WHERE work.task_id = ?
                        """, taskId) != 0;
    }

    /**
     * Checks the exact task-level V2 closure failure whose completed staging graph must be discarded before retry.
     * The locked POST path locks the run and every page before deletion, so a concurrent request cannot rebuild a
     * different reconciliation state after advisory eligibility was read.
     *
     * [Req-ID]: REQ-ESR-011
     */
    private boolean isInvalidCompletedReconciliationStagingRetry(
            String taskId, RetryTaskRow task, RetryWorkRow work, RetryAttemptRow attempt, boolean lockRows) {
        String requiredCode = StructuredValidationFailure.Code.RECONCILIATION_V2_RESULT_INVALID.name();
        String requiredPath = "$.reconciliation_run";
        if (!requiredCode.equals(task.validationErrorCode())
                || !requiredPath.equals(task.validationErrorPath())
                || !"FAILED".equals(work.status())
                || !"feature-scope-reconciliation".equals(work.skillName())
                || !"FEATURE_SCOPE_RECONCILIATION_V2".equals(work.operationName())
                || !requiredCode.equals(work.validationErrorCode())
                || !requiredPath.equals(work.validationErrorPath())
                || !"FAILED".equals(attempt.status())
                || !"business_validation_failed".equals(attempt.failureType())
                || !requiredCode.equals(attempt.validationErrorCode())
                || !requiredPath.equals(attempt.validationErrorPath())
                || work.acceptedResultSha256() != null
                || work.hasLease()
                || work.hasRunningAttempt()
                || unfinishedWorkOwnedBusinessRowCount(taskId, lockRows) != 0
                || hasPublishedReconciliationOrDownstreamRows(taskId, lockRows)
                || count("""
                        SELECT COUNT(*) FROM generation_task
                        WHERE id = ? AND (artifact_id IS NOT NULL OR artifact_sha256 IS NOT NULL OR artifact_path IS NOT NULL)
                        """, taskId) != 0) {
            return false;
        }
        // A task-level closure failure is safe to rebuild only when the target work owns the task's sole run.
        // Locking every task run prevents a completed sibling from introducing a second graph between eligibility
        // and deletion. [Req-ID]: REQ-ESR-011
        List<RetryReconciliationRunRow> runs = jdbcTemplate.query("""
                        SELECT work_item_id, run_key, catalog_sha256, status, accepted_result_sha256
                        FROM structured_reconciliation_run
                        WHERE task_id = ? ORDER BY work_item_id%s
                        """.formatted(lockRows ? " FOR UPDATE" : ""),
                (row, ignored) -> new RetryReconciliationRunRow(
                        row.getString("work_item_id"), row.getString("run_key"), row.getString("catalog_sha256"),
                        row.getString("status"), row.getString("accepted_result_sha256")),
                taskId);
        if (runs.size() != 1 || !work.id().equals(runs.get(0).workItemId())
                || !"STAGING".equals(runs.get(0).status()) || runs.get(0).acceptedResultSha256() != null) {
            return false;
        }
        RetryReconciliationRunRow run = runs.get(0);
        List<RetryReconciliationPageRow> pages = jdbcTemplate.query("""
                        SELECT page_key, run_key, catalog_sha256, parent_page_key, status,
                               completed_owner_source_refs_json, result_sha256,
                               (completed_at IS NOT NULL) AS has_completed_at
                        FROM structured_reconciliation_page_stage
                        WHERE work_item_id = ? ORDER BY page_key%s
                        """.formatted(lockRows ? " FOR UPDATE" : ""),
                (row, ignored) -> new RetryReconciliationPageRow(
                        row.getString("page_key"), row.getString("run_key"), row.getString("catalog_sha256"),
                        row.getString("parent_page_key"), row.getString("status"),
                        row.getString("completed_owner_source_refs_json"), row.getString("result_sha256"),
                        row.getBoolean("has_completed_at")),
                work.id());
        return !pages.isEmpty() && pages.stream().allMatch(page -> "COMPLETED".equals(page.status())
                && run.runKey().equals(page.runKey()) && run.catalogSha256().equals(page.catalogSha256()));
    }

    private boolean isInvalidReconciliationRunDiagnostic(RetryTaskRow task) {
        return StructuredValidationFailure.Code.RECONCILIATION_V2_RESULT_INVALID.name()
                        .equals(task.validationErrorCode())
                && "$.reconciliation_run".equals(task.validationErrorPath());
    }

    /**
     * Removes only the invalid run-owned staging graph and requeues the same failed work.
     * V15 foreign keys cascade page deletion to staged relations and bindings; affected-row checks keep this
     * operation fail-closed and let the surrounding transaction restore the complete graph on any mismatch.
     *
     * [Req-ID]: REQ-ESR-011
     */
    private int rebuildInvalidReconciliationStaging(String taskId, String workItemId) {
        int expectedPages = count(
                "SELECT COUNT(*) FROM structured_reconciliation_page_stage WHERE work_item_id = ?", workItemId);
        if (expectedPages < 1) {
            throw new IllegalStateException("Invalid reconciliation staging has no completed page to rebuild");
        }
        int deletedPages = jdbcTemplate.update(
                "DELETE FROM structured_reconciliation_page_stage WHERE work_item_id = ?", workItemId);
        if (deletedPages != expectedPages
                || count("SELECT COUNT(*) FROM structured_reconciliation_relation_stage WHERE work_item_id = ?", workItemId) != 0
                || count("SELECT COUNT(*) FROM structured_reconciliation_relation_stage_binding WHERE work_item_id = ?", workItemId) != 0) {
            throw new IllegalStateException("Invalid reconciliation staging cleanup did not preserve ownership");
        }
        if (jdbcTemplate.update("""
                DELETE FROM structured_reconciliation_run
                WHERE task_id = ? AND work_item_id = ? AND status = 'STAGING' AND accepted_result_sha256 IS NULL
                """, taskId, workItemId) != 1) {
            throw new IllegalStateException("Invalid reconciliation run changed during explicit retry");
        }
        return jdbcTemplate.update("""
                UPDATE structured_generation_work_item
                SET status = 'QUEUED', lease_owner = NULL, lease_expires_at = NULL,
                    validation_error_code = NULL, validation_error_path = NULL, validation_error_message = NULL
                WHERE id = ? AND task_id = ? AND status = 'FAILED' AND accepted_result_sha256 IS NULL
                """, workItemId, taskId);
    }

    /**
     * Recognizes the narrow V2 terminal shape where Java rejected every fact window for one fixed semantic-validation
     * code before publication and the only accepted downstream rows are the planner's no-fact fallbacks. No project
     * identity, material name, or cardinality is part of the decision. [Req-ID]: REQ-TGV2-011, REQ-TGV2-012
     */
    private StructuredRetryDecision v2FactValidationRecoveryDecision(
            String taskId, RetryTaskRow task, List<RetryWorkRow> unfinished, boolean lockRows) {
        String expectedCode = task.validationErrorCode();
        boolean directEvidence = StructuredValidationFailure.Code.FACT_DIRECT_EVIDENCE_UNSUPPORTED.name()
                .equals(expectedCode);
        if (unfinished.isEmpty() || unfinished.stream().anyMatch(work ->
                !"FAILED".equals(work.status())
                        || !"requirement-fact-extraction".equals(work.skillName())
                        || !"REQUIREMENT_FACT_EXTRACTION_V2".equals(work.operationName())
                        || work.acceptedResultSha256() != null
                        || work.hasLease() || work.hasRunningAttempt()
                        || !expectedCode.equals(work.validationErrorCode())
                        || !V2_FACT_STATEMENT_PATH.matcher(orDefault(work.validationErrorPath(), "")).matches())) {
            return unavailableRetry("V2 事实失败状态不符合安全恢复条件");
        }
        for (RetryWorkRow work : unfinished) {
            List<RetryAttemptRow> attempts = latestRetryAttempts(work.id(), lockRows);
            if (attempts.size() != 1 || !isRecoverableV2FactValidationFailure(attempts.get(0), expectedCode)
                    || (directEvidence && !hasMatchingStrictValidationDiagnostic(task, work, attempts.get(0)))) {
                return unavailableRetry("V2 事实失败状态不符合安全恢复条件");
            }
        }
        if (directEvidence) {
            return v2DirectEvidenceRecoveryDecision(taskId, unfinished, lockRows);
        }
        try {
            List<V2FallbackWorkRow> fallbacks = currentV2TestcaseWorks(taskId, lockRows);
            if (!isExactV2AtomicityWorkGraph(taskId, unfinished, fallbacks, lockRows)
                    || unfinishedWorkOwnedBusinessRowCount(taskId, lockRows) != 0
                    || hasCurrentV2FactPublication(taskId, lockRows)
                    || hasCurrentV2TestcaseRows(taskId, lockRows)) {
                return unavailableRetry("V2 事实失败已存在部分结果，不能安全恢复");
            }
            if (!isExactMissingFactFallbackSet(taskId, fallbacks, lockRows)) {
                return unavailableRetry("V2 旧降级结果不符合安全替换条件");
            }
        } catch (InvalidV2RecoverySnapshotException invalidSnapshot) {
            return unavailableRetry("V2 历史投影无法安全复验，不能恢复");
        }
        return new StructuredRetryDecision(StructuredRetryEligibility.eligible(), null,
                StructuredRetryMutation.RECOVER_V2_ATOMICITY_REJECTION);
    }

    /**
     * Keeps completed functions immutable while proving that every affected function owns only a zero-write failed
     * fact window and its validated no-fact fallback. [Req-ID]: REQ-TGV2-012
     */
    private StructuredRetryDecision v2DirectEvidenceRecoveryDecision(
            String taskId, List<RetryWorkRow> unfinished, boolean lockRows) {
        if (unfinished.stream().map(RetryWorkRow::functionKey).anyMatch(key -> !nonBlank(key))) {
            return unavailableRetry("V2 直接依据失败的功能身份不完整，不能安全恢复");
        }
        Set<String> affectedFunctions = unfinished.stream().map(RetryWorkRow::functionKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        V2RecoverySnapshot snapshot;
        try {
            snapshot = loadV2RecoverySnapshot(taskId, affectedFunctions, lockRows);
        } catch (InvalidV2RecoverySnapshotException invalidSnapshot) {
            return unavailableRetry("V2 历史投影无法安全复验，不能恢复");
        }
        for (RetryWorkRow work : unfinished) {
            List<RetryAttemptRow> attempts = snapshot.attemptsByWork().getOrDefault(work.id(), List.of());
            if (attempts.isEmpty() || !isRecoverableV2FactValidationFailure(
                    attempts.get(0), StructuredValidationFailure.Code.FACT_DIRECT_EVIDENCE_UNSUPPORTED.name())) {
                return unavailableRetry("V2 事实失败状态不符合安全恢复条件");
            }
        }
        Set<String> approvedFunctions = snapshot.functionsByKey().keySet();
        if (affectedFunctions.isEmpty() || !approvedFunctions.containsAll(affectedFunctions)
                || !isSafeV2DirectEvidenceWorkGraph(taskId, unfinished, affectedFunctions, snapshot)) {
            return unavailableRetry("V2 直接依据失败已存在部分结果，不能安全恢复");
        }
        List<V2FallbackWorkRow> currentTestcaseWorks = currentV2TestcaseWorks(snapshot);
        List<V2FallbackWorkRow> affectedFallbacks = currentTestcaseWorks.stream()
                .filter(row -> affectedFunctions.contains(row.functionKey())).toList();
        if (affectedFallbacks.size() != affectedFunctions.size()
                || currentTestcaseWorks.stream().anyMatch(row -> affectedFunctions.contains(row.functionKey())
                        && !V2GenerationPlanner.missingFormalFactPointKey(taskId, row.functionKey())
                                .equals(row.testPointKey()))
                || !isExactMissingFactFallbackSubset(
                        taskId, affectedFunctions, affectedFallbacks, lockRows, snapshot)) {
            return unavailableRetry("V2 直接依据失败的旧降级结果不符合安全替换条件");
        }
        return new StructuredRetryDecision(StructuredRetryEligibility.eligible(), null,
                StructuredRetryMutation.RECOVER_V2_DIRECT_EVIDENCE_REJECTION);
    }

    private static boolean isRecoverableV2FactValidationFailure(RetryAttemptRow attempt, String expectedCode) {
        return "FAILED".equals(attempt.status())
                && "business_validation_failed".equals(attempt.failureType())
                && expectedCode.equals(attempt.validationErrorCode())
                && V2_FACT_STATEMENT_PATH.matcher(orDefault(attempt.validationErrorPath(), "")).matches();
    }

    /**
     * Parses all three durable diagnostic layers through the same bounded catalog before an explicit recovery may
     * consume them. Invalid database text is reduced to an ineligible decision and never retained as an exception
     * cause or log value. Internal reason enums remain diagnostic only. [Req-ID]: REQ-TGV2-012
     */
    private static boolean hasMatchingStrictValidationDiagnostic(
            RetryTaskRow task, RetryWorkRow work, RetryAttemptRow attempt) {
        Optional<StoredValidationDiagnostic> taskDiagnostic = strictStoredValidationDiagnostic(
                task.validationErrorCode(), task.validationErrorPath(), task.validationErrorMessage());
        Optional<StoredValidationDiagnostic> workDiagnostic = strictStoredValidationDiagnostic(
                work.validationErrorCode(), work.validationErrorPath(), work.validationErrorMessage());
        Optional<StoredValidationDiagnostic> attemptDiagnostic = strictStoredValidationDiagnostic(
                attempt.validationErrorCode(), attempt.validationErrorPath(), attempt.validationErrorMessage());
        return taskDiagnostic.isPresent()
                && taskDiagnostic.equals(workDiagnostic)
                && taskDiagnostic.equals(attemptDiagnostic);
    }

    private static Optional<StoredValidationDiagnostic> strictStoredValidationDiagnostic(
            String code, String path, String message) {
        if (code == null || path == null || message == null) return Optional.empty();
        try {
            StructuredValidationFailure safe = StructuredValidationFailure.fromStored(
                    StructuredValidationFailure.Code.valueOf(code), path, message);
            return Optional.of(new StoredValidationDiagnostic(safe.code(), safe.path(), safe.storageMessage()));
        } catch (IllegalArgumentException exception) {
            // Untrusted stored text must fail closed without surviving in a cause, log, or reader-facing response.
            return Optional.empty();
        }
    }

    private static boolean isV2FactValidationRecoveryTask(RetryTaskRow task) {
        if (!StructuredProcessingStatus.FAILED.name().equals(task.processingStatus())
                || !GenerationContractVersions.V2.equals(task.workflowVersion())
                || !GenerationContractVersions.V2.equals(task.inputVersion())
                || !GenerationContractVersions.V2.equals(task.artifactVersion())
                || !isRecoverableV2FactValidationCode(task.validationErrorCode())
                || !V2_FACT_STATEMENT_PATH.matcher(orDefault(task.validationErrorPath(), "")).matches()) {
            return false;
        }
        boolean directEvidence = StructuredValidationFailure.Code.FACT_DIRECT_EVIDENCE_UNSUPPORTED.name()
                .equals(task.validationErrorCode());
        if ((!directEvidence && !StructuredCoverageStatus.UNABLE_TO_GENERATE.name().equals(task.coverageStatus()))
                || (directEvidence
                        && !StructuredCoverageStatus.UNABLE_TO_GENERATE.name().equals(task.coverageStatus())
                        && !StructuredCoverageStatus.PARTIAL.name().equals(task.coverageStatus()))) return false;
        if (GenerationTaskStatus.PARTIAL.name().equals(task.status())) {
            return nonBlank(task.artifactId()) && nonBlank(task.artifactSha256()) && nonBlank(task.artifactPath());
        }
        return GenerationTaskStatus.FAILED.name().equals(task.status())
                && task.artifactId() == null && task.artifactSha256() == null && task.artifactPath() == null;
    }

    private static boolean isRecoverableV2FactValidationCode(String code) {
        return StructuredValidationFailure.Code.FACT_ATOMICITY_INVALID.name().equals(code)
                || StructuredValidationFailure.Code.FACT_DIRECT_EVIDENCE_UNSUPPORTED.name().equals(code);
    }

    /**
     * Selects only the historical V2 terminal shape produced after the old validator rejected overall expectations.
     * The task-level coordinator diagnostic is intentionally different from the work-level business diagnostic.
     * [Req-ID]: REQ-TGV2-014
     */
    private static boolean isV2ExpectedResultsRecoveryTask(RetryTaskRow task) {
        return GenerationTaskStatus.FAILED.name().equals(task.status())
                && StructuredProcessingStatus.FAILED.name().equals(task.processingStatus())
                && StructuredCoverageStatus.PENDING.name().equals(task.coverageStatus())
                && GenerationContractVersions.V2.equals(task.workflowVersion())
                && GenerationContractVersions.V2.equals(task.inputVersion())
                && GenerationContractVersions.V2.equals(task.artifactVersion())
                && StructuredValidationFailure.Code.STRUCTURED_COORDINATOR_STATE_FAILURE.name()
                        .equals(task.validationErrorCode())
                && "$.artifact_export".equals(task.validationErrorPath())
                && task.resultSnapshot() == null
                && task.artifactId() == null && task.artifactSha256() == null && task.artifactPath() == null;
    }

    /** Selects only the terminal failure artifact produced by the retired identity-label grounding rule. */
    private static boolean isV2IdentityLabelRecoveryTask(RetryTaskRow task) {
        return GenerationTaskStatus.PARTIAL.name().equals(task.status())
                && StructuredProcessingStatus.FAILED.name().equals(task.processingStatus())
                && StructuredCoverageStatus.UNABLE_TO_GENERATE.name().equals(task.coverageStatus())
                && GenerationContractVersions.V2.equals(task.workflowVersion())
                && GenerationContractVersions.V2.equals(task.inputVersion())
                && GenerationContractVersions.V2.equals(task.artifactVersion())
                && isV2IdentityLabelRecoveryEnvelope(task.resultSnapshot(), task.validationErrorCode(),
                        task.validationErrorPath(), task.validationErrorMessage())
                && nonBlank(task.artifactId())
                && nonBlank(task.artifactSha256())
                && task.artifactSha256().matches("(?i)[0-9a-f]{64}")
                && nonBlank(task.artifactPath());
    }

    /** Accepts both historical terminal envelopes without weakening their diagnostic identity. */
    private static boolean isV2IdentityLabelRecoveryEnvelope(
            String resultSnapshot, String code, String path, String message) {
        boolean legacySnapshot = nonBlank(resultSnapshot) && code == null && path == null && message == null;
        Optional<StoredValidationDiagnostic> diagnostic = strictStoredValidationDiagnostic(code, path, message);
        boolean diagnosticOnly = resultSnapshot == null && diagnostic.isPresent()
                && StructuredValidationFailure.Code.TESTCASE_UNSUPPORTED_BUSINESS_DETAIL.name()
                        .equals(diagnostic.get().code())
                && V2_IDENTITY_LABEL_PATH.matcher(diagnostic.get().path()).matches();
        return legacySnapshot || diagnosticOnly;
    }

    /**
     * Revalidates the complete retained fact projection and the exact zero-write design failure set.
     * Function, task, project and fixture identities never participate in authorization. [Req-ID]: REQ-TGV2-015
     */
    private StructuredRetryDecision v2IdentityLabelRecoveryDecision(
            String taskId, RetryTaskRow task, List<RetryWorkRow> unfinished, boolean lockRows) {
        if (unfinished.isEmpty() || !hasUniqueFailureArtifact(taskId, task.artifactId(),
                task.artifactSha256(), task.artifactPath(), lockRows)) {
            return unavailableRetry("V2 身份标签失败制品或处理项不符合安全恢复条件");
        }
        Set<String> failedIds = new LinkedHashSet<>();
        Optional<StoredValidationDiagnostic> taskDiagnostic = strictStoredValidationDiagnostic(
                task.validationErrorCode(), task.validationErrorPath(), task.validationErrorMessage());
        boolean taskDiagnosticMatched = taskDiagnostic.isEmpty();
        for (RetryWorkRow work : unfinished) {
            Optional<StoredValidationDiagnostic> workDiagnostic = strictStoredValidationDiagnostic(
                    work.validationErrorCode(), work.validationErrorPath(), work.validationErrorMessage());
            if (!"FAILED".equals(work.status())
                    || !"functional-testcase-design".equals(work.skillName())
                    || !"FUNCTIONAL_TESTCASE_DESIGN_V2".equals(work.operationName())
                    || !nonBlank(work.functionKey()) || !nonBlank(work.testPointKey())
                    || work.coverageStatus() != null || work.acceptedResultSha256() != null
                    || work.hasLease() || work.hasRunningAttempt()
                    || workDiagnostic.isEmpty()
                    || !StructuredValidationFailure.Code.TESTCASE_UNSUPPORTED_BUSINESS_DETAIL.name()
                            .equals(workDiagnostic.get().code())
                    || !V2_IDENTITY_LABEL_PATH.matcher(workDiagnostic.get().path()).matches()
                    || !hasOnlyIdentityLabelValidationAttempts(
                            v2TechnicalRecoveryAttempts(work.id()), workDiagnostic.get())) {
                return unavailableRetry("V2 身份标签失败历史不符合安全恢复条件");
            }
            if (taskDiagnostic.equals(workDiagnostic)) taskDiagnosticMatched = true;
            failedIds.add(work.id());
        }
        if (!taskDiagnosticMatched) {
            return unavailableRetry("V2 身份标签任务诊断与失败处理项不一致");
        }
        if (hasAnyV2TestcaseDesignProjection(taskId, lockRows)) {
            return unavailableRetry("V2 身份标签失败已存在部分设计结果");
        }
        V2RecoverySnapshot snapshot;
        try {
            snapshot = loadV2RecoverySnapshot(taskId, Set.of(), lockRows);
        } catch (InvalidV2RecoverySnapshotException invalidSnapshot) {
            return unavailableRetry("V2 已发布事实无法安全复验");
        }
        if (!snapshot.exactTaskFactProjection()) {
            return unavailableRetry("V2 已发布事实集合与回放结果不闭合");
        }
        if (!snapshot.exactSplitLineage() || !snapshot.exactFailedLeafEvidence()) {
            return unavailableRetry("V2 冻结事实窗口谱系不闭合");
        }
        if (snapshot.works().stream()
                .filter(row -> "REQUIREMENT_FACT_EXTRACTION_V2".equals(row.operationName()))
                .filter(row -> "COMPLETED".equals(row.status()))
                .anyMatch(row -> !snapshot.completeFactProjections().getOrDefault(row.id(), false))) {
            return unavailableRetry("V2 已完成事实窗口与发布账本不闭合");
        }
        if (!isSafeV2IdentityLabelWorkGraph(snapshot, failedIds)) {
            return unavailableRetry("V2 身份标签失败工作图不符合安全恢复条件");
        }
        Set<String> affectedFunctions = unfinished.stream().map(RetryWorkRow::functionKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        String suffix = lockRows ? " FOR UPDATE" : "";
        Map<String, V2ApprovedFunctionRow> functions = loadV2ApprovedFunctions(
                taskId, affectedFunctions, suffix);
        Map<String, V2TaskFactProjection> facts = loadV2FactsForFunctions(
                taskId, affectedFunctions, suffix);
        Map<FunctionPointKey, V2GenerationPlanner.TestPointPlan> plans = buildV2TestPointPlans(
                taskId, functions, facts);
        for (V2FactWorkRow work : snapshot.works()) {
            if (!failedIds.contains(work.id())) continue;
            V2GenerationPlanner.TestPointPlan plan = plans.get(
                    new FunctionPointKey(work.functionKey(), work.testPointKey()));
            if (plan == null || !work.identityKey().equals(plan.registration().identityKey())) {
                return unavailableRetry("V2 身份标签失败工作身份无法从已发布事实重建");
            }
        }
        return new StructuredRetryDecision(StructuredRetryEligibility.eligible(), null,
                StructuredRetryMutation.RECOVER_V2_IDENTITY_LABEL_REJECTION);
    }

    private static boolean hasOnlyIdentityLabelValidationAttempts(
            List<V2TechnicalRecoveryAttemptRow> attempts, StoredValidationDiagnostic workDiagnostic) {
        if (attempts.isEmpty()) return false;
        int expectedAttemptNumber = attempts.size();
        boolean latest = true;
        for (V2TechnicalRecoveryAttemptRow attempt : attempts) {
            Optional<StoredValidationDiagnostic> diagnostic = strictStoredValidationDiagnostic(
                    attempt.validationErrorCode(), attempt.validationErrorPath(), attempt.validationErrorMessage());
            if (attempt.attemptNumber() != expectedAttemptNumber--
                    || !"FAILED".equals(attempt.status()) || attempt.completedAt() == null
                    || !"business_validation_failed".equals(attempt.failureType())
                    || diagnostic.isEmpty()
                    || !StructuredValidationFailure.Code.TESTCASE_UNSUPPORTED_BUSINESS_DETAIL.name()
                            .equals(diagnostic.get().code())
                    || !V2_IDENTITY_LABEL_PATH.matcher(diagnostic.get().path()).matches()
                    || latest && !diagnostic.get().equals(workDiagnostic)) return false;
            latest = false;
        }
        return true;
    }

    private static boolean isSafeV2IdentityLabelWorkGraph(
            V2RecoverySnapshot snapshot, Set<String> failedIds) {
        if (failedIds.isEmpty() || !snapshot.exactTaskFactProjection() || !snapshot.exactSplitLineage()
                || !snapshot.exactFailedLeafEvidence()) return false;
        int completedFactWorks = 0;
        for (V2FactWorkRow row : snapshot.works()) {
            if (row.hasLease() || row.runningAttempt()) return false;
            if ("REQUIREMENT_FACT_EXTRACTION_V2".equals(row.operationName())) {
                if (!"requirement-fact-extraction".equals(row.skillName())) return false;
                if ("COMPLETED".equals(row.status())) {
                    completedFactWorks++;
                    if (!nonBlank(row.acceptedResultSha256())
                            || !snapshot.completeFactProjections().getOrDefault(row.id(), false)) return false;
                } else if (!"SPLIT".equals(row.status()) || row.acceptedResultSha256() != null
                        || snapshot.allWorkOwnedBusinessIds().contains(row.id())) {
                    return false;
                }
            } else if ("FUNCTIONAL_TESTCASE_DESIGN_V2".equals(row.operationName())) {
                if (!"functional-testcase-design".equals(row.skillName())
                        || !failedIds.contains(row.id()) || !"FAILED".equals(row.status())
                        || row.acceptedResultSha256() != null
                        || snapshot.allWorkOwnedBusinessIds().contains(row.id())) return false;
            } else {
                return false;
            }
        }
        return completedFactWorks > 0 && snapshot.works().stream()
                .filter(row -> "FUNCTIONAL_TESTCASE_DESIGN_V2".equals(row.operationName()))
                .map(V2FactWorkRow::id).collect(java.util.stream.Collectors.toSet()).equals(failedIds);
    }

    /**
     * Proves the complete zero-write design rejection graph while independently replaying the accepted fact projection.
     * The function/test-point identities are derived from retained facts, so fixture or project identities cannot widen
     * this recovery branch. [Req-ID]: REQ-TGV2-014
     */
    private StructuredRetryDecision v2ExpectedResultsRecoveryDecision(
            String taskId, RetryTaskRow task, List<RetryWorkRow> unfinished, boolean lockRows) {
        Optional<StoredValidationDiagnostic> taskDiagnostic = strictStoredValidationDiagnostic(
                task.validationErrorCode(), task.validationErrorPath(), task.validationErrorMessage());
        if (taskDiagnostic.isEmpty()
                || !StructuredValidationFailure.Code.STRUCTURED_COORDINATOR_STATE_FAILURE.name()
                        .equals(taskDiagnostic.get().code())) {
            return unavailableRetry("V2 用例设计任务诊断不符合安全恢复条件");
        }
        List<RetryWorkRow> failedDesigns = new ArrayList<>();
        List<RetryWorkRow> staleFallbacks = new ArrayList<>();
        for (RetryWorkRow work : unfinished) {
            boolean exactDesign = "functional-testcase-design".equals(work.skillName())
                    && "FUNCTIONAL_TESTCASE_DESIGN_V2".equals(work.operationName())
                    && nonBlank(work.functionKey()) && nonBlank(work.testPointKey())
                    && work.coverageStatus() == null
                    && work.acceptedResultSha256() == null && !work.hasLease() && !work.hasRunningAttempt();
            if (!exactDesign) return unavailableRetry("V2 用例设计处理项不符合安全恢复条件");
            if ("FAILED".equals(work.status())
                    && StructuredValidationFailure.Code.TESTCASE_EXPECTED_ORDER_INVALID.name()
                            .equals(work.validationErrorCode())
                    && V2_EXPECTED_RESULTS_PATH.matcher(orDefault(work.validationErrorPath(), "")).matches()) {
                failedDesigns.add(work);
            } else if ("QUEUED".equals(work.status())
                    && V2GenerationPlanner.missingFormalFactPointKey(taskId, work.functionKey())
                            .equals(work.testPointKey())
                    && work.validationErrorCode() == null && work.validationErrorPath() == null
                    && work.validationErrorMessage() == null) {
                staleFallbacks.add(work);
            } else {
                return unavailableRetry("V2 用例设计处理项不符合安全恢复条件");
            }
        }
        if (failedDesigns.isEmpty()) return unavailableRetry("V2 用例设计失败集合为空");
        Set<String> affectedFunctions = failedDesigns.stream().map(RetryWorkRow::functionKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> fallbackFunctions = staleFallbacks.stream().map(RetryWorkRow::functionKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (staleFallbacks.isEmpty() || fallbackFunctions.size() != staleFallbacks.size()
                || !fallbackFunctions.equals(affectedFunctions)) {
            return unavailableRetry("V2 旧缺事实降级项不符合安全替换条件");
        }
        for (RetryWorkRow work : failedDesigns) {
            if (!hasOnlyExpectedResultsValidationAttempts(v2TechnicalRecoveryAttempts(work.id()))) {
                return unavailableRetry("V2 用例设计失败历史不符合安全恢复条件");
            }
        }
        for (RetryWorkRow fallback : staleFallbacks) {
            if (!hasOnlyTerminalZeroWriteFallbackAttempts(v2TechnicalRecoveryAttempts(fallback.id()))) {
                return unavailableRetry("V2 旧缺事实降级历史不符合安全替换条件");
            }
        }
        if (hasAnyV2TestcaseDesignProjection(taskId, lockRows) || hasArtifactCoordinates(taskId, lockRows)) {
            return unavailableRetry("V2 用例设计失败已存在部分结果");
        }
        V2RecoverySnapshot snapshot;
        try {
            snapshot = loadV2RecoverySnapshot(taskId, Set.of(), lockRows);
        } catch (InvalidV2RecoverySnapshotException invalidSnapshot) {
            return unavailableRetry("V2 已发布事实无法安全复验");
        }
        Set<String> failedIds = failedDesigns.stream().map(RetryWorkRow::id)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> fallbackIds = staleFallbacks.stream().map(RetryWorkRow::id)
                .collect(java.util.stream.Collectors.toSet());
        if (!isSafeV2ExpectedResultsWorkGraph(snapshot, failedIds, fallbackIds)) {
            return unavailableRetry("V2 用例设计工作图不符合安全恢复条件");
        }
        List<V2FactWorkRow> failedRows = snapshot.works().stream().filter(row -> failedIds.contains(row.id())).toList();
        V2TestcaseBusinessBatch expectedPlans = loadV2TestcaseBusinessBatch(
                taskId, failedRows, lockRows ? " FOR UPDATE" : "");
        for (V2FactWorkRow row : failedRows) {
            V2GenerationPlanner.TestPointPlan plan = expectedPlans.testPointPlans()
                    .get(new FunctionPointKey(row.functionKey(), row.testPointKey()));
            if (plan == null || !row.identityKey().equals(plan.registration().identityKey())) {
                return unavailableRetry("V2 用例设计冻结身份无法从已发布事实重建");
            }
        }
        for (RetryWorkRow fallback : staleFallbacks) {
            V2GenerationPlanner.TestPointPlan plan = expectedPlans.testPointPlans()
                    .get(new FunctionPointKey(fallback.functionKey(), fallback.testPointKey()));
            if (plan == null || !fallback.identityKey().equals(plan.registration().identityKey())) {
                return unavailableRetry("V2 旧缺事实降级身份无法从已审核范围重建");
            }
        }
        Map<String, V2TaskFactProjection> affectedFacts = loadV2FactsForFunctions(
                taskId, affectedFunctions, lockRows ? " FOR UPDATE" : "");
        if (affectedFunctions.stream().anyMatch(function -> affectedFacts.values().stream()
                .noneMatch(fact -> function.equals(fact.functionKey())))) {
            return unavailableRetry("V2 用例设计缺少已发布事实");
        }
        return new StructuredRetryDecision(StructuredRetryEligibility.eligible(), null,
                StructuredRetryMutation.RECOVER_V2_EXPECTED_RESULTS_REJECTION);
    }

    private static boolean hasOnlyExpectedResultsValidationAttempts(List<V2TechnicalRecoveryAttemptRow> attempts) {
        if (attempts.isEmpty()) return false;
        for (int index = 0; index < attempts.size(); index++) {
            V2TechnicalRecoveryAttemptRow attempt = attempts.get(index);
            Optional<StoredValidationDiagnostic> diagnostic = strictStoredValidationDiagnostic(
                    attempt.validationErrorCode(), attempt.validationErrorPath(), attempt.validationErrorMessage());
            if (attempt.attemptNumber() != attempts.size() - index || !"FAILED".equals(attempt.status())
                    || attempt.completedAt() == null || !"business_validation_failed".equals(attempt.failureType())
                    || diagnostic.isEmpty()
                    || !StructuredValidationFailure.Code.TESTCASE_EXPECTED_ORDER_INVALID.name()
                            .equals(diagnostic.get().code())
                    || !V2_EXPECTED_RESULTS_PATH.matcher(diagnostic.get().path()).matches()) return false;
        }
        return true;
    }

    private static boolean hasOnlyTerminalZeroWriteFallbackAttempts(List<V2TechnicalRecoveryAttemptRow> attempts) {
        if (attempts.isEmpty()) return false;
        for (int index = 0; index < attempts.size(); index++) {
            V2TechnicalRecoveryAttemptRow attempt = attempts.get(index);
            if (attempt.attemptNumber() != attempts.size() - index || !"FAILED".equals(attempt.status())
                    || attempt.completedAt() == null || !V2_ZERO_WRITE_TECHNICAL_FAILURES.contains(attempt.failureType())
                    || attempt.validationErrorCode() != null || attempt.validationErrorPath() != null
                    || attempt.validationErrorMessage() != null) return false;
        }
        return true;
    }

    /**
     * Selects only the terminal V2 shape whose workbook is a failure projection rather than accepted business data.
     * The database graph is checked separately after task/work locks are acquired.
     * [Req-ID]: REQ-TGV2-013
     */
    private static boolean isV2ZeroWriteTechnicalRecoveryTask(RetryTaskRow task) {
        return GenerationTaskStatus.PARTIAL.name().equals(task.status())
                && StructuredProcessingStatus.FAILED.name().equals(task.processingStatus())
                && StructuredCoverageStatus.UNABLE_TO_GENERATE.name().equals(task.coverageStatus())
                && GenerationContractVersions.V2.equals(task.workflowVersion())
                && GenerationContractVersions.V2.equals(task.inputVersion())
                && GenerationContractVersions.V2.equals(task.artifactVersion())
                && task.validationErrorCode() == null
                && task.validationErrorPath() == null
                && task.validationErrorMessage() == null
                && nonBlank(task.artifactId())
                && nonBlank(task.artifactSha256())
                && task.artifactSha256().matches("(?i)[0-9a-f]{64}")
                && nonBlank(task.artifactPath());
    }

    /**
     * Proves that every model-facing V2 work failed technically before publishing any business row.
     * Advisory reads and the mutation path share this predicate; the latter additionally holds task/work/current-row
     * locks so a concurrent accept cannot cross the zero-write decision.
     * [Req-ID]: REQ-TGV2-013
     */
    private StructuredRetryDecision v2ZeroWriteTechnicalRecoveryDecision(
            String taskId, RetryTaskRow task, List<RetryWorkRow> unfinished, boolean lockRows) {
        if (unfinished.isEmpty() || !hasUniqueFailureArtifact(taskId, task.artifactId(),
                task.artifactSha256(), task.artifactPath(), lockRows)) {
            return unavailableRetry("V2 技术失败制品或处理项不符合安全恢复条件");
        }
        List<String> allWorkIds = jdbcTemplate.queryForList("""
                SELECT id FROM structured_generation_work_item
                WHERE task_id = ? ORDER BY created_at, id%s
                """.formatted(lockRows ? " FOR UPDATE" : ""), String.class, taskId);
        if (allWorkIds.size() != unfinished.size()) {
            return unavailableRetry("V2 技术失败任务已包含其他处理结果");
        }
        for (RetryWorkRow work : unfinished) {
            if (!"FAILED".equals(work.status())
                    || work.acceptedResultSha256() != null
                    || work.hasLease()
                    || work.hasRunningAttempt()
                    || work.validationErrorCode() != null
                    || work.validationErrorPath() != null
                    || work.validationErrorMessage() != null
                    || !isV2GenerationWork(work)) {
                return unavailableRetry("V2 技术失败处理项不符合安全恢复条件");
            }
            List<V2TechnicalRecoveryAttemptRow> attempts = v2TechnicalRecoveryAttempts(work.id());
            if (!hasOnlyTerminalRecoverableV2TechnicalAttempts(attempts)) {
                return unavailableRetry("该 V2 技术失败类型不能通过此操作恢复");
            }
        }
        if (unfinishedWorkOwnedBusinessRowCount(taskId, lockRows) != 0
                || hasAnyV2TechnicalRecoveryBusinessState(taskId, lockRows)) {
            return unavailableRetry("V2 技术失败任务已存在部分业务结果");
        }
        return new StructuredRetryDecision(
                StructuredRetryEligibility.eligible(), null,
                StructuredRetryMutation.RECOVER_V2_ZERO_WRITE_TECHNICAL_FAILURE);
    }

    private static boolean isV2GenerationWork(RetryWorkRow work) {
        return ("requirement-fact-extraction".equals(work.skillName())
                        && "REQUIREMENT_FACT_EXTRACTION_V2".equals(work.operationName()))
                || ("functional-testcase-design".equals(work.skillName())
                        && "FUNCTIONAL_TESTCASE_DESIGN_V2".equals(work.operationName()));
    }

    private List<V2TechnicalRecoveryAttemptRow> v2TechnicalRecoveryAttempts(String workItemId) {
        // The READ COMMITTED mutation path already holds the parent work row before this query. InnoDB therefore
        // blocks any child attempt insert on the foreign-key check, while this fresh statement sees every commit that
        // preceded the acquired work lock. A prefix-range FOR UPDATE on (work_item_id, attempt_number) would add
        // next-key locks across adjacent UUIDs and can deadlock two otherwise unrelated task recoveries.
        // [Req-ID]: REQ-TGV2-013
        return jdbcTemplate.query("""
                        SELECT attempt_number, status, failure_type, completed_at,
                               validation_error_code, validation_error_path, validation_error_message
                        FROM structured_generation_attempt
                        WHERE work_item_id = ? ORDER BY attempt_number DESC
                        """,
                (row, ignored) -> new V2TechnicalRecoveryAttemptRow(
                        row.getInt("attempt_number"), row.getString("status"), row.getString("failure_type"),
                        row.getTimestamp("completed_at") == null ? null : row.getTimestamp("completed_at").toInstant(),
                        row.getString("validation_error_code"), row.getString("validation_error_path"),
                        row.getString("validation_error_message")), workItemId);
    }

    private static boolean hasOnlyTerminalRecoverableV2TechnicalAttempts(
            List<V2TechnicalRecoveryAttemptRow> attempts) {
        if (attempts.isEmpty()) return false;
        int expectedAttemptNumber = attempts.size();
        for (V2TechnicalRecoveryAttemptRow attempt : attempts) {
            if (attempt.attemptNumber() != expectedAttemptNumber--
                    || !"FAILED".equals(attempt.status())
                    || attempt.completedAt() == null
                    || !V2_ZERO_WRITE_TECHNICAL_FAILURES.contains(attempt.failureType())
                    || attempt.validationErrorCode() != null
                    || attempt.validationErrorPath() != null
                    || attempt.validationErrorMessage() != null) {
                return false;
            }
        }
        return true;
    }

    private boolean hasUniqueFailureArtifact(String taskId, String artifactId, String artifactSha256,
            String artifactPath, boolean lockRows) {
        // V24 gives both identity lookups their own unique indexes. Separate, fixed-order current reads avoid the
        // unindexed OR scan that could deadlock two unrelated task recoveries after each had locked its task row.
        // Equal workbook hashes remain legal because a digest is content identity, not artifact ownership.
        // [Req-ID]: REQ-TGV2-013
        List<FailureArtifactOwner> idOwners = artifactOwners("artifact_id = ?", artifactId, lockRows);
        List<FailureArtifactOwner> pathOwners = artifactOwners(
                "artifact_path_sha256 = UNHEX(SHA2(?, 256)) AND artifact_path = ?",
                new Object[] {artifactPath, artifactPath}, lockRows);
        return isExactArtifactOwner(idOwners, taskId, artifactId, artifactSha256, artifactPath)
                && isExactArtifactOwner(pathOwners, taskId, artifactId, artifactSha256, artifactPath);
    }

    private List<FailureArtifactOwner> artifactOwners(String predicate, Object argument, boolean lockRows) {
        return artifactOwners(predicate, new Object[] {argument}, lockRows);
    }

    private List<FailureArtifactOwner> artifactOwners(String predicate, Object[] arguments, boolean lockRows) {
        return jdbcTemplate.query("""
                        SELECT id, artifact_id, artifact_sha256, artifact_path
                        FROM generation_task WHERE %s%s
                        """.formatted(predicate, lockRows ? " FOR UPDATE" : ""),
                (row, ignored) -> new FailureArtifactOwner(row.getString("id"), row.getString("artifact_id"),
                        row.getString("artifact_sha256"), row.getString("artifact_path")), arguments);
    }

    private static boolean isExactArtifactOwner(List<FailureArtifactOwner> owners, String taskId,
            String artifactId, String artifactSha256, String artifactPath) {
        return owners.size() == 1
                && taskId.equals(owners.get(0).taskId())
                && artifactId.equals(owners.get(0).artifactId())
                && artifactSha256.equals(owners.get(0).artifactSha256())
                && artifactPath.equals(owners.get(0).artifactPath());
    }

    private boolean hasAnyV2TechnicalRecoveryBusinessState(String taskId, boolean lockRows) {
        for (String table : V2_TECHNICAL_RECOVERY_BUSINESS_TABLES) {
            if (hasLockedRows("SELECT task_id FROM " + table + " WHERE task_id = ?", taskId, lockRows)) return true;
        }
        return hasLockedRows("SELECT work_item_id FROM structured_reconciliation_run WHERE task_id = ?", taskId, lockRows);
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** A retained completed work may have failed history, but exactly one attempt may have published success. */
    private static boolean hasUniqueSuccessfulAttempt(List<RetryAttemptRow> attempts) {
        return !attempts.isEmpty()
                && "COMPLETED".equals(attempts.get(0).status())
                && attempts.stream().filter(attempt -> "COMPLETED".equals(attempt.status())).count() == 1;
    }

    private boolean hasCurrentV2FactPublication(String taskId, boolean lockRows) {
        return hasLockedRows("SELECT fact_key FROM v2_requirement_fact WHERE task_id = ?", taskId, lockRows)
                || hasLockedRows("SELECT feedback_key FROM v2_testability_feedback WHERE task_id = ?", taskId, lockRows)
                || hasLockedRows("""
                        SELECT work_item_id FROM v2_work_publication
                        WHERE task_id = ? AND publication_type = 'requirement_facts'
                        """, taskId, lockRows);
    }

    private boolean hasCurrentV2TestcaseRows(String taskId, boolean lockRows) {
        return hasLockedRows("SELECT case_key FROM structured_test_case WHERE task_id = ?", taskId, lockRows)
                || hasLockedRows("""
                        SELECT step.work_item_id FROM structured_test_case_step step
                        JOIN structured_generation_work_item work ON work.id = step.work_item_id
                        WHERE work.task_id = ?
                        """, taskId, lockRows)
                || hasLockedRows("""
                        SELECT binding.work_item_id FROM structured_reference_binding binding
                        JOIN structured_generation_work_item work ON work.id = binding.work_item_id
                        WHERE work.task_id = ?
                """, taskId, lockRows);
    }

    /** Includes non-case design rows because even a pending or unable outcome is a published V2 business result. */
    private boolean hasAnyV2TestcaseDesignProjection(String taskId, boolean lockRows) {
        return hasLockedRows("SELECT test_point_key FROM structured_test_point WHERE task_id = ?", taskId, lockRows)
                || hasLockedRows("SELECT test_point_key FROM v2_generation_outcome WHERE task_id = ?", taskId, lockRows)
                || hasCurrentV2TestcaseRows(taskId, lockRows)
                || hasLockedRows("""
                        SELECT work_item_id FROM v2_work_publication
                        WHERE task_id = ? AND publication_type = 'testcase_design'
                        """, taskId, lockRows);
    }

    private static boolean isSafeV2ExpectedResultsWorkGraph(
            V2RecoverySnapshot snapshot, Set<String> failedIds, Set<String> fallbackIds) {
        if (!snapshot.exactTaskFactProjection() || !snapshot.exactSplitLineage()
                || !snapshot.exactFailedLeafEvidence()) return false;
        int completedFactWorks = 0;
        for (V2FactWorkRow row : snapshot.works()) {
            if (row.hasLease() || row.runningAttempt()) return false;
            if ("REQUIREMENT_FACT_EXTRACTION_V2".equals(row.operationName())) {
                if (!"requirement-fact-extraction".equals(row.skillName())) return false;
                if ("COMPLETED".equals(row.status())) {
                    completedFactWorks++;
                    if (!nonBlank(row.acceptedResultSha256())
                            || !snapshot.completeFactProjections().getOrDefault(row.id(), false)) return false;
                } else if (!"SPLIT".equals(row.status()) || row.acceptedResultSha256() != null
                        || snapshot.allWorkOwnedBusinessIds().contains(row.id())) {
                    return false;
                }
            } else if ("FUNCTIONAL_TESTCASE_DESIGN_V2".equals(row.operationName())) {
                if (!"functional-testcase-design".equals(row.skillName())
                        || row.acceptedResultSha256() != null
                        || snapshot.allWorkOwnedBusinessIds().contains(row.id())) return false;
                if (failedIds.contains(row.id())) {
                    if (!"FAILED".equals(row.status())) return false;
                } else if (fallbackIds.contains(row.id())) {
                    if (!"QUEUED".equals(row.status())) return false;
                } else {
                    return false;
                }
            } else {
                return false;
            }
        }
        return completedFactWorks > 0;
    }

    private boolean hasLockedRows(String sql, String taskId, boolean lockRows) {
        return !jdbcTemplate.queryForList(sql + (lockRows ? " FOR UPDATE" : ""), String.class, taskId).isEmpty();
    }

    private List<V2FallbackWorkRow> currentV2TestcaseWorks(String taskId, boolean lockRows) {
        return jdbcTemplate.query("""
                        SELECT id, identity_key, function_key, test_point_key, status, accepted_result_sha256,
                               (lease_owner IS NOT NULL OR lease_expires_at IS NOT NULL) AS has_lease,
                               EXISTS (SELECT 1 FROM structured_generation_attempt running
                                   WHERE running.work_item_id = work.id AND running.status = 'RUNNING') AS running_attempt
                        FROM structured_generation_work_item work
                        WHERE task_id = ? AND skill_name = 'functional-testcase-design'
                          AND operation_name = 'FUNCTIONAL_TESTCASE_DESIGN_V2'
                          AND status <> 'SUPERSEDED'
                        ORDER BY function_key, id%s
                        """.formatted(lockRows ? " FOR UPDATE" : ""),
                (row, ignored) -> new V2FallbackWorkRow(row.getString("id"), row.getString("identity_key"),
                        row.getString("function_key"),
                        row.getString("test_point_key"), row.getString("status"),
                        row.getString("accepted_result_sha256"), row.getBoolean("has_lease"),
                        row.getBoolean("running_attempt")), taskId);
    }

    private static List<V2FallbackWorkRow> currentV2TestcaseWorks(V2RecoverySnapshot snapshot) {
        return snapshot.works().stream()
                .filter(work -> "functional-testcase-design".equals(work.skillName())
                        && "FUNCTIONAL_TESTCASE_DESIGN_V2".equals(work.operationName())
                        && !"SUPERSEDED".equals(work.status()))
                .map(work -> new V2FallbackWorkRow(work.id(), work.identityKey(), work.functionKey(),
                        work.testPointKey(), work.status(), work.acceptedResultSha256(), work.hasLease(),
                        work.runningAttempt()))
                .sorted(java.util.Comparator.comparing(V2FallbackWorkRow::functionKey)
                        .thenComparing(V2FallbackWorkRow::id))
                .toList();
    }

    /**
     * Proves that the task contains only failed V2 fact leaves, their legitimate split lineage, and the exact
     * no-fact testcase fallbacks. This is deliberately independent of task, project, document, or fixture identity.
     * [Req-ID]: REQ-TGV2-011
     */
    private boolean isExactV2AtomicityWorkGraph(String taskId, List<RetryWorkRow> unfinished,
            List<V2FallbackWorkRow> fallbacks, boolean lockRows) {
        List<V2FactWorkRow> rows = jdbcTemplate.query("""
                        SELECT work.id, work.parent_work_item_id, work.status, work.skill_name, work.operation_name,
                               work.ordinal_start, work.ordinal_end, work.material_key, work.material_document_id,
                               work.source_label, work.identity_key, work.split_depth, work.function_key,
                               work.test_point_key, work.accepted_result_sha256,
                               (work.lease_owner IS NOT NULL OR work.lease_expires_at IS NOT NULL) AS has_lease,
                               EXISTS (SELECT 1 FROM structured_generation_attempt running
                                   WHERE running.work_item_id=work.id AND running.status='RUNNING') AS running_attempt
                        FROM structured_generation_work_item work
                        WHERE work.task_id=?
                        ORDER BY work.created_at, work.id%s
                        """.formatted(lockRows ? " FOR UPDATE" : ""),
                (row, ignored) -> new V2FactWorkRow(row.getString("id"), row.getString("parent_work_item_id"),
                        row.getString("status"), row.getString("skill_name"), row.getString("operation_name"),
                        nullableInteger(row, "ordinal_start"), nullableInteger(row, "ordinal_end"),
                        row.getString("material_key"), row.getString("material_document_id"),
                        row.getString("source_label"), List.of(), List.of(), row.getString("identity_key"),
                        row.getInt("split_depth"), row.getString("function_key"), row.getString("test_point_key"),
                        row.getString("accepted_result_sha256"), row.getBoolean("has_lease"),
                        row.getBoolean("running_attempt")), taskId);
        Map<String, V2FactWorkRow> byId = new LinkedHashMap<>();
        Map<String, List<V2FactWorkRow>> children = new LinkedHashMap<>();
        Set<String> failedIds = unfinished.stream().map(RetryWorkRow::id).collect(java.util.stream.Collectors.toSet());
        Set<String> fallbackIds = fallbacks.stream().map(V2FallbackWorkRow::id).collect(java.util.stream.Collectors.toSet());
        for (V2FactWorkRow row : rows) {
            if (byId.putIfAbsent(row.id(), row) != null || row.hasLease() || row.runningAttempt()) return false;
            if (row.parentWorkItemId() != null) {
                children.computeIfAbsent(row.parentWorkItemId(), ignored -> new ArrayList<>()).add(row);
            }
            if ("REQUIREMENT_FACT_EXTRACTION_V2".equals(row.operationName())) {
                if (!"requirement-fact-extraction".equals(row.skillName()) || row.acceptedResultSha256() != null
                        || !("FAILED".equals(row.status()) || "SPLIT".equals(row.status()))) return false;
                if ("FAILED".equals(row.status()) != failedIds.contains(row.id())) return false;
                if (workOwnedBusinessRowCount(row.id(), lockRows) != 0) return false;
            } else if ("FUNCTIONAL_TESTCASE_DESIGN_V2".equals(row.operationName())) {
                if (!"functional-testcase-design".equals(row.skillName()) || !"COMPLETED".equals(row.status())
                        || !fallbackIds.contains(row.id()) || !nonBlank(row.acceptedResultSha256())) return false;
            } else {
                return false;
            }
        }
        if (failedIds.size() != unfinished.size() || !byId.keySet().containsAll(failedIds)
                || !byId.keySet().containsAll(fallbackIds)) return false;
        for (V2FactWorkRow row : rows) {
            if ("SPLIT".equals(row.status())) {
                List<V2FactWorkRow> direct = children.getOrDefault(row.id(), List.of()).stream()
                        .sorted(java.util.Comparator.comparing(V2FactWorkRow::ordinalStart)).toList();
                if (direct.size() != 2) return false;
            } else if (row.parentWorkItemId() != null) {
                V2FactWorkRow parent = byId.get(row.parentWorkItemId());
                if (parent == null || !"SPLIT".equals(parent.status())) return false;
            }
        }
        List<V2FactWorkRow> failedLeaves = rows.stream().filter(row -> failedIds.contains(row.id())).toList();
        for (int offset = 0; offset < failedLeaves.size(); offset += V2_FACT_RECOVERY_WORK_BATCH_SIZE) {
            exactV2EvidenceBatch(taskId, failedLeaves.subList(
                    offset, Math.min(offset + V2_FACT_RECOVERY_WORK_BATCH_SIZE, failedLeaves.size())),
                    lockRows ? " FOR UPDATE" : "");
        }
        return hasExactV2SplitLineage(taskId, rows, lockRows ? " FOR UPDATE" : "");
    }

    /**
     * Compares the exact task-level fact and quote unions formed by already replayed completed windows. A fact can be
     * merged across windows, so per-work subset checks are insufficient and per-work equality would reject valid
     * merges. [Req-ID]: REQ-TGV2-012
     */
    private boolean hasExactTaskV2FactProjection(
            Set<String> expectedFacts, Map<String, Set<String>> expectedQuotes,
            String taskId, String suffix) {
        Set<String> storedFacts = new LinkedHashSet<>(jdbcTemplate.queryForList(
                "SELECT fact_key FROM v2_requirement_fact WHERE task_id=? ORDER BY fact_key" + suffix,
                String.class, taskId));
        Map<String, Set<String>> storedQuotes = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT fact_key,
                       SHA2(CONCAT(CAST(evidence_key AS BINARY), 0x00, CAST(quote_text AS BINARY)), 256)
                           AS quote_identity
                FROM v2_requirement_fact_quote
                WHERE task_id=? ORDER BY fact_key, evidence_key, quote_sha256%s
                """.formatted(suffix), (org.springframework.jdbc.core.RowCallbackHandler) row -> storedQuotes
                .computeIfAbsent(row.getString("fact_key"), ignored -> new LinkedHashSet<>())
                .add(row.getString("quote_identity")), taskId);
        return expectedFacts.equals(storedFacts) && expectedQuotes.equals(storedQuotes);
    }

    /**
     * Loads one task-scoped, lock-compatible recovery snapshot. Recovery validation is intentionally performed from
     * this bounded query set so the task lock is not held across one SQL round trip per fact, case, or binding.
     * [Req-ID]: REQ-TGV2-012
     */
    private V2RecoverySnapshot loadV2RecoverySnapshot(
            String taskId, Set<String> affectedFunctions, boolean lockRows) {
        String suffix = lockRows ? " FOR UPDATE" : "";
        List<V2FactWorkRow> works = jdbcTemplate.query("""
                        SELECT work.id, work.parent_work_item_id, work.status, work.skill_name, work.operation_name,
                               work.ordinal_start, work.ordinal_end, work.material_key, work.material_document_id,
                               work.source_label, work.identity_key, work.split_depth, work.function_key, work.test_point_key,
                               work.accepted_result_sha256,
                               (work.lease_owner IS NOT NULL OR work.lease_expires_at IS NOT NULL) AS has_lease,
                               EXISTS (SELECT 1 FROM structured_generation_attempt running
                                   WHERE running.work_item_id=work.id AND running.status='RUNNING') AS running_attempt
                        FROM structured_generation_work_item work
                        WHERE work.task_id=? ORDER BY work.created_at, work.id%s
                        """.formatted(suffix),
                (row, ignored) -> new V2FactWorkRow(row.getString("id"), row.getString("parent_work_item_id"),
                        row.getString("status"), row.getString("skill_name"), row.getString("operation_name"),
                        nullableInteger(row, "ordinal_start"), nullableInteger(row, "ordinal_end"),
                        row.getString("material_key"), row.getString("material_document_id"),
                        row.getString("source_label"), List.of(), List.of(), row.getString("identity_key"),
                        row.getInt("split_depth"), row.getString("function_key"), row.getString("test_point_key"),
                        row.getString("accepted_result_sha256"), row.getBoolean("has_lease"),
                        row.getBoolean("running_attempt")), taskId);
        Map<String, V2ApprovedFunctionRow> functions = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT function_key
                FROM v2_approved_function WHERE task_id=? ORDER BY stable_sequence%s
                """.formatted(suffix), (org.springframework.jdbc.core.RowCallbackHandler) row ->
                functions.put(row.getString("function_key"),
                new V2ApprovedFunctionRow(row.getString("function_key"), null, null, null)), taskId);

        Map<String, String> documentRoles = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT document_id, document_role FROM material_inventory_document
                WHERE task_id=? AND complete=TRUE ORDER BY document_id%s
                """.formatted(suffix), (org.springframework.jdbc.core.RowCallbackHandler) row ->
                documentRoles.put(row.getString("document_id"),
                row.getString("document_role")), taskId);
        Map<String, List<RetryAttemptRow>> attemptsByWork = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT attempt.work_item_id, attempt.status, attempt.failure_type,
                       attempt.validation_error_code, attempt.validation_error_path,
                       attempt.validation_error_message
                FROM structured_generation_attempt attempt
                JOIN structured_generation_work_item work ON work.id=attempt.work_item_id
                WHERE work.task_id=? ORDER BY attempt.work_item_id, attempt.attempt_number DESC%s
                """.formatted(suffix), (org.springframework.jdbc.core.RowCallbackHandler) row ->
                attemptsByWork.computeIfAbsent(
                        row.getString("work_item_id"), ignored -> new ArrayList<>()).add(new RetryAttemptRow(
                        row.getString("status"), row.getString("failure_type"),
                        row.getString("validation_error_code"), row.getString("validation_error_path"),
                        row.getString("validation_error_message"))), taskId);

        Map<String, List<V2ReplayPublication>> publicationsByWork = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT work_item_id, publication_type, input_sha256, result_sha256
                FROM v2_work_publication WHERE task_id=? ORDER BY work_item_id%s
                """.formatted(suffix), (org.springframework.jdbc.core.RowCallbackHandler) row -> publicationsByWork
                .computeIfAbsent(row.getString("work_item_id"), ignored -> new ArrayList<>())
                .add(new V2ReplayPublication(row.getString("publication_type"), row.getString("input_sha256"),
                        row.getString("result_sha256"), null)), taskId);

        Map<String, Set<String>> workIdsByTable = new LinkedHashMap<>();
        Set<String> legacyOrReconciliationWorkIds = new LinkedHashSet<>();
        for (String table : UNFINISHED_WORK_BUSINESS_TABLES) {
            Set<String> workIds = taskWorkIds(table, taskId, lockRows);
            workIdsByTable.put(table, workIds);
            legacyOrReconciliationWorkIds.addAll(workIds);
        }
        for (String table : RECONCILIATION_WORK_OWNED_TABLES) {
            Set<String> workIds = taskWorkIds(table, taskId, lockRows);
            workIdsByTable.put(table, workIds);
            legacyOrReconciliationWorkIds.addAll(workIds);
        }
        Set<String> factOwnerIds = new LinkedHashSet<>(jdbcTemplate.queryForList(
                "SELECT first_work_item_id FROM v2_requirement_fact WHERE task_id=?" + suffix,
                String.class, taskId));
        Set<String> feedbackOwnerIds = new LinkedHashSet<>(jdbcTemplate.queryForList(
                "SELECT work_item_id FROM v2_testability_feedback WHERE task_id=?" + suffix,
                String.class, taskId));
        Set<String> outcomeOwnerIds = new LinkedHashSet<>(jdbcTemplate.queryForList(
                "SELECT work_item_id FROM v2_generation_outcome WHERE task_id=?" + suffix,
                String.class, taskId));
        Set<String> publicationOwnerIds = new LinkedHashSet<>(publicationsByWork.keySet());
        Set<String> allWorkOwnedBusinessIds = new LinkedHashSet<>(legacyOrReconciliationWorkIds);
        allWorkOwnedBusinessIds.addAll(factOwnerIds);
        allWorkOwnedBusinessIds.addAll(feedbackOwnerIds);
        allWorkOwnedBusinessIds.addAll(outcomeOwnerIds);
        allWorkOwnedBusinessIds.addAll(publicationOwnerIds);

        Set<String> unexpectedFactWorkIds = new LinkedHashSet<>(legacyOrReconciliationWorkIds);
        unexpectedFactWorkIds.addAll(outcomeOwnerIds);
        Set<String> testcaseAllowedTables = Set.of("structured_test_point", "structured_test_case",
                "structured_test_case_step", "structured_reference_binding");
        Set<String> unexpectedTestcaseWorkIds = new LinkedHashSet<>();
        for (String table : UNFINISHED_WORK_BUSINESS_TABLES) {
            if (!testcaseAllowedTables.contains(table)) {
                unexpectedTestcaseWorkIds.addAll(workIdsByTable.getOrDefault(table, Set.of()));
            }
        }
        for (String table : RECONCILIATION_WORK_OWNED_TABLES) {
            unexpectedTestcaseWorkIds.addAll(workIdsByTable.getOrDefault(table, Set.of()));
        }
        unexpectedTestcaseWorkIds.addAll(factOwnerIds);
        unexpectedTestcaseWorkIds.addAll(feedbackOwnerIds);

        V2FactProjectionValidation factProjectionValidation = loadCompleteV2FactProjections(
                taskId, works, affectedFunctions, attemptsByWork, documentRoles,
                unexpectedFactWorkIds, lockRows);
        Map<String, Boolean> completeTestcaseProjections = loadCompleteV2TestcaseProjections(
                taskId, works, attemptsByWork, unexpectedTestcaseWorkIds, lockRows);
        boolean exactSplitLineage = hasExactV2SplitLineage(taskId, works, suffix);
        boolean exactFailedLeafEvidence = hasExactV2FailedLeafEvidence(taskId, works, suffix);
        return new V2RecoverySnapshot(works, functions, attemptsByWork, publicationsByWork,
                factProjectionValidation.completeByWork(), factProjectionValidation.exactTaskProjection(),
                completeTestcaseProjections, allWorkOwnedBusinessIds, exactSplitLineage, exactFailedLeafEvidence);
    }

    /**
     * Validates one split family at a time, so a large historical task never retains every raw evidence array while
     * task rows are locked. Parent and child evidence must still resolve to the frozen material inventory exactly.
     * [Req-ID]: REQ-TGV2-012
     */
    private boolean hasExactV2SplitLineage(String taskId, List<V2FactWorkRow> works, String suffix) {
        Map<String, List<V2FactWorkRow>> children = new LinkedHashMap<>();
        works.stream().filter(work -> work.parentWorkItemId() != null).forEach(work ->
                children.computeIfAbsent(work.parentWorkItemId(), ignored -> new ArrayList<>()).add(work));
        for (V2FactWorkRow parent : works) {
            if (!"SPLIT".equals(parent.status())) continue;
            List<V2FactWorkRow> direct = children.getOrDefault(parent.id(), List.of()).stream()
                    .sorted(java.util.Comparator.comparing(V2FactWorkRow::ordinalStart)).toList();
            if (direct.size() != 2) return false;
            List<V2FactWorkRow> family = exactV2EvidenceBatch(
                    taskId, List.of(parent, direct.get(0), direct.get(1)), suffix);
            if (!isExactSplit(family.get(0), family.get(1), family.get(2))) return false;
        }
        return true;
    }

    private boolean hasExactV2FailedLeafEvidence(String taskId, List<V2FactWorkRow> works, String suffix) {
        List<V2FactWorkRow> failedLeaves = works.stream()
                .filter(work -> "REQUIREMENT_FACT_EXTRACTION_V2".equals(work.operationName()))
                .filter(work -> "FAILED".equals(work.status()))
                .toList();
        for (int offset = 0; offset < failedLeaves.size(); offset += V2_FACT_RECOVERY_WORK_BATCH_SIZE) {
            exactV2EvidenceBatch(taskId, failedLeaves.subList(
                    offset, Math.min(offset + V2_FACT_RECOVERY_WORK_BATCH_SIZE, failedLeaves.size())), suffix);
        }
        return true;
    }

    /**
     * Partitions recovery identities so split-heavy tasks cannot create an unbounded SQL parameter list.
     * Encounter order is retained because the final exact-set comparison also detects missing lineage rows.
     * [Req-ID]: REQ-TGV2-012
     */
    static List<List<String>> partitionV2RecoveryIds(Set<String> identities) {
        List<String> ordered = List.copyOf(identities);
        List<List<String>> batches = new ArrayList<>();
        for (int offset = 0; offset < ordered.size(); offset += V2_RECOVERY_UNIT_BATCH_SIZE) {
            batches.add(List.copyOf(ordered.subList(
                    offset, Math.min(offset + V2_RECOVERY_UNIT_BATCH_SIZE, ordered.size()))));
        }
        return List.copyOf(batches);
    }

    private Map<String, V2ApprovedFunctionRow> loadV2ApprovedFunctions(
            String taskId, Set<String> functionKeys, String suffix) {
        if (functionKeys.isEmpty()) return Map.of();
        String placeholders = functionKeys.stream().map(ignored -> "?")
                .collect(java.util.stream.Collectors.joining(", "));
        List<Object> parameters = new ArrayList<>(1 + functionKeys.size());
        parameters.add(taskId);
        parameters.addAll(functionKeys);
        Map<String, V2ApprovedFunctionRow> result = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT function_key, name_text, path_text, description_text
                FROM v2_approved_function WHERE task_id=? AND function_key IN (%s)
                ORDER BY stable_sequence%s
                """.formatted(placeholders, suffix), (org.springframework.jdbc.core.RowCallbackHandler) row ->
                result.put(row.getString("function_key"), new V2ApprovedFunctionRow(
                        row.getString("function_key"), row.getString("name_text"),
                        row.getString("path_text"), row.getString("description_text"))), parameters.toArray());
        return result;
    }

    private List<V2FactWorkRow> hydrateV2FactWorkEvidence(
            String taskId, List<V2FactWorkRow> works, String suffix) {
        if (works.isEmpty()) return List.of();
        String placeholders = works.stream().map(ignored -> "?")
                .collect(java.util.stream.Collectors.joining(", "));
        List<Object> parameters = new ArrayList<>(1 + works.size());
        parameters.add(taskId);
        works.forEach(work -> parameters.add(work.id()));
        Map<String, V2EvidenceWindow> evidence = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT id, allowed_evidence_keys_json, context_evidence_keys_json
                FROM structured_generation_work_item WHERE task_id=? AND id IN (%s)
                ORDER BY id%s
                """.formatted(placeholders, suffix), (org.springframework.jdbc.core.RowCallbackHandler) row ->
                evidence.put(row.getString("id"), new V2EvidenceWindow(
                        recoveryOptionalStringList(row.getString("allowed_evidence_keys_json")),
                        recoveryOptionalStringList(row.getString("context_evidence_keys_json")))),
                parameters.toArray());
        if (evidence.size() != works.size()) throw new InvalidV2RecoverySnapshotException();
        return works.stream().map(work -> {
            V2EvidenceWindow window = evidence.get(work.id());
            return new V2FactWorkRow(work.id(), work.parentWorkItemId(), work.status(), work.skillName(),
                    work.operationName(), work.ordinalStart(), work.ordinalEnd(), work.materialKey(),
                    work.materialDocumentId(), work.sourceLabel(), window.evidenceKeys(), window.contextEvidenceKeys(),
                    work.identityKey(), work.splitDepth(), work.functionKey(), work.testPointKey(),
                    work.acceptedResultSha256(), work.hasLease(), work.runningAttempt());
        }).toList();
    }

    /**
     * Rehydrates and proves one bounded set of retained windows against the immutable material inventory. Failed
     * leaves therefore cannot become retry-eligible merely by carrying syntactically valid but foreign unit ids.
     * [Req-ID]: REQ-TGV2-012
     */
    private List<V2FactWorkRow> exactV2EvidenceBatch(
            String taskId, List<V2FactWorkRow> works, String suffix) {
        List<V2FactWorkRow> hydrated = hydrateV2FactWorkEvidence(taskId, works, suffix);
        Set<DocumentUnitKey> referenced = new LinkedHashSet<>();
        for (V2FactWorkRow work : hydrated) {
            if (!nonBlank(work.materialDocumentId()) || work.ordinalStart() == null || work.ordinalEnd() == null
                    || work.ordinalStart() <= 0 || work.ordinalStart() > work.ordinalEnd()
                    || work.evidenceKeys().isEmpty()
                    || new LinkedHashSet<>(work.evidenceKeys()).size() != work.evidenceKeys().size()
                    || new LinkedHashSet<>(work.contextEvidenceKeys()).size() != work.contextEvidenceKeys().size()
                    || work.evidenceKeys().stream().anyMatch(key -> !nonBlank(key))
                    || work.contextEvidenceKeys().stream().anyMatch(key -> !nonBlank(key))) {
                throw new InvalidV2RecoverySnapshotException();
            }
            Set<String> overlap = new LinkedHashSet<>(work.evidenceKeys());
            overlap.retainAll(work.contextEvidenceKeys());
            if (!overlap.isEmpty()) throw new InvalidV2RecoverySnapshotException();
            java.util.stream.Stream.concat(work.evidenceKeys().stream(), work.contextEvidenceKeys().stream())
                    .forEach(unitId -> referenced.add(new DocumentUnitKey(work.materialDocumentId(), unitId)));
        }
        Map<DocumentUnitKey, Integer> coordinates = new LinkedHashMap<>();
        List<DocumentUnitKey> ordered = List.copyOf(referenced);
        for (int offset = 0; offset < ordered.size(); offset += V2_RECOVERY_UNIT_BATCH_SIZE) {
            List<DocumentUnitKey> batch = ordered.subList(
                    offset, Math.min(offset + V2_RECOVERY_UNIT_BATCH_SIZE, ordered.size()));
            String pairs = batch.stream().map(ignored -> "(?, ?)")
                    .collect(java.util.stream.Collectors.joining(", "));
            List<Object> parameters = new ArrayList<>(1 + batch.size() * 2);
            parameters.add(taskId);
            batch.forEach(key -> {
                parameters.add(key.documentId());
                parameters.add(key.unitId());
            });
            jdbcTemplate.query("""
                    SELECT document_id, unit_id, ordinal FROM material_inventory_unit
                    WHERE task_id=? AND (document_id, unit_id) IN (%s)
                    ORDER BY document_id, ordinal, unit_id%s
                    """.formatted(pairs, suffix), (org.springframework.jdbc.core.RowCallbackHandler) row -> {
                DocumentUnitKey key = new DocumentUnitKey(row.getString("document_id"), row.getString("unit_id"));
                if (coordinates.putIfAbsent(key, row.getInt("ordinal")) != null) {
                    throw new InvalidV2RecoverySnapshotException();
                }
            }, parameters.toArray());
        }
        if (!coordinates.keySet().equals(referenced)) throw new InvalidV2RecoverySnapshotException();
        for (V2FactWorkRow work : hydrated) {
            int expectedSize = work.ordinalEnd() - work.ordinalStart() + 1;
            if (work.evidenceKeys().size() != expectedSize) throw new InvalidV2RecoverySnapshotException();
            for (int index = 0; index < work.evidenceKeys().size(); index++) {
                Integer ordinal = coordinates.get(new DocumentUnitKey(
                        work.materialDocumentId(), work.evidenceKeys().get(index)));
                if (!Objects.equals(ordinal, work.ordinalStart() + index)) {
                    throw new InvalidV2RecoverySnapshotException();
                }
            }
        }
        return hydrated;
    }

    /** Builds every deterministic test-point input once per function for the complete locked recovery snapshot. */
    private static Map<FunctionPointKey, V2GenerationPlanner.TestPointPlan> buildV2TestPointPlans(
            String taskId, Map<String, V2ApprovedFunctionRow> functions,
            Map<String, V2TaskFactProjection> factsByKey) {
        Map<String, List<V2GenerationPlanner.PersistedFact>> factsByFunction = new LinkedHashMap<>();
        try {
            factsByKey.forEach((factKey, fact) -> factsByFunction
                    .computeIfAbsent(fact.functionKey(), ignored -> new ArrayList<>())
                    .add(new V2GenerationPlanner.PersistedFact(factKey,
                            RequirementFactExtractionV2Result.FactType.fromWire(fact.factType()),
                            fact.statement(), fact.quotes())));
        } catch (IllegalArgumentException invalidPersistedFact) {
            // Recovery is a fail-closed predicate: malformed retained facts make every testcase replay ineligible.
            return Map.of();
        }
        Map<FunctionPointKey, V2GenerationPlanner.TestPointPlan> plans = new LinkedHashMap<>();
        V2GenerationPlanner planner = new V2GenerationPlanner();
        for (V2ApprovedFunctionRow row : functions.values()) {
            ApprovedFunctionScope.ApprovedFunction function = new ApprovedFunctionScope.ApprovedFunction(
                    row.functionKey(), row.name(), row.path(), row.description());
            V2GenerationPlanner.TestPointPlan missing = planner.missingFormalFactTestPoint(taskId, function);
            plans.put(new FunctionPointKey(row.functionKey(), missing.input().testPoint().testPointKey()), missing);
            for (V2GenerationPlanner.TestPointPlan plan : planner.testPoints(
                    taskId, function, factsByFunction.getOrDefault(row.functionKey(), List.of()))) {
                plans.put(new FunctionPointKey(row.functionKey(), plan.input().testPoint().testPointKey()), plan);
            }
        }
        return Map.copyOf(plans);
    }

    /**
     * Replays completed fact windows in bounded work batches. Parsed-unit bodies and V23 replay JSON live only for
     * the current batch; the recovery snapshot retains compact validation and task-level quote identities.
     * [Req-ID]: REQ-TGV2-012
     */
    private V2FactProjectionValidation loadCompleteV2FactProjections(
            String taskId, List<V2FactWorkRow> works, Set<String> affectedFunctions,
            Map<String, List<RetryAttemptRow>> attemptsByWork,
            Map<String, String> documentRoles,
            Set<String> unexpectedFactWorkIds, boolean lockRows) {
        List<V2FactWorkRow> completedFactWorks = works.stream()
                .filter(work -> "REQUIREMENT_FACT_EXTRACTION_V2".equals(work.operationName()))
                .filter(work -> "COMPLETED".equals(work.status()))
                .toList();
        Map<String, Boolean> completeByWork = new LinkedHashMap<>();
        Set<String> expectedFacts = new LinkedHashSet<>();
        Map<String, Set<String>> expectedQuotes = new LinkedHashMap<>();
        String suffix = lockRows ? " FOR UPDATE" : "";
        for (int offset = 0; offset < completedFactWorks.size(); offset += V2_FACT_RECOVERY_WORK_BATCH_SIZE) {
            List<V2FactWorkRow> batch = completedFactWorks.subList(
                    offset, Math.min(offset + V2_FACT_RECOVERY_WORK_BATCH_SIZE, completedFactWorks.size()));
            List<V2FactWorkRow> hydratedBatch = hydrateV2FactWorkEvidence(taskId, batch, suffix);
            Set<String> batchFunctionKeys = hydratedBatch.stream().map(V2FactWorkRow::functionKey)
                    .filter(GenerationTaskRepository::nonBlank)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            Map<String, V2ApprovedFunctionRow> batchFunctions = loadV2ApprovedFunctions(
                    taskId, batchFunctionKeys, suffix);
            Map<String, List<V2ReplayPublication>> publications = loadV2ReplayPublications(
                    taskId, hydratedBatch, "requirement_facts", suffix);
            Map<String, Map<String, RequirementFactExtractionV2Input.MaterialUnit>> units =
                    loadV2FactUnits(taskId, hydratedBatch, suffix);
            V2FactBusinessBatch business = loadV2FactBusinessBatch(
                    taskId, hydratedBatch, publications, suffix);
            V2FactRecoveryData data = new V2FactRecoveryData(batchFunctions, documentRoles, units, publications,
                    business.factsByKey(), business.firstOwnedFactsByWork(), business.observationsByWork(),
                    unexpectedFactWorkIds);
            for (V2FactWorkRow work : hydratedBatch) {
                var accepted = validatedV2FactProjection(
                        work, affectedFunctions.contains(work.functionKey()), data);
                boolean complete = hasUniqueSuccessfulAttempt(attemptsByWork.getOrDefault(work.id(), List.of()))
                        && accepted != null;
                completeByWork.put(work.id(), complete);
                if (!complete) continue;
                for (var fact : accepted.facts()) {
                    expectedFacts.add(fact.factKey());
                    expectedQuotes.computeIfAbsent(fact.factKey(), ignored -> new LinkedHashSet<>()).addAll(
                            fact.sourceQuotes().stream().map(quote ->
                                    quoteIdentity(quote.evidenceKey(), quote.quote())).toList());
                }
            }
        }
        return new V2FactProjectionValidation(Map.copyOf(completeByWork),
                hasExactTaskV2FactProjection(expectedFacts, expectedQuotes, taskId, suffix));
    }

    /** Loads only the durable fact/feedback rows referenced by the current recovery work batch. */
    private V2FactBusinessBatch loadV2FactBusinessBatch(String taskId, List<V2FactWorkRow> works,
            Map<String, List<V2ReplayPublication>> publications, String suffix) {
        Set<String> candidateFactKeys = new LinkedHashSet<>();
        try {
            for (List<V2ReplayPublication> workPublications : publications.values()) {
                for (V2ReplayPublication publication : workPublications) {
                    RequirementFactExtractionV2Result result = strictRecoveryValue(
                            publication.validatedResultReplayJson(), RequirementFactExtractionV2Result.class);
                    if (result == null) throw new InvalidV2RecoverySnapshotException();
                    result.requirementFacts().forEach(fact -> candidateFactKeys.add(
                            com.testcaseagent.validation.RequirementFactV2Validator.stableFactKey(
                                    result.functionKey(), fact.factType(), fact.statement())));
                }
            }
        } catch (JsonProcessingException | IllegalArgumentException | NullPointerException invalidReplay) {
            throw new InvalidV2RecoverySnapshotException();
        }

        Map<String, V2TaskFactProjection> factsByKey = new LinkedHashMap<>();
        if (!candidateFactKeys.isEmpty()) {
            List<String> factKeys = List.copyOf(candidateFactKeys);
            for (int offset = 0; offset < factKeys.size(); offset += V2_RECOVERY_UNIT_BATCH_SIZE) {
                List<String> keyBatch = factKeys.subList(
                        offset, Math.min(offset + V2_RECOVERY_UNIT_BATCH_SIZE, factKeys.size()));
                String placeholders = keyBatch.stream().map(ignored -> "?")
                        .collect(java.util.stream.Collectors.joining(", "));
                List<Object> parameters = new ArrayList<>(1 + keyBatch.size());
                parameters.add(taskId);
                parameters.addAll(keyBatch);
                jdbcTemplate.query("""
                        SELECT fact_key, first_work_item_id, function_key, fact_type, statement_text
                        FROM v2_requirement_fact WHERE task_id=? AND fact_key IN (%s)
                        ORDER BY fact_key%s
                        """.formatted(placeholders, suffix), (org.springframework.jdbc.core.RowCallbackHandler) row -> {
                    String factKey = row.getString("fact_key");
                    factsByKey.put(factKey, new V2TaskFactProjection(row.getString("first_work_item_id"),
                            row.getString("function_key"), row.getString("fact_type"), row.getString("statement_text"),
                            new ArrayList<>()));
                }, parameters.toArray());
                jdbcTemplate.query("""
                        SELECT fact_key, evidence_key, quote_text FROM v2_requirement_fact_quote
                        WHERE task_id=? AND fact_key IN (%s)
                        ORDER BY fact_key, evidence_key, quote_sha256%s
                        """.formatted(placeholders, suffix), (org.springframework.jdbc.core.RowCallbackHandler) row -> {
                    V2TaskFactProjection fact = factsByKey.get(row.getString("fact_key"));
                    if (fact != null) fact.quotes().add(new StructuredSourceQuoteV2(
                            row.getString("evidence_key"), row.getString("quote_text")));
                }, parameters.toArray());
            }
        }

        String workPlaceholders = works.stream().map(ignored -> "?")
                .collect(java.util.stream.Collectors.joining(", "));
        List<Object> workParameters = new ArrayList<>(1 + works.size());
        workParameters.add(taskId);
        works.forEach(work -> workParameters.add(work.id()));
        Map<String, List<String>> firstOwnedFactsByWork = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT first_work_item_id, fact_key FROM v2_requirement_fact
                WHERE task_id=? AND first_work_item_id IN (%s)
                ORDER BY first_work_item_id, fact_key%s
                """.formatted(workPlaceholders, suffix), (org.springframework.jdbc.core.RowCallbackHandler) row ->
                firstOwnedFactsByWork.computeIfAbsent(row.getString("first_work_item_id"), ignored -> new ArrayList<>())
                        .add(row.getString("fact_key")), workParameters.toArray());

        Map<String, List<V2PersistedObservationRow>> observationsByWork = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT feedback.work_item_id, feedback.feedback_key, feedback.function_key, feedback.window_key,
                       feedback.observation_type, feedback.description_text, feedback.affected_fact_types_json,
                       quote.evidence_key, quote.quote_text
                FROM v2_testability_feedback feedback
                LEFT JOIN v2_testability_feedback_quote quote
                  ON quote.task_id=feedback.task_id AND quote.feedback_key=feedback.feedback_key
                WHERE feedback.task_id=? AND feedback.work_item_id IN (%s)
                ORDER BY feedback.work_item_id, feedback.feedback_key, quote.evidence_key, quote.quote_sha256%s
                """.formatted(workPlaceholders, suffix), (org.springframework.jdbc.core.RowCallbackHandler) row ->
                observationsByWork.computeIfAbsent(row.getString("work_item_id"), ignored -> new ArrayList<>())
                        .add(new V2PersistedObservationRow(row.getString("feedback_key"),
                                row.getString("function_key"), row.getString("window_key"),
                                row.getString("observation_type"), row.getString("description_text"),
                                row.getString("affected_fact_types_json"), row.getString("evidence_key"),
                                row.getString("quote_text"))), workParameters.toArray());
        return new V2FactBusinessBatch(factsByKey, firstOwnedFactsByWork, observationsByWork);
    }

    private Map<String, List<V2ReplayPublication>> loadV2ReplayPublications(
            String taskId, List<V2FactWorkRow> works, String publicationType, String suffix) {
        if (works.isEmpty()) return Map.of();
        String placeholders = works.stream().map(ignored -> "?")
                .collect(java.util.stream.Collectors.joining(", "));
        List<Object> parameters = new ArrayList<>(2 + works.size());
        parameters.add(taskId);
        parameters.add(publicationType);
        works.forEach(work -> parameters.add(work.id()));
        Map<String, List<V2ReplayPublication>> publications = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT work_item_id, publication_type, input_sha256, result_sha256, validated_result_replay_json
                FROM v2_work_publication
                WHERE task_id=? AND publication_type=? AND work_item_id IN (%s)
                ORDER BY work_item_id%s
                """.formatted(placeholders, suffix), (org.springframework.jdbc.core.RowCallbackHandler) row ->
                publications.computeIfAbsent(row.getString("work_item_id"), ignored -> new ArrayList<>()).add(
                        new V2ReplayPublication(row.getString("publication_type"), row.getString("input_sha256"),
                                row.getString("result_sha256"), row.getString("validated_result_replay_json"))),
                parameters.toArray());
        return publications;
    }

    private Map<String, Map<String, RequirementFactExtractionV2Input.MaterialUnit>> loadV2FactUnits(
            String taskId, List<V2FactWorkRow> works, String suffix) {
        List<DocumentUnitKey> referencedUnits = works.stream()
                .filter(work -> nonBlank(work.materialDocumentId()))
                .flatMap(work -> java.util.stream.Stream.concat(
                        work.evidenceKeys().stream(), work.contextEvidenceKeys().stream())
                        .filter(GenerationTaskRepository::nonBlank)
                        .map(unitId -> new DocumentUnitKey(work.materialDocumentId(), unitId)))
                .distinct()
                .sorted(java.util.Comparator.comparing(DocumentUnitKey::documentId)
                        .thenComparing(DocumentUnitKey::unitId))
                .toList();
        Map<String, Map<String, RequirementFactExtractionV2Input.MaterialUnit>> units = new LinkedHashMap<>();
        for (int offset = 0; offset < referencedUnits.size(); offset += V2_RECOVERY_UNIT_BATCH_SIZE) {
            List<DocumentUnitKey> batch = referencedUnits.subList(
                    offset, Math.min(offset + V2_RECOVERY_UNIT_BATCH_SIZE, referencedUnits.size()));
            String pairs = batch.stream().map(ignored -> "(?, ?)")
                    .collect(java.util.stream.Collectors.joining(", "));
            List<Object> parameters = new ArrayList<>(1 + batch.size() * 2);
            parameters.add(taskId);
            batch.forEach(key -> {
                parameters.add(key.documentId());
                parameters.add(key.unitId());
            });
            jdbcTemplate.query("""
                    SELECT document_id, unit_id, ordinal, content FROM material_inventory_unit
                    WHERE task_id=? AND (document_id, unit_id) IN (%s)
                    ORDER BY document_id, ordinal, unit_id%s
                    """.formatted(pairs, suffix), (org.springframework.jdbc.core.RowCallbackHandler) row -> units
                    .computeIfAbsent(row.getString("document_id"), ignored -> new LinkedHashMap<>())
                    .put(row.getString("unit_id"), new RequirementFactExtractionV2Input.MaterialUnit(
                            row.getString("unit_id"), row.getInt("ordinal"), row.getString("content"))),
                    parameters.toArray());
        }
        return units;
    }

    /**
     * Rebuilds completed testcase projections in fixed work batches. Raw cases, steps, and bindings are discarded after
     * each batch, so recovery does not retain the whole task's large testcase payload while task rows are locked.
     */
    private Map<String, Boolean> loadCompleteV2TestcaseProjections(
            String taskId, List<V2FactWorkRow> works,
            Map<String, List<RetryAttemptRow>> attemptsByWork,
            Set<String> unexpectedTestcaseWorkIds, boolean lockRows) {
        List<V2FactWorkRow> testcaseWorks = works.stream()
                .filter(work -> "FUNCTIONAL_TESTCASE_DESIGN_V2".equals(work.operationName()))
                .filter(work -> "COMPLETED".equals(work.status()) || "SUPERSEDED".equals(work.status()))
                .toList();
        Map<String, Boolean> completed = new LinkedHashMap<>();
        String suffix = lockRows ? " FOR UPDATE" : "";
        for (int offset = 0; offset < testcaseWorks.size(); offset += V2_TESTCASE_RECOVERY_WORK_BATCH_SIZE) {
            List<V2FactWorkRow> batch = testcaseWorks.subList(
                    offset, Math.min(offset + V2_TESTCASE_RECOVERY_WORK_BATCH_SIZE, testcaseWorks.size()));
            V2TestcaseBusinessBatch business = loadV2TestcaseBusinessBatch(
                    taskId, batch, suffix);
            Map<String, List<V2ReplayPublication>> publicationsByWork = loadV2ReplayPublications(
                    taskId, batch, "testcase_design", suffix);
            String placeholders = batch.stream().map(ignored -> "?")
                    .collect(java.util.stream.Collectors.joining(", "));
            List<Object> parameters = new ArrayList<>(1 + batch.size());
            parameters.add(taskId);
            batch.forEach(work -> parameters.add(work.id()));

            Map<String, List<V2PersistedTestcaseRow>> testcasesByWork = new LinkedHashMap<>();
            jdbcTemplate.query("""
                    SELECT work_item_id, case_key, name_text, title, priority, preconditions_json,
                           hardware_configuration_json, software_configuration_json, test_configuration_json,
                           parameter_configuration_json, inputs_json, expected_results_json, evaluation_criteria,
                           result_evaluation_criteria, termination_conditions_json, result_collection,
                           case_status, missing_information_json
                    FROM structured_test_case WHERE task_id=? AND work_item_id IN (%s)
                    ORDER BY work_item_id, case_key%s
                    """.formatted(placeholders, suffix), (org.springframework.jdbc.core.RowCallbackHandler) row ->
                    testcasesByWork.computeIfAbsent(row.getString("work_item_id"), ignored -> new ArrayList<>())
                            .add(new V2PersistedTestcaseRow(row.getString("case_key"), row.getString("name_text"),
                                    row.getString("title"), row.getString("priority"),
                                    row.getString("preconditions_json"),
                                    row.getString("hardware_configuration_json"),
                                    row.getString("software_configuration_json"),
                                    row.getString("test_configuration_json"),
                                    row.getString("parameter_configuration_json"), row.getString("inputs_json"),
                                    row.getString("expected_results_json"), row.getString("evaluation_criteria"),
                                    row.getString("result_evaluation_criteria"),
                                    row.getString("termination_conditions_json"), row.getString("result_collection"),
                                    row.getString("case_status"), row.getString("missing_information_json"))),
                    parameters.toArray());

            Map<WorkCaseKey, List<FunctionalTestcaseDesignV2Result.Step>> stepsByWorkCase = new LinkedHashMap<>();
            jdbcTemplate.query("""
                    SELECT step.work_item_id, step.case_key, step.step_no, step.action_text, step.expected_text,
                           step.evaluation_criteria, step.termination_or_error, step.result_collection
                    FROM structured_test_case_step step
                    JOIN structured_generation_work_item work ON work.id=step.work_item_id
                    WHERE work.task_id=? AND step.work_item_id IN (%s)
                    ORDER BY step.work_item_id, step.case_key, step.step_no%s
                    """.formatted(placeholders, suffix), (org.springframework.jdbc.core.RowCallbackHandler) row ->
                    stepsByWorkCase.computeIfAbsent(
                            new WorkCaseKey(row.getString("work_item_id"), row.getString("case_key")),
                            ignored -> new ArrayList<>()).add(new FunctionalTestcaseDesignV2Result.Step(
                                    row.getInt("step_no"), row.getString("action_text"),
                                    row.getString("expected_text"), row.getString("evaluation_criteria"),
                                    row.getString("termination_or_error"), row.getString("result_collection"))),
                    parameters.toArray());

            Map<BindingLookupKey, List<String>> bindingReferencesByKey = new LinkedHashMap<>();
            Map<String, Integer> bindingCountsByWork = new LinkedHashMap<>();
            jdbcTemplate.query("""
                    SELECT binding.work_item_id, binding.subject_key, binding.subject_type,
                           binding.reference_type, binding.reference_key
                    FROM structured_reference_binding binding
                    JOIN structured_generation_work_item work ON work.id=binding.work_item_id
                    WHERE work.task_id=? AND binding.work_item_id IN (%s)
                    ORDER BY binding.work_item_id, binding.subject_key, binding.subject_type,
                             binding.reference_type, binding.reference_key%s
                    """.formatted(placeholders, suffix), (org.springframework.jdbc.core.RowCallbackHandler) row -> {
                String workItemId = row.getString("work_item_id");
                BindingLookupKey key = new BindingLookupKey(workItemId, row.getString("subject_key"),
                        row.getString("subject_type"), row.getString("reference_type"));
                bindingReferencesByKey.computeIfAbsent(key, ignored -> new ArrayList<>())
                        .add(row.getString("reference_key"));
                bindingCountsByWork.merge(workItemId, 1, Integer::sum);
            }, parameters.toArray());

            V2TestcaseRecoveryData data = new V2TestcaseRecoveryData(attemptsByWork, publicationsByWork,
                    business.testPointsByWork(), business.outcomesByWork(), business.testPointPlans(),
                    testcasesByWork, stepsByWorkCase,
                    bindingReferencesByKey, bindingCountsByWork, unexpectedTestcaseWorkIds);
            batch.forEach(work -> completed.put(work.id(), hasCompleteV2TestcaseProjection(taskId, work, data)));
        }
        return Map.copyOf(completed);
    }

    /** Loads task-point inputs and outcomes only for the current testcase recovery work batch. */
    private V2TestcaseBusinessBatch loadV2TestcaseBusinessBatch(
            String taskId, List<V2FactWorkRow> works, String suffix) {
        String placeholders = works.stream().map(ignored -> "?")
                .collect(java.util.stream.Collectors.joining(", "));
        List<Object> parameters = new ArrayList<>(1 + works.size());
        parameters.add(taskId);
        works.forEach(work -> parameters.add(work.id()));
        Map<String, List<V2TestPointRow>> testPointsByWork = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT work_item_id, function_key, test_point_key, function_name, test_point_type, basis,
                       description, missing_information_json, formal_coverage_satisfied
                FROM structured_test_point WHERE task_id=? AND work_item_id IN (%s)
                ORDER BY work_item_id, test_point_key%s
                """.formatted(placeholders, suffix), (org.springframework.jdbc.core.RowCallbackHandler) row ->
                testPointsByWork.computeIfAbsent(row.getString("work_item_id"), ignored -> new ArrayList<>())
                        .add(new V2TestPointRow(row.getString("function_key"), row.getString("test_point_key"),
                                row.getString("function_name"), row.getString("test_point_type"),
                                row.getString("basis"), row.getString("description"),
                                recoveryStringList(row.getString("missing_information_json")),
                                row.getBoolean("formal_coverage_satisfied"))), parameters.toArray());
        Map<String, List<V2GenerationOutcomeRow>> outcomesByWork = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT work_item_id, function_key, test_point_key, generation_outcome,
                       missing_information_json, formal_coverage_satisfied
                FROM v2_generation_outcome WHERE task_id=? AND work_item_id IN (%s)
                ORDER BY work_item_id%s
                """.formatted(placeholders, suffix), (org.springframework.jdbc.core.RowCallbackHandler) row ->
                outcomesByWork.computeIfAbsent(row.getString("work_item_id"), ignored -> new ArrayList<>())
                        .add(new V2GenerationOutcomeRow(row.getString("function_key"),
                                row.getString("test_point_key"), row.getString("generation_outcome"),
                                recoveryStringList(row.getString("missing_information_json")),
                                row.getBoolean("formal_coverage_satisfied"))), parameters.toArray());

        Set<String> functionKeys = works.stream().map(V2FactWorkRow::functionKey)
                .filter(GenerationTaskRepository::nonBlank)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, V2ApprovedFunctionRow> batchFunctions = loadV2ApprovedFunctions(
                taskId, functionKeys, suffix);
        Map<String, V2TaskFactProjection> facts = loadV2FactsForFunctions(taskId, functionKeys, suffix);
        return new V2TestcaseBusinessBatch(testPointsByWork, outcomesByWork,
                buildV2TestPointPlans(taskId, batchFunctions, facts));
    }

    private Map<String, V2TaskFactProjection> loadV2FactsForFunctions(
            String taskId, Set<String> functionKeys, String suffix) {
        if (functionKeys.isEmpty()) return Map.of();
        String placeholders = functionKeys.stream().map(ignored -> "?")
                .collect(java.util.stream.Collectors.joining(", "));
        List<Object> parameters = new ArrayList<>(1 + functionKeys.size());
        parameters.add(taskId);
        parameters.addAll(functionKeys);
        Map<String, V2TaskFactProjection> facts = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT fact_key, first_work_item_id, function_key, fact_type, statement_text
                FROM v2_requirement_fact WHERE task_id=? AND function_key IN (%s)
                ORDER BY fact_key%s
                """.formatted(placeholders, suffix), (org.springframework.jdbc.core.RowCallbackHandler) row -> {
            String factKey = row.getString("fact_key");
            facts.put(factKey, new V2TaskFactProjection(row.getString("first_work_item_id"),
                    row.getString("function_key"), row.getString("fact_type"), row.getString("statement_text"),
                    new ArrayList<>()));
        }, parameters.toArray());
        if (facts.isEmpty()) return facts;
        List<String> factKeys = List.copyOf(facts.keySet());
        for (int offset = 0; offset < factKeys.size(); offset += V2_RECOVERY_UNIT_BATCH_SIZE) {
            List<String> keyBatch = factKeys.subList(
                    offset, Math.min(offset + V2_RECOVERY_UNIT_BATCH_SIZE, factKeys.size()));
            String factPlaceholders = keyBatch.stream().map(ignored -> "?")
                    .collect(java.util.stream.Collectors.joining(", "));
            List<Object> factParameters = new ArrayList<>(1 + keyBatch.size());
            factParameters.add(taskId);
            factParameters.addAll(keyBatch);
            jdbcTemplate.query("""
                    SELECT fact_key, evidence_key, quote_text FROM v2_requirement_fact_quote
                    WHERE task_id=? AND fact_key IN (%s)
                    ORDER BY fact_key, evidence_key, quote_sha256%s
                    """.formatted(factPlaceholders, suffix), (org.springframework.jdbc.core.RowCallbackHandler) row -> {
                V2TaskFactProjection fact = facts.get(row.getString("fact_key"));
                if (fact != null) fact.quotes().add(new StructuredSourceQuoteV2(
                        row.getString("evidence_key"), row.getString("quote_text")));
            }, factParameters.toArray());
        }
        return facts;
    }

    private Set<String> taskWorkIds(String table, String taskId, boolean lockRows) {
        return new LinkedHashSet<>(jdbcTemplate.queryForList("SELECT owned.work_item_id FROM " + table
                + " owned JOIN structured_generation_work_item work ON work.id=owned.work_item_id"
                + " WHERE work.task_id=?" + (lockRows ? " FOR UPDATE" : ""), String.class, taskId));
    }

    private boolean isExactSplit(
            V2FactWorkRow parent, V2FactWorkRow left, V2FactWorkRow right) {
        List<String> childEvidence = new ArrayList<>(left.evidenceKeys());
        childEvidence.addAll(right.evidenceKeys());
        return Objects.equals(parent.skillName(), left.skillName())
                && Objects.equals(parent.skillName(), right.skillName())
                && Objects.equals(parent.operationName(), left.operationName())
                && Objects.equals(parent.operationName(), right.operationName())
                && Objects.equals(parent.materialKey(), left.materialKey())
                && Objects.equals(parent.materialKey(), right.materialKey())
                && Objects.equals(parent.materialDocumentId(), left.materialDocumentId())
                && Objects.equals(parent.materialDocumentId(), right.materialDocumentId())
                && Objects.equals(parent.sourceLabel(), left.sourceLabel())
                && Objects.equals(parent.sourceLabel(), right.sourceLabel())
                && Objects.equals(parent.functionKey(), left.functionKey())
                && Objects.equals(parent.functionKey(), right.functionKey())
                && left.splitDepth() == parent.splitDepth() + 1 && right.splitDepth() == parent.splitDepth() + 1
                && Objects.equals(parent.ordinalStart(), left.ordinalStart())
                && left.ordinalEnd() != null && right.ordinalStart() != null
                && left.ordinalEnd() + 1 == right.ordinalStart()
                && Objects.equals(parent.ordinalEnd(), right.ordinalEnd())
                && parent.evidenceKeys().equals(childEvidence);
    }

    private boolean isSafeV2DirectEvidenceWorkGraph(String taskId, List<RetryWorkRow> unfinished,
            Set<String> affectedFunctions, V2RecoverySnapshot snapshot) {
        List<V2FactWorkRow> rows = snapshot.works();
        Set<String> failedIds = unfinished.stream().map(RetryWorkRow::id)
                .collect(java.util.stream.Collectors.toSet());
        Map<String, V2FactWorkRow> byId = new LinkedHashMap<>();
        Map<String, List<V2FactWorkRow>> children = new LinkedHashMap<>();
        for (V2FactWorkRow row : rows) {
            if (byId.putIfAbsent(row.id(), row) != null || row.hasLease() || row.runningAttempt()) return false;
            if (row.parentWorkItemId() != null) {
                children.computeIfAbsent(row.parentWorkItemId(), ignored -> new ArrayList<>()).add(row);
            }
            if ("REQUIREMENT_FACT_EXTRACTION_V2".equals(row.operationName())) {
                if (!"requirement-fact-extraction".equals(row.skillName())) return false;
                if ("FAILED".equals(row.status())) {
                    if (!failedIds.contains(row.id()) || row.acceptedResultSha256() != null
                            || snapshot.allWorkOwnedBusinessIds().contains(row.id())) return false;
                } else if ("SPLIT".equals(row.status())) {
                    if (row.acceptedResultSha256() != null
                            || snapshot.allWorkOwnedBusinessIds().contains(row.id())) return false;
                } else if ("COMPLETED".equals(row.status())) {
                    List<RetryAttemptRow> attempts = snapshot.attemptsByWork()
                            .getOrDefault(row.id(), List.of());
                    if (!hasUniqueSuccessfulAttempt(attempts)
                            || !nonBlank(row.acceptedResultSha256())
                            || !hasExactV2Publication(snapshot, row.id(), "requirement_facts",
                                    row.acceptedResultSha256())
                            || !snapshot.completeFactProjections().getOrDefault(row.id(), false)) return false;
                } else {
                    return false;
                }
            } else if ("FUNCTIONAL_TESTCASE_DESIGN_V2".equals(row.operationName())) {
                if (!"functional-testcase-design".equals(row.skillName())
                        || !("COMPLETED".equals(row.status()) || "SUPERSEDED".equals(row.status()))
                        || !nonBlank(row.acceptedResultSha256())
                        || !snapshot.completeTestcaseProjections().getOrDefault(row.id(), false)) return false;
            } else {
                return false;
            }
        }
        if (!byId.keySet().containsAll(failedIds)) return false;
        for (V2FactWorkRow row : rows) {
            if ("SPLIT".equals(row.status())) {
                List<V2FactWorkRow> direct = children.getOrDefault(row.id(), List.of()).stream()
                        .sorted(java.util.Comparator.comparing(V2FactWorkRow::ordinalStart)).toList();
                if (direct.size() != 2) return false;
            } else if (row.parentWorkItemId() != null) {
                V2FactWorkRow parent = byId.get(row.parentWorkItemId());
                if (parent == null || !"SPLIT".equals(parent.status())) return false;
            }
        }
        return snapshot.exactSplitLineage() && snapshot.exactFailedLeafEvidence()
                && snapshot.exactTaskFactProjection();
    }

    private static boolean hasExactV2Publication(V2RecoverySnapshot snapshot, String workItemId,
            String publicationType, String acceptedResultSha256) {
        List<V2ReplayPublication> publications = snapshot.publicationsByWork()
                .getOrDefault(workItemId, List.of()).stream()
                .filter(publication -> publicationType.equals(publication.publicationType())).toList();
        return publications.size() == 1 && acceptedResultSha256.equals(publications.get(0).resultSha256());
    }

    /** Replays one validated public fact result and proves that its durable projection is still a closed subset. */
    private com.testcaseagent.validation.RequirementFactV2Validator.AcceptedWindow validatedV2FactProjection(
            V2FactWorkRow work, boolean requireEmptyResult, V2FactRecoveryData data) {
        List<V2ReplayPublication> publications = data.publicationsByWork()
                .getOrDefault(work.id(), List.of()).stream()
                .filter(publication -> "requirement_facts".equals(publication.publicationType())).toList();
        if (!nonBlank(work.acceptedResultSha256()) || publications.size() != 1
                || !nonBlank(publications.get(0).validatedResultReplayJson())) return null;
        try {
            V2ReplayPublication publication = publications.get(0);
            RequirementFactExtractionV2Result original = strictRecoveryValue(
                    publication.validatedResultReplayJson(), RequirementFactExtractionV2Result.class);
            if (original == null || !work.acceptedResultSha256().equals(hashJson(original))
                    || !work.acceptedResultSha256().equals(publication.resultSha256())) return null;
            RequirementFactExtractionV2Input input = replayableFactInput(work, data);
            if (input == null || !hashJson(input).equals(publication.inputSha256())) return null;
            var accepted = new com.testcaseagent.validation.RequirementFactV2Validator().validate(input, original);
            if (requireEmptyResult && (!accepted.facts().isEmpty() || !accepted.observations().isEmpty())) return null;
            Set<String> acceptedKeys = accepted.facts().stream()
                    .map(com.testcaseagent.validation.RequirementFactV2Validator.AcceptedFact::factKey)
                    .collect(java.util.stream.Collectors.toSet());
            List<String> firstOwned = data.firstOwnedFactsByWork().getOrDefault(work.id(), List.of());
            if (!acceptedKeys.containsAll(firstOwned)) return null;
            for (var fact : accepted.facts()) {
                V2TaskFactProjection stored = data.factsByKey().get(fact.factKey());
                if (stored == null || !work.functionKey().equals(stored.functionKey())
                        || !fact.factType().wireValue().equals(stored.factType())
                        || !normalizedV2FactText(fact.statement()).equals(
                                normalizedV2FactText(stored.statement()))) return null;
                Set<String> storedQuotes = stored.quotes().stream()
                        .map(quote -> quoteIdentity(quote.evidenceKey(), quote.quote()))
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                Set<String> expectedQuotes = fact.sourceQuotes().stream()
                        .map(quote -> quoteIdentity(quote.evidenceKey(), quote.quote()))
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                if (!storedQuotes.containsAll(expectedQuotes)) return null;
            }
            return observationIdentities(work.functionKey(), input.windowKey(), accepted.observations()).equals(
                    persistedObservationIdentities(
                            data.observationsByWork().getOrDefault(work.id(), List.of()),
                            work.functionKey(), input.windowKey()))
                    && !data.unexpectedFactWorkIds().contains(work.id()) ? accepted : null;
        } catch (IllegalArgumentException | JsonProcessingException invalidProjection) {
            return null;
        }
    }

    private RequirementFactExtractionV2Input replayableFactInput(
            V2FactWorkRow work, V2FactRecoveryData data) {
        V2ApprovedFunctionRow function = data.functionsByKey().get(work.functionKey());
        String role = data.documentRoles().get(work.materialDocumentId());
        if (function == null || role == null) return null;
        List<RequirementFactExtractionV2Input.MaterialUnit> units = persistedFactUnits(
                work.materialDocumentId(), work.evidenceKeys(), data);
        List<RequirementFactExtractionV2Input.MaterialUnit> context = persistedFactUnits(
                work.materialDocumentId(), work.contextEvidenceKeys(), data);
        if (units.size() != work.evidenceKeys().size()
                || context.size() != work.contextEvidenceKeys().size()) return null;
        return new RequirementFactExtractionV2Input(function.functionKey(), function.name(), function.path(),
                function.description(), work.materialKey(), v2FactContentType(role), work.identityKey(), units, context);
    }

    /**
     * Reconstructs the public V2 material type from the immutable inventory role used by the original planner.
     * The persisted input hash remains the final guard against any historical mapping drift. [Req-ID]: REQ-TGV2-012
     */
    private static MaterialContentTypeKey v2FactContentType(String documentRole) {
        return switch (documentRole) {
            case "REQUIREMENT" -> MaterialContentTypeKey.REQUIREMENTS_SPEC;
            case "WORK_ORDER_PLAN" -> MaterialContentTypeKey.WORK_ORDER_PLAN;
            default -> throw new IllegalArgumentException("Unsupported V2 fact material role");
        };
    }

    private List<RequirementFactExtractionV2Input.MaterialUnit> persistedFactUnits(
            String documentId, List<String> keys, V2FactRecoveryData data) {
        if (keys.isEmpty()) return List.of();
        Map<String, RequirementFactExtractionV2Input.MaterialUnit> byKey = data.unitsByDocument()
                .getOrDefault(documentId, Map.of());
        List<RequirementFactExtractionV2Input.MaterialUnit> ordered = keys.stream().map(byKey::get).toList();
        return ordered.stream().anyMatch(Objects::isNull) ? List.of() : ordered;
    }

    private Map<String, String> persistedObservationIdentities(
            List<V2PersistedObservationRow> rows, String expectedFunctionKey, String expectedWindowKey) {
        Map<String, V2PersistedObservationAccumulator> accumulated = new LinkedHashMap<>();
        for (V2PersistedObservationRow row : rows) {
            if (!expectedFunctionKey.equals(row.functionKey()) || !expectedWindowKey.equals(row.windowKey())) {
                return Map.of("__scope_mismatch__", "true");
            }
            V2PersistedObservationAccumulator value = accumulated.computeIfAbsent(row.feedbackKey(), ignored ->
                    new V2PersistedObservationAccumulator(row.observationType(), row.description(),
                            recoveryStringList(row.affectedFactTypes()), new ArrayList<>()));
            if (row.evidenceKey() != null && row.quote() != null) {
                value.quotes().add(new StructuredSourceQuoteV2(row.evidenceKey(), row.quote()));
            }
        }
        Map<String, String> identities = new LinkedHashMap<>();
        accumulated.forEach((feedbackKey, value) -> identities.put(feedbackKey,
                observationIdentity(value.observationType(), value.description(),
                        value.affectedFactTypes(), value.quotes())));
        return Map.copyOf(identities);
    }

    private Map<String, String> observationIdentities(String functionKey, String windowKey,
            List<com.testcaseagent.validation.RequirementFactV2Validator.AcceptedObservation> observations) {
        Map<String, String> identities = new LinkedHashMap<>();
        for (var value : observations) {
            String key = "feedback-" + sha256Text("testability-feedback-v2\n" + functionKey + "\n"
                    + windowKey + "\n" + value.observationType().wireValue() + "\n" + value.description());
            String identity = observationIdentity(value.observationType().wireValue(), value.description(),
                    value.affectedFactTypes().stream()
                            .map(RequirementFactExtractionV2Result.FactType::wireValue).toList(),
                    value.sourceQuotes());
            if (identities.putIfAbsent(key, identity) != null) return Map.of("__duplicate__", "true");
        }
        return Map.copyOf(identities);
    }

    private String observationIdentity(String type, String description, List<String> factTypes,
            List<StructuredSourceQuoteV2> quotes) {
        List<String> quoteIds = quotes.stream().map(quote -> quoteIdentity(quote.evidenceKey(), quote.quote()))
                .sorted().toList();
        return hashJson(List.of(type, description, factTypes, quoteIds));
    }

    private static String quoteIdentity(String evidenceKey, String quote) {
        return sha256Text(evidenceKey + "\u0000" + quote);
    }

    private static String normalizedV2FactText(String value) {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ").strip();
    }

    private boolean hasUnexpectedCompletedFactBusinessRows(String workItemId, boolean lockRows) {
        for (String table : UNFINISHED_WORK_BUSINESS_TABLES) {
            if (!jdbcTemplate.queryForList("SELECT work_item_id FROM " + table
                    + " WHERE work_item_id=? LIMIT 1" + (lockRows ? " FOR UPDATE" : ""),
                    String.class, workItemId).isEmpty()) return true;
        }
        for (String table : RECONCILIATION_WORK_OWNED_TABLES) {
            if (!jdbcTemplate.queryForList("SELECT work_item_id FROM " + table
                    + " WHERE work_item_id=? LIMIT 1" + (lockRows ? " FOR UPDATE" : ""),
                    String.class, workItemId).isEmpty()) return true;
        }
        return false;
    }

    /** Failed or split work must not retain a publication ledger from a result that was never accepted. */
    private boolean hasAnyV2Publication(String taskId, String workItemId, boolean lockRows) {
        return !jdbcTemplate.queryForList("""
                SELECT work_item_id FROM v2_work_publication
                WHERE task_id=? AND work_item_id=? LIMIT 1%s
                """.formatted(lockRows ? " FOR UPDATE" : ""), String.class, taskId, workItemId).isEmpty();
    }

    /**
     * Re-proves that a retained completed testcase work owns the minimum atomic V2 projection before fact recovery
     * may skip it. This prevents a status/hash shell with missing publication rows from becoming permanent history.
     * [Req-ID]: REQ-TGV2-012
     */
    private boolean hasCompleteV2TestcaseProjection(
            String taskId, V2FactWorkRow work, V2TestcaseRecoveryData data) {
        if (!nonBlank(work.acceptedResultSha256())) return false;
        List<RetryAttemptRow> attempts = data.attemptsByWork().getOrDefault(work.id(), List.of());
        if (!hasUniqueSuccessfulAttempt(attempts)) return false;
        boolean historicalMissingFactFallback = V2GenerationPlanner.missingFormalFactPointKey(
                taskId, work.functionKey()).equals(work.testPointKey());
        V2GenerationPlanner.TestPointPlan plan = expectedV2TestPointPlan(work, data);
        if (plan == null) return false;
        List<V2TestPointRow> points = data.testPointsByWork().getOrDefault(work.id(), List.of());
        List<V2GenerationOutcomeRow> outcomes = data.outcomesByWork().getOrDefault(work.id(), List.of());
        List<V2ReplayPublication> publications = data.publicationsByWork().getOrDefault(work.id(), List.of()).stream()
                .filter(publication -> "testcase_design".equals(publication.publicationType())).toList();
        if (points.size() != 1 || outcomes.size() != 1 || publications.size() != 1) return false;
        V2TestPointRow point = points.get(0);
        V2GenerationOutcomeRow outcome = outcomes.get(0);
        V2ReplayPublication publication = publications.get(0);
        FunctionalTestcaseDesignV2Input input = plan.input();
        if (!work.functionKey().equals(point.functionKey()) || !work.functionKey().equals(outcome.functionKey())
                || !work.testPointKey().equals(point.testPointKey())
                || !work.testPointKey().equals(outcome.testPointKey())
                || !input.functionName().equals(point.functionName())
                || !input.testPoint().type().wireValue().equals(point.testPointType())
                || !input.testPoint().basis().wireValue().equals(point.basis())
                || !input.testPoint().description().equals(point.description())
                || !input.testPoint().missingInformation().equals(point.pointMissing())
                || !hashJson(input).equals(publication.inputSha256())
                || !work.acceptedResultSha256().equals(publication.resultSha256())) return false;
        try {
            List<FunctionalTestcaseDesignV2Result.Testcase> testcases = persistedV2Testcases(work.id(), data);
            FunctionalTestcaseDesignV2Result result = new FunctionalTestcaseDesignV2Result(
                    work.functionKey(), work.testPointKey(),
                    FunctionalTestcaseDesignV2Result.GenerationOutcome.fromWire(outcome.generationOutcome()),
                    outcome.outcomeMissing(), testcases);
            FunctionalTestcaseV2Validator validator = new FunctionalTestcaseV2Validator();
            FunctionalTestcaseV2Validator.AcceptedDesign accepted = validator.validate(input, result);
            if (!result.testcases().equals(accepted.testcases().stream()
                    .map(FunctionalTestcaseV2Validator.AcceptedTestcase::testcase).toList())) return false;
            if (nonBlank(publication.validatedResultReplayJson())) {
                FunctionalTestcaseDesignV2Result original = strictRecoveryValue(
                        publication.validatedResultReplayJson(), FunctionalTestcaseDesignV2Result.class);
                if (original == null || !publication.resultSha256().equals(hashJson(original))
                        || !canonicalAcceptedDesign(validator.validate(input, original))
                                .equals(canonicalAcceptedDesign(accepted))) return false;
            } else {
                if (!historicalMissingFactFallback || !publication.resultSha256().equals(hashJson(result))) return false;
            }
            if (accepted.formalCoverageSatisfied() != outcome.outcomeFormal()
                    || point.pointFormal() != outcome.outcomeFormal()) return false;
            List<String> acceptedKeys = accepted.testcases().stream()
                    .map(FunctionalTestcaseV2Validator.AcceptedTestcase::caseKey).sorted().toList();
            List<String> persistedKeys = data.testcasesByWork().getOrDefault(work.id(), List.of()).stream()
                    .map(V2PersistedTestcaseRow::caseKey).sorted().toList();
            return acceptedKeys.equals(persistedKeys)
                    && data.publicationsByWork().getOrDefault(work.id(), List.of()).size() == 1
                    && !data.unexpectedTestcaseWorkIds().contains(work.id())
                    && hasExactV2TestcaseBindings(work.id(), input, accepted, data);
        } catch (IllegalArgumentException | JsonProcessingException invalidProjection) {
            return false;
        }
    }

    /** Rebuilds the Java-owned input from committed facts; no raw KEE response is trusted during recovery. */
    private V2GenerationPlanner.TestPointPlan expectedV2TestPointPlan(
            V2FactWorkRow work, V2TestcaseRecoveryData data) {
        return data.testPointPlans().get(new FunctionPointKey(work.functionKey(), work.testPointKey()));
    }

    private List<FunctionalTestcaseDesignV2Result.Testcase> persistedV2Testcases(
            String workItemId, V2TestcaseRecoveryData data) {
        List<V2PersistedTestcaseRow> rows = data.testcasesByWork().getOrDefault(workItemId, List.of());
        List<FunctionalTestcaseDesignV2Result.Testcase> result = new ArrayList<>();
        for (V2PersistedTestcaseRow row : rows) {
            List<FunctionalTestcaseDesignV2Result.Step> steps = data.stepsByWorkCase()
                    .getOrDefault(new WorkCaseKey(workItemId, row.caseKey()), List.of());
            List<String> factKeys = bindingReferences(data, workItemId, row.caseKey(),
                    "TEST_CASE", "REQUIREMENT_FACT");
            List<String> evidenceKeys = bindingReferences(data, workItemId, row.caseKey(),
                    "TEST_CASE", "EVIDENCE");
            result.add(new FunctionalTestcaseDesignV2Result.Testcase(row.name(), row.title(),
                    FunctionalTestcaseDesignV2Result.Priority.valueOf(row.priority()), recoveryStringList(row.preconditions()),
                    new FunctionalTestcaseDesignV2Result.Initialization(recoveryStringList(row.hardware()),
                            recoveryStringList(row.software()), recoveryStringList(row.testConfiguration()),
                            recoveryStringList(row.parameters())),
                    recoveryInputs(row.inputs()), steps, recoveryStringList(row.expectedResults()), row.evaluationCriteria(),
                    row.resultEvaluationCriteria(), recoveryStringList(row.terminationConditions()), row.resultCollection(),
                    factKeys, evidenceKeys, FunctionalTestcaseDesignV2Result.CaseStatus.valueOf(row.caseStatus()),
                    recoveryStringList(row.missingInformation())));
        }
        return List.copyOf(result);
    }

    /** Converts malformed retained testcase input JSON into a fail-closed recovery decision. */
    private List<FunctionalTestcaseDesignV2Result.Input> recoveryInputs(String json) {
        try {
            List<FunctionalTestcaseDesignV2Result.Input> parsed = strictRecoveryValue(json,
                    new TypeReference<List<FunctionalTestcaseDesignV2Result.Input>>() { });
            if (parsed == null || parsed.stream().anyMatch(Objects::isNull)) {
                throw new InvalidV2RecoverySnapshotException();
            }
            return List.copyOf(parsed);
        } catch (JsonProcessingException | NullPointerException invalidJson) {
            throw new InvalidV2RecoverySnapshotException();
        }
    }

    private static List<String> bindingReferences(V2TestcaseRecoveryData data, String workItemId, String subjectKey,
            String subjectType, String referenceType) {
        return data.bindingReferencesByKey()
                .getOrDefault(new BindingLookupKey(workItemId, subjectKey, subjectType, referenceType), List.of())
                .stream().sorted().toList();
    }

    private boolean hasExactV2TestcaseBindings(String workItemId, FunctionalTestcaseDesignV2Input input,
            FunctionalTestcaseV2Validator.AcceptedDesign accepted, V2TestcaseRecoveryData data) {
        List<String> pointFacts = input.requirementFacts().stream()
                .map(FunctionalTestcaseDesignV2Input.RequirementFact::factKey).sorted().toList();
        List<String> pointEvidence = input.requirementFacts().stream()
                .flatMap(fact -> fact.sourceQuotes().stream()).map(StructuredSourceQuoteV2::evidenceKey)
                .distinct().sorted().toList();
        if (!pointFacts.equals(bindingReferences(data, workItemId, input.testPoint().testPointKey(),
                "TEST_POINT", "REQUIREMENT_FACT"))
                || !pointEvidence.equals(bindingReferences(data, workItemId, input.testPoint().testPointKey(),
                        "TEST_POINT", "EVIDENCE"))) return false;
        int expected = pointFacts.size() + pointEvidence.size();
        for (FunctionalTestcaseV2Validator.AcceptedTestcase testcase : accepted.testcases()) {
            expected += testcase.testcase().requirementFactKeys().size() + testcase.testcase().evidenceKeys().size();
        }
        return data.bindingCountsByWork().getOrDefault(workItemId, 0) == expected;
    }

    private int workOwnedBusinessRowCount(String workItemId, boolean lockRows) {
        for (String table : UNFINISHED_WORK_BUSINESS_TABLES) {
            if (!jdbcTemplate.queryForList("SELECT work_item_id FROM " + table
                    + " WHERE work_item_id=? LIMIT 1" + (lockRows ? " FOR UPDATE" : ""),
                    String.class, workItemId).isEmpty()) return 1;
        }
        if (!jdbcTemplate.queryForList("SELECT first_work_item_id FROM v2_requirement_fact "
                        + "WHERE first_work_item_id=? LIMIT 1" + (lockRows ? " FOR UPDATE" : ""),
                String.class, workItemId).isEmpty()
                || !jdbcTemplate.queryForList("SELECT work_item_id FROM v2_testability_feedback "
                        + "WHERE work_item_id=? LIMIT 1" + (lockRows ? " FOR UPDATE" : ""),
                String.class, workItemId).isEmpty()) return 1;
        return 0;
    }

    /** A retained ordinary testcase work may own only its validated point, outcome, cases, steps and bindings. */
    private boolean hasUnexpectedCompletedTestcaseBusinessRows(String workItemId, boolean lockRows) {
        Set<String> allowed = Set.of("structured_test_point", "structured_test_case",
                "structured_test_case_step", "structured_reference_binding");
        for (String table : UNFINISHED_WORK_BUSINESS_TABLES) {
            if (allowed.contains(table)) continue;
            if (!jdbcTemplate.queryForList("SELECT work_item_id FROM " + table
                    + " WHERE work_item_id=? LIMIT 1" + (lockRows ? " FOR UPDATE" : ""),
                    String.class, workItemId).isEmpty()) return true;
        }
        for (String table : RECONCILIATION_WORK_OWNED_TABLES) {
            if (!jdbcTemplate.queryForList("SELECT work_item_id FROM " + table
                    + " WHERE work_item_id=? LIMIT 1" + (lockRows ? " FOR UPDATE" : ""),
                    String.class, workItemId).isEmpty()) return true;
        }
        return !jdbcTemplate.queryForList("SELECT first_work_item_id FROM v2_requirement_fact "
                        + "WHERE first_work_item_id=? LIMIT 1" + (lockRows ? " FOR UPDATE" : ""),
                String.class, workItemId).isEmpty()
                || !jdbcTemplate.queryForList("SELECT work_item_id FROM v2_testability_feedback "
                        + "WHERE work_item_id=? LIMIT 1" + (lockRows ? " FOR UPDATE" : ""),
                String.class, workItemId).isEmpty();
    }

    private boolean isExactMissingFactFallbackSet(
            String taskId, List<V2FallbackWorkRow> fallbacks, boolean lockRows) {
        List<String> functions = jdbcTemplate.queryForList("""
                SELECT function_key FROM v2_approved_function
                WHERE task_id = ? ORDER BY stable_sequence%s
                """.formatted(lockRows ? " FOR UPDATE" : ""), String.class, taskId);
        if (functions.isEmpty() || fallbacks.size() != functions.size()) return false;
        if (count("SELECT COUNT(*) FROM structured_test_point WHERE task_id = ?", taskId) != fallbacks.size()
                || count("SELECT COUNT(*) FROM v2_generation_outcome WHERE task_id = ?", taskId) != fallbacks.size()
                || count("""
                        SELECT COUNT(*) FROM v2_work_publication
                        WHERE task_id = ? AND publication_type = 'testcase_design'
                        """, taskId) != fallbacks.size()) {
            return false;
        }
        Map<String, V2FallbackWorkRow> byFunction = new LinkedHashMap<>();
        for (V2FallbackWorkRow fallback : fallbacks) {
            if (byFunction.putIfAbsent(fallback.functionKey(), fallback) != null
                    || !"COMPLETED".equals(fallback.status())
                    || !nonBlank(fallback.acceptedResultSha256())
                    || fallback.hasLease() || fallback.hasRunningAttempt()
                    || !V2GenerationPlanner.missingFormalFactPointKey(taskId, fallback.functionKey())
                            .equals(fallback.testPointKey())) {
                return false;
            }
            List<RetryAttemptRow> attempts = latestRetryAttempts(fallback.id(), lockRows);
            if (attempts.size() != 1 || !"COMPLETED".equals(attempts.get(0).status())) return false;
            if (!hasExactMissingFactFallbackProjection(taskId, fallback, lockRows)) return false;
        }
        return functions.stream().allMatch(byFunction::containsKey);
    }

    private boolean isExactMissingFactFallbackSubset(String taskId, Set<String> affectedFunctions,
            List<V2FallbackWorkRow> fallbacks, boolean lockRows, V2RecoverySnapshot snapshot) {
        Map<String, V2FallbackWorkRow> byFunction = new LinkedHashMap<>();
        for (V2FallbackWorkRow fallback : fallbacks) {
            if (!affectedFunctions.contains(fallback.functionKey())
                    || byFunction.putIfAbsent(fallback.functionKey(), fallback) != null
                    || !"COMPLETED".equals(fallback.status())
                    || !nonBlank(fallback.acceptedResultSha256())
                    || fallback.hasLease() || fallback.hasRunningAttempt()
                    || !V2GenerationPlanner.missingFormalFactPointKey(taskId, fallback.functionKey())
                            .equals(fallback.testPointKey())
                    || !snapshot.completeTestcaseProjections().getOrDefault(fallback.id(), false)) return false;
        }
        return affectedFunctions.size() == byFunction.size() && affectedFunctions.stream().allMatch(byFunction::containsKey);
    }

    private static V2FactWorkRow fallbackWork(V2FallbackWorkRow fallback) {
        return new V2FactWorkRow(fallback.id(), null, fallback.status(), "functional-testcase-design",
                "FUNCTIONAL_TESTCASE_DESIGN_V2", null, null, null, null, null, List.of(), List.of(), null, 0,
                fallback.functionKey(), fallback.testPointKey(), fallback.acceptedResultSha256(),
                fallback.hasLease(), fallback.hasRunningAttempt());
    }

    private boolean hasExactMissingFactFallbackProjection(
            String taskId, V2FallbackWorkRow fallback, boolean lockRows) {
        String suffix = lockRows ? " FOR UPDATE" : "";
        List<V2ApprovedFunctionRow> functions = jdbcTemplate.query("""
                SELECT function_key, name_text, path_text, description_text
                FROM v2_approved_function WHERE task_id=? AND function_key=?
                """ + suffix, (row, ignored) -> new V2ApprovedFunctionRow(row.getString("function_key"),
                row.getString("name_text"), row.getString("path_text"), row.getString("description_text")),
                taskId, fallback.functionKey());
        if (functions.size() != 1) return false;
        ApprovedFunctionScope.ApprovedFunction function = new ApprovedFunctionScope.ApprovedFunction(
                functions.get(0).functionKey(), functions.get(0).name(), functions.get(0).path(), functions.get(0).description());
        V2GenerationPlanner.TestPointPlan plan = new V2GenerationPlanner().missingFormalFactTestPoint(taskId, function);
        List<V2FallbackProjectionRow> projections = jdbcTemplate.query("""
                SELECT point.function_name, point.test_point_type, point.basis, point.description,
                       point.missing_information_json AS point_missing, point.formal_coverage_satisfied AS point_formal,
                       outcome.generation_outcome, outcome.missing_information_json AS outcome_missing,
                       outcome.formal_coverage_satisfied AS outcome_formal,
                       publication.input_sha256, publication.result_sha256,
                       publication.validated_result_replay_json
                FROM structured_test_point point
                JOIN v2_generation_outcome outcome
                  ON outcome.work_item_id=point.work_item_id AND outcome.task_id=point.task_id
                 AND outcome.test_point_key=point.test_point_key AND outcome.function_key=point.function_key
                JOIN v2_work_publication publication
                  ON publication.work_item_id=point.work_item_id AND publication.task_id=point.task_id
                 AND publication.publication_type='testcase_design'
                WHERE point.task_id=? AND point.work_item_id=? AND point.test_point_key=? AND point.function_key=?
                """ + suffix, (row, ignored) -> new V2FallbackProjectionRow(row.getString("function_name"),
                row.getString("test_point_type"), row.getString("basis"), row.getString("description"),
                recoveryStringList(row.getString("point_missing")), row.getBoolean("point_formal"),
                row.getString("generation_outcome"), recoveryStringList(row.getString("outcome_missing")),
                row.getBoolean("outcome_formal"), row.getString("input_sha256"), row.getString("result_sha256"),
                row.getString("validated_result_replay_json")),
                taskId, fallback.id(), fallback.testPointKey(), fallback.functionKey());
        if (projections.size() != 1) return false;
        V2FallbackProjectionRow projection = projections.get(0);
        // The persisted public V2 result may explain the missing information more specifically than the planner hint.
        // Rebuild and revalidate that exact result before trusting its hash or allowing coordinated row changes.
        com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignV2Result replay;
        try {
            replay = new com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignV2Result(
                    fallback.functionKey(), fallback.testPointKey(),
                    com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignV2Result.GenerationOutcome.UNABLE_TO_GENERATE,
                    projection.outcomeMissing(), List.of());
            // The accepted result contract requires a non-empty explanation, but does not require the model's
            // explanation to repeat the planner hint. Revalidate that public contract before trusting its hash.
            FunctionalTestcaseV2Validator validator = new FunctionalTestcaseV2Validator();
            FunctionalTestcaseV2Validator.AcceptedDesign rebuilt = validator.validate(plan.input(), replay);
            // V23 stores the validated public result so a recovery cannot trust only coherently edited projection rows.
            // Historical rows may have no replay; once present, it is part of the closed-world proof and must match.
            if (nonBlank(projection.validatedResultReplayJson())) {
                com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignV2Result original = strictRecoveryValue(
                        projection.validatedResultReplayJson(),
                        com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignV2Result.class);
                if (original == null || !projection.resultSha256().equals(hashJson(original))
                        || !canonicalAcceptedDesign(validator.validate(plan.input(), original))
                                .equals(canonicalAcceptedDesign(rebuilt))) return false;
            }
        } catch (IllegalArgumentException | JsonProcessingException invalidPersistedResult) {
            return false;
        }
        return plan.input().functionName().equals(projection.functionName())
                && plan.input().testPoint().type().wireValue().equals(projection.testPointType())
                && plan.input().testPoint().basis().wireValue().equals(projection.basis())
                && plan.input().testPoint().description().equals(projection.description())
                && plan.input().testPoint().missingInformation().equals(projection.pointMissing())
                && !projection.pointFormal() && "unable_to_generate".equals(projection.generationOutcome())
                && !projection.outcomeMissing().isEmpty() && !projection.outcomeFormal()
                && hashJson(plan.input()).equals(projection.inputSha256())
                && hashJson(replay).equals(projection.resultSha256())
                && fallback.acceptedResultSha256().equals(projection.resultSha256())
                && !hasUnexpectedFallbackBusinessRows(fallback.id(), lockRows)
                && count("SELECT COUNT(*) FROM structured_test_point WHERE task_id = ? AND work_item_id = ?",
                        taskId, fallback.id()) == 1
                && count("SELECT COUNT(*) FROM v2_generation_outcome WHERE task_id = ? AND work_item_id = ?",
                        taskId, fallback.id()) == 1
                && count("SELECT COUNT(*) FROM v2_work_publication WHERE task_id = ? AND work_item_id = ?",
                        taskId, fallback.id()) == 1
                && count("SELECT COUNT(*) FROM structured_test_case WHERE task_id = ? AND work_item_id = ?",
                        taskId, fallback.id()) == 0
                && count("SELECT COUNT(*) FROM structured_reference_binding WHERE work_item_id = ?",
                        fallback.id()) == 0;
    }

    private boolean hasUnexpectedFallbackBusinessRows(String workItemId, boolean lockRows) {
        for (String table : UNFINISHED_WORK_BUSINESS_TABLES) {
            // The exact fallback owns one validated test-point row. Its outcome and publication live in dedicated V2
            // tables and are checked separately; every other work-owned business row is outside the frozen projection.
            if ("structured_test_point".equals(table)) continue;
            if (!jdbcTemplate.queryForList("SELECT work_item_id FROM " + table
                    + " WHERE work_item_id=? LIMIT 1" + (lockRows ? " FOR UPDATE" : ""),
                    String.class, workItemId).isEmpty()) return true;
        }
        // V15 reconciliation state also belongs to one work item, but is intentionally not part of the generic
        // business-table directory. A no-fact fallback must own none of it: recovery is a closed-world proof.
        for (String table : RECONCILIATION_WORK_OWNED_TABLES) {
            if (!jdbcTemplate.queryForList("SELECT work_item_id FROM " + table
                    + " WHERE work_item_id=? LIMIT 1" + (lockRows ? " FOR UPDATE" : ""),
                    String.class, workItemId).isEmpty()) return true;
        }
        // V20 uses first_work_item_id for facts and a dedicated work_item_id for feedback, so neither table is in
        // the legacy work-owned directory above. A no-fact fallback must not be the provenance owner of either row.
        if (!jdbcTemplate.queryForList("""
                SELECT first_work_item_id FROM v2_requirement_fact
                WHERE first_work_item_id=? LIMIT 1%s
                """.formatted(lockRows ? " FOR UPDATE" : ""), String.class, workItemId).isEmpty()
                || !jdbcTemplate.queryForList("""
                SELECT work_item_id FROM v2_testability_feedback
                WHERE work_item_id=? LIMIT 1%s
                """.formatted(lockRows ? " FOR UPDATE" : ""), String.class, workItemId).isEmpty()) return true;
        return false;
    }

    private String hashJson(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(value)));
        } catch (NoSuchAlgorithmException | JsonProcessingException exception) {
            throw new IllegalStateException("Validated V2 projection cannot be hashed", exception);
        }
    }

    private static String sha256Text(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static FunctionalTestcaseV2Validator.AcceptedDesign canonicalAcceptedDesign(
            FunctionalTestcaseV2Validator.AcceptedDesign value) {
        List<FunctionalTestcaseV2Validator.AcceptedTestcase> ordered = value.testcases().stream()
                .sorted(java.util.Comparator.comparing(FunctionalTestcaseV2Validator.AcceptedTestcase::caseKey))
                .toList();
        return new FunctionalTestcaseV2Validator.AcceptedDesign(value.generationOutcome(),
                value.missingInformation(), ordered, value.formalCoverageSatisfied());
    }

    /**
     * Recovery never inherits a caller-supplied lenient mapper. Retained public results are an authorization input for
     * coordinated mutation, so unknown fields and trailing JSON must fail closed even when normal HTTP DTO handling is
     * configured more permissively for compatibility. [Req-ID]: REQ-TGV2-012
     */
    private <T> T strictRecoveryValue(String json, Class<T> type) throws JsonProcessingException {
        return objectMapper.readerFor(type)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readValue(json);
    }

    private <T> T strictRecoveryValue(String json, TypeReference<T> type) throws JsonProcessingException {
        return objectMapper.readerFor(type)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readValue(json);
    }

    /** Converts malformed retained JSON into a fail-closed eligibility result without hiding database failures. */
    private List<String> recoveryStringList(String value) {
        try {
            JsonNode parsed = objectMapper.readerFor(JsonNode.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(value);
            if (parsed == null || !parsed.isArray()) {
                throw new InvalidV2RecoverySnapshotException();
            }
            List<String> values = new ArrayList<>(parsed.size());
            for (com.fasterxml.jackson.databind.JsonNode item : parsed) {
                if (!item.isTextual()) throw new InvalidV2RecoverySnapshotException();
                values.add(item.textValue());
            }
            return List.copyOf(values);
        } catch (JsonProcessingException | NullPointerException invalidJson) {
            throw new InvalidV2RecoverySnapshotException();
        }
    }

    private List<String> recoveryOptionalStringList(String value) {
        return value == null ? List.of() : recoveryStringList(value);
    }

    /**
     * Requeues all proven zero-write fact-validation windows and retires only the matching no-fact fallback works.
     * Historical attempts, accepted hashes, outcomes, and publication ledgers remain unchanged for audit.
     * [Req-ID]: REQ-TGV2-011, REQ-TGV2-012
     */
    private boolean recoverV2AtomicityRejectedTask(String taskId) {
        List<String> factWorkIds = jdbcTemplate.queryForList("""
                SELECT id FROM structured_generation_work_item
                WHERE task_id = ? AND status = 'FAILED'
                  AND skill_name = 'requirement-fact-extraction'
                  AND operation_name = 'REQUIREMENT_FACT_EXTRACTION_V2'
                  AND validation_error_code = 'FACT_ATOMICITY_INVALID'
                  AND accepted_result_sha256 IS NULL
                ORDER BY created_at, id FOR UPDATE
                """, String.class, taskId);
        List<V2FallbackWorkRow> fallbackRows = currentV2TestcaseWorks(taskId, true);
        if (factWorkIds.isEmpty() || fallbackRows.isEmpty()) {
            throw new IllegalStateException("V2 fact recovery targets changed during explicit retry");
        }
        int requeued = 0;
        for (String workItemId : factWorkIds) {
            requeued += jdbcTemplate.update("""
                    UPDATE structured_generation_work_item
                    SET status='QUEUED', lease_owner=NULL, lease_expires_at=NULL,
                        validation_error_code=NULL, validation_error_path=NULL, validation_error_message=NULL
                    WHERE id=? AND task_id=? AND status='FAILED' AND accepted_result_sha256 IS NULL
                    """, workItemId, taskId);
        }
        int superseded = 0;
        for (V2FallbackWorkRow fallback : fallbackRows) {
            superseded += jdbcTemplate.update("""
                    UPDATE structured_generation_work_item SET status='SUPERSEDED'
                    WHERE id=? AND task_id=? AND status='COMPLETED' AND accepted_result_sha256=?
                    """, fallback.id(), taskId, fallback.acceptedResultSha256());
        }
        int taskChanged = jdbcTemplate.update("""
                UPDATE generation_task
                SET status='QUEUED', structured_processing_status='PENDING', structured_coverage_status='PENDING',
                    result_snapshot=NULL, artifact_id=NULL, artifact_sha256=NULL, artifact_path=NULL,
                    validation_error_code=NULL, validation_error_path=NULL, validation_error_message=NULL
                WHERE id=? AND status IN ('PARTIAL','FAILED')
                  AND workflow_version='2.0' AND input_version='2.0' AND artifact_version='2.0'
                  AND ((status='PARTIAL' AND artifact_id IS NOT NULL
                        AND artifact_sha256 IS NOT NULL AND artifact_path IS NOT NULL)
                    OR (status='FAILED' AND artifact_id IS NULL
                        AND artifact_sha256 IS NULL AND artifact_path IS NULL))
                """, taskId);
        if (requeued != factWorkIds.size() || superseded != fallbackRows.size() || taskChanged != 1) {
            throw new IllegalStateException("V2 fact recovery did not mutate its complete frozen set");
        }
        return true;
    }

    /**
     * Requeues only direct-evidence failures and retires only their affected no-fact fallbacks. Completed functions
     * remain byte-for-byte audit history and current business output. [Req-ID]: REQ-TGV2-012
     */
    private boolean recoverV2DirectEvidenceRejectedTask(String taskId) {
        List<DirectEvidenceRecoveryWork> failedWorks = jdbcTemplate.query("""
                        SELECT id, function_key, validation_error_path
                        FROM structured_generation_work_item
                        WHERE task_id=? AND status='FAILED'
                          AND skill_name='requirement-fact-extraction'
                          AND operation_name='REQUIREMENT_FACT_EXTRACTION_V2'
                          AND validation_error_code='FACT_DIRECT_EVIDENCE_UNSUPPORTED'
                          AND accepted_result_sha256 IS NULL
                        ORDER BY created_at, id FOR UPDATE
                        """, (row, ignored) -> new DirectEvidenceRecoveryWork(
                        row.getString("id"), row.getString("function_key"), row.getString("validation_error_path")),
                taskId);
        Set<String> affectedFunctions = failedWorks.stream().map(DirectEvidenceRecoveryWork::functionKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (failedWorks.isEmpty() || affectedFunctions.isEmpty()
                || failedWorks.stream().anyMatch(work ->
                        !nonBlank(work.functionKey())
                                ||
                        !V2_FACT_STATEMENT_PATH.matcher(orDefault(work.validationErrorPath(), "")).matches())) {
            throw new IllegalStateException("V2 direct-evidence recovery targets changed during explicit retry");
        }
        List<V2FallbackWorkRow> fallbackRows = currentV2TestcaseWorks(taskId, true).stream()
                .filter(row -> affectedFunctions.contains(row.functionKey())).toList();
        if (fallbackRows.size() != affectedFunctions.size()) {
            throw new IllegalStateException("V2 direct-evidence fallback targets changed during explicit retry");
        }
        int requeued = 0;
        for (DirectEvidenceRecoveryWork work : failedWorks) {
            requeued += jdbcTemplate.update("""
                    UPDATE structured_generation_work_item
                    SET status='QUEUED', lease_owner=NULL, lease_expires_at=NULL,
                        validation_error_code=NULL, validation_error_path=NULL, validation_error_message=NULL
                    WHERE id=? AND task_id=? AND status='FAILED'
                      AND validation_error_code='FACT_DIRECT_EVIDENCE_UNSUPPORTED'
                      AND accepted_result_sha256 IS NULL
                    """, work.id(), taskId);
        }
        int superseded = 0;
        for (V2FallbackWorkRow fallback : fallbackRows) {
            superseded += jdbcTemplate.update("""
                    UPDATE structured_generation_work_item SET status='SUPERSEDED'
                    WHERE id=? AND task_id=? AND status='COMPLETED' AND accepted_result_sha256=?
                    """, fallback.id(), taskId, fallback.acceptedResultSha256());
        }
        int taskChanged = jdbcTemplate.update("""
                UPDATE generation_task
                SET status='QUEUED', structured_processing_status='PENDING', structured_coverage_status='PENDING',
                    result_snapshot=NULL, artifact_id=NULL, artifact_sha256=NULL, artifact_path=NULL,
                    validation_error_code=NULL, validation_error_path=NULL, validation_error_message=NULL
                WHERE id=? AND status IN ('PARTIAL','FAILED')
                  AND workflow_version='2.0' AND input_version='2.0' AND artifact_version='2.0'
                  AND validation_error_code='FACT_DIRECT_EVIDENCE_UNSUPPORTED'
                  AND ((status='PARTIAL' AND artifact_id IS NOT NULL
                        AND artifact_sha256 IS NOT NULL AND artifact_path IS NOT NULL)
                    OR (status='FAILED' AND artifact_id IS NULL
                        AND artifact_sha256 IS NULL AND artifact_path IS NULL))
                """, taskId);
        if (requeued != failedWorks.size() || superseded != fallbackRows.size() || taskChanged != 1) {
            throw new IllegalStateException("V2 direct-evidence recovery did not mutate its complete frozen set");
        }
        return true;
    }

    /**
     * Requeues the complete locked set of zero-write V2 technical failures and detaches only its failure artifact.
     * Historical attempts and immutable input rows remain untouched; each next claim creates the new attempt.
     * [Req-ID]: REQ-TGV2-013
     */
    private boolean recoverV2ZeroWriteTechnicalFailureTask(String taskId) {
        int expectedWorks = count("""
                SELECT COUNT(*) FROM structured_generation_work_item
                WHERE task_id = ? AND status = 'FAILED' AND accepted_result_sha256 IS NULL
                  AND ((skill_name='requirement-fact-extraction' AND operation_name='REQUIREMENT_FACT_EXTRACTION_V2')
                    OR (skill_name='functional-testcase-design' AND operation_name='FUNCTIONAL_TESTCASE_DESIGN_V2'))
                """, taskId);
        if (expectedWorks <= 0) {
            throw new IllegalStateException("V2 technical recovery lost its locked work set");
        }
        int requeued = jdbcTemplate.update("""
                UPDATE structured_generation_work_item
                SET status='QUEUED', lease_owner=NULL, lease_expires_at=NULL,
                    validation_error_code=NULL, validation_error_path=NULL, validation_error_message=NULL
                WHERE task_id=? AND status='FAILED' AND accepted_result_sha256 IS NULL
                  AND ((skill_name='requirement-fact-extraction' AND operation_name='REQUIREMENT_FACT_EXTRACTION_V2')
                    OR (skill_name='functional-testcase-design' AND operation_name='FUNCTIONAL_TESTCASE_DESIGN_V2'))
                """, taskId);
        int taskChanged = jdbcTemplate.update("""
                UPDATE generation_task
                SET status='QUEUED', structured_processing_status='PENDING', structured_coverage_status='PENDING',
                    result_snapshot=NULL, artifact_id=NULL, artifact_sha256=NULL, artifact_path=NULL,
                    validation_error_code=NULL, validation_error_path=NULL, validation_error_message=NULL
                WHERE id=? AND status='PARTIAL' AND structured_processing_status='FAILED'
                  AND structured_coverage_status='UNABLE_TO_GENERATE'
                  AND workflow_version='2.0' AND input_version='2.0' AND artifact_version='2.0'
                  AND artifact_id IS NOT NULL AND artifact_sha256 IS NOT NULL AND artifact_path IS NOT NULL
                """, taskId);
        if (requeued != expectedWorks || taskChanged != 1) {
            throw new IllegalStateException("V2 technical recovery did not mutate its complete locked set");
        }
        return true;
    }

    /**
     * Requeues only the design works rejected by the retired overall/step equality rule and retires their stale
     * no-fact placeholders. Completed fact windows and their publication rows are never updated. [Req-ID]: REQ-TGV2-014
     */
    private boolean recoverV2ExpectedResultsRejectedTask(String taskId) {
        List<ExpectedResultsRecoveryWork> failedWorks = jdbcTemplate.query("""
                        SELECT id, function_key, validation_error_path
                        FROM structured_generation_work_item
                        WHERE task_id=? AND status='FAILED'
                          AND skill_name='functional-testcase-design'
                          AND operation_name='FUNCTIONAL_TESTCASE_DESIGN_V2'
                          AND validation_error_code='TESTCASE_EXPECTED_ORDER_INVALID'
                          AND accepted_result_sha256 IS NULL
                        ORDER BY created_at, id FOR UPDATE
                        """, (row, ignored) -> new ExpectedResultsRecoveryWork(
                        row.getString("id"), row.getString("function_key"), row.getString("validation_error_path")),
                taskId);
        if (failedWorks.isEmpty() || failedWorks.stream().anyMatch(work ->
                !nonBlank(work.functionKey())
                        || !V2_EXPECTED_RESULTS_PATH.matcher(orDefault(work.validationErrorPath(), "")).matches())) {
            throw new IllegalStateException("V2 expected-results recovery targets changed during explicit retry");
        }
        Set<String> affectedFunctions = failedWorks.stream().map(ExpectedResultsRecoveryWork::functionKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<V2FallbackWorkRow> fallbacks = currentV2TestcaseWorks(taskId, true).stream()
                .filter(row -> "QUEUED".equals(row.status())
                        && V2GenerationPlanner.missingFormalFactPointKey(taskId, row.functionKey())
                                .equals(row.testPointKey()))
                .toList();
        Set<String> fallbackFunctions = fallbacks.stream().map(V2FallbackWorkRow::functionKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (fallbacks.isEmpty() || fallbackFunctions.size() != fallbacks.size()
                || !fallbackFunctions.equals(affectedFunctions)) {
            throw new IllegalStateException("V2 expected-results fallback targets changed during explicit retry");
        }
        Map<String, V2ApprovedFunctionRow> functions = loadV2ApprovedFunctions(
                taskId, affectedFunctions, " FOR UPDATE");
        Map<String, V2TaskFactProjection> facts = loadV2FactsForFunctions(
                taskId, affectedFunctions, " FOR UPDATE");
        Map<FunctionPointKey, V2GenerationPlanner.TestPointPlan> plans = buildV2TestPointPlans(
                taskId, functions, facts);
        for (V2FallbackWorkRow fallback : fallbacks) {
            V2GenerationPlanner.TestPointPlan plan = plans.get(
                    new FunctionPointKey(fallback.functionKey(), fallback.testPointKey()));
            if (plan == null || !fallback.identityKey().equals(plan.registration().identityKey())) {
                throw new IllegalStateException("V2 expected-results fallback identity changed during explicit retry");
            }
        }
        int requeued = 0;
        for (ExpectedResultsRecoveryWork work : failedWorks) {
            requeued += jdbcTemplate.update("""
                    UPDATE structured_generation_work_item
                    SET status='QUEUED', lease_owner=NULL, lease_expires_at=NULL,
                        validation_error_code=NULL, validation_error_path=NULL, validation_error_message=NULL
                    WHERE id=? AND task_id=? AND status='FAILED'
                      AND validation_error_code='TESTCASE_EXPECTED_ORDER_INVALID'
                      AND accepted_result_sha256 IS NULL
                    """, work.id(), taskId);
        }
        int superseded = 0;
        for (V2FallbackWorkRow fallback : fallbacks) {
            superseded += jdbcTemplate.update("""
                    UPDATE structured_generation_work_item SET status='SUPERSEDED'
                    WHERE id=? AND task_id=? AND status='QUEUED' AND accepted_result_sha256 IS NULL
                      AND lease_owner IS NULL AND lease_expires_at IS NULL
                    """, fallback.id(), taskId);
        }
        int taskChanged = jdbcTemplate.update("""
                UPDATE generation_task
                SET status='QUEUED', structured_processing_status='PENDING', structured_coverage_status='PENDING',
                    result_snapshot=NULL, validation_error_code=NULL,
                    validation_error_path=NULL, validation_error_message=NULL
                WHERE id=? AND status='FAILED' AND structured_processing_status='FAILED'
                  AND structured_coverage_status='PENDING'
                  AND workflow_version='2.0' AND input_version='2.0' AND artifact_version='2.0'
                  AND validation_error_code='STRUCTURED_COORDINATOR_STATE_FAILURE'
                  AND validation_error_path='$.artifact_export'
                  AND result_snapshot IS NULL
                  AND artifact_id IS NULL AND artifact_sha256 IS NULL AND artifact_path IS NULL
                """, taskId);
        if (requeued != failedWorks.size() || superseded != fallbacks.size() || taskChanged != 1) {
            throw new IllegalStateException("V2 expected-results recovery did not mutate its complete locked set");
        }
        return true;
    }

    /**
     * Requeues the complete identity-label rejection set while keeping the old workbook as a non-downloadable
     * replacement baseline. The next successful terminal publish atomically replaces its coordinates; this method
     * never deletes the historical file or creates an attempt. [Req-ID]: REQ-TGV2-015
     */
    private boolean recoverV2IdentityLabelRejectedTask(String taskId) {
        IdentityLabelRecoveryTaskEnvelope taskEnvelope = jdbcTemplate.queryForObject("""
                SELECT result_snapshot, validation_error_code, validation_error_path, validation_error_message
                FROM generation_task WHERE id=? FOR UPDATE
                """, (row, ignored) -> new IdentityLabelRecoveryTaskEnvelope(
                row.getString("result_snapshot"), row.getString("validation_error_code"),
                row.getString("validation_error_path"), row.getString("validation_error_message")), taskId);
        if (taskEnvelope == null || !isV2IdentityLabelRecoveryEnvelope(taskEnvelope.resultSnapshot(),
                taskEnvelope.validationErrorCode(), taskEnvelope.validationErrorPath(),
                taskEnvelope.validationErrorMessage())) {
            throw new IllegalStateException("V2 identity-label recovery lost its locked task envelope");
        }
        List<IdentityLabelRecoveryWork> failedWorks = jdbcTemplate.query("""
                SELECT id, coverage_status, validation_error_code, validation_error_path, validation_error_message
                FROM structured_generation_work_item
                WHERE task_id=? AND status='FAILED'
                  AND skill_name='functional-testcase-design'
                  AND operation_name='FUNCTIONAL_TESTCASE_DESIGN_V2'
                  AND validation_error_code='TESTCASE_UNSUPPORTED_BUSINESS_DETAIL'
                  AND accepted_result_sha256 IS NULL
                  AND lease_owner IS NULL AND lease_expires_at IS NULL
                ORDER BY created_at, id FOR UPDATE
                """, (row, ignored) -> new IdentityLabelRecoveryWork(
                row.getString("id"), row.getString("coverage_status"), row.getString("validation_error_code"),
                row.getString("validation_error_path"), row.getString("validation_error_message")), taskId);
        if (failedWorks.isEmpty() || failedWorks.stream().anyMatch(work -> {
            Optional<StoredValidationDiagnostic> diagnostic = strictStoredValidationDiagnostic(
                    work.validationErrorCode(), work.validationErrorPath(), work.validationErrorMessage());
            return work.coverageStatus() != null || diagnostic.isEmpty()
                    || !StructuredValidationFailure.Code.TESTCASE_UNSUPPORTED_BUSINESS_DETAIL.name()
                            .equals(diagnostic.get().code())
                    || !V2_IDENTITY_LABEL_PATH.matcher(diagnostic.get().path()).matches();
        })) {
            throw new IllegalStateException("V2 identity-label recovery lost its locked work set");
        }
        int requeued = 0;
        for (IdentityLabelRecoveryWork work : failedWorks) {
            requeued += jdbcTemplate.update("""
                    UPDATE structured_generation_work_item
                    SET status='QUEUED', lease_owner=NULL, lease_expires_at=NULL,
                        validation_error_code=NULL, validation_error_path=NULL, validation_error_message=NULL
                    WHERE id=? AND task_id=? AND status='FAILED'
                      AND skill_name='functional-testcase-design'
                      AND operation_name='FUNCTIONAL_TESTCASE_DESIGN_V2'
                      AND validation_error_code=? AND validation_error_path=? AND validation_error_message=?
                      AND coverage_status IS NULL
                      AND accepted_result_sha256 IS NULL
                      AND lease_owner IS NULL AND lease_expires_at IS NULL
                    """, work.id(), taskId, work.validationErrorCode(), work.validationErrorPath(),
                    work.validationErrorMessage());
        }
        String envelopePredicate;
        Object[] envelopeArguments;
        if (taskEnvelope.resultSnapshot() == null) {
            envelopePredicate = """
                    result_snapshot IS NULL
                      AND validation_error_code=? AND validation_error_path=? AND validation_error_message=?
                    """;
            envelopeArguments = new Object[] {taskEnvelope.validationErrorCode(), taskEnvelope.validationErrorPath(),
                    taskEnvelope.validationErrorMessage(), taskId};
        } else {
            envelopePredicate = """
                    result_snapshot=CAST(? AS JSON)
                      AND validation_error_code IS NULL AND validation_error_path IS NULL
                      AND validation_error_message IS NULL
                    """;
            envelopeArguments = new Object[] {taskEnvelope.resultSnapshot(), taskId};
        }
        int taskChanged = jdbcTemplate.update("""
                UPDATE generation_task
                SET status='QUEUED', structured_processing_status='PENDING', structured_coverage_status='PENDING',
                    result_snapshot=NULL,
                    validation_error_code=NULL, validation_error_path=NULL, validation_error_message=NULL
                WHERE %s AND id=? AND status='PARTIAL' AND structured_processing_status='FAILED'
                  AND structured_coverage_status='UNABLE_TO_GENERATE'
                  AND workflow_version='2.0' AND input_version='2.0' AND artifact_version='2.0'
                  AND artifact_id IS NOT NULL AND artifact_sha256 IS NOT NULL AND artifact_path IS NOT NULL
                """.formatted(envelopePredicate), envelopeArguments);
        if (requeued != failedWorks.size() || taskChanged != 1) {
            throw new IllegalStateException("V2 identity-label recovery did not mutate its complete frozen set");
        }
        return true;
    }

    private boolean isExplicitlyRetryableFailure(
            String taskId, RetryWorkRow work, RetryAttemptRow attempt, boolean lockRows) {
        if (!"FAILED".equals(attempt.status())) return false;
        if ("structured_output_invalid".equals(attempt.failureType())) {
            // V2 reconciliation has dedicated run/page recovery contracts. Letting it fall through the historical
            // generic rule would bypass those durable staging checks. A mismatched operation name cannot disguise a
            // work that already owns a V2 run. [Req-ID]: REQ-ESR-012, REQ-ESR-013
            return !isV2ReconciliationWork(work) && !hasReconciliationRun(work.id(), lockRows);
        }
        if ("business_validation_failed".equals(attempt.failureType())) {
            return !isV2ReconciliationWork(work)
                    && StructuredValidationFailure.Code.REVIEW_FACT_DIRECT_EVIDENCE_UNSUPPORTED.name()
                            .equals(attempt.validationErrorCode())
                    && StructuredValidationFailure.isSafePath(attempt.validationErrorPath());
        }
        if (isExhaustedFeatureExtractionModelFailure(work, attempt)) return true;
        return "response_too_large".equals(attempt.failureType())
                && isIrreducibleRequirementReviewLeaf(taskId, work);
    }

    /**
     * Proves the durable zero-write boundary for one explicit V2 reconciliation model-failure retry.
     *
     * <p>KEE's transport category is intentionally log-only, so this predicate does not claim that a persisted
     * {@code model_execution_failed} was a network error. Instead the mutation path locks and validates the stronger
     * database fact: completed pages may be retained, but every unfinished page still owns no candidate result and no
     * formal or downstream result exists. Requeueing then lets the pending-page query skip completed pages.</p>
     *
     * [Req-ID]: REQ-ESR-012
     */
    private boolean isZeroWriteReconciliationModelFailureRetry(
            String taskId, RetryWorkRow work, RetryAttemptRow attempt, boolean lockRows) {
        if (!"FAILED".equals(work.status())
                || !isV2ReconciliationWork(work)
                || !"FAILED".equals(attempt.status())
                || !"model_execution_failed".equals(attempt.failureType())
                || work.acceptedResultSha256() != null
                || work.hasLease()
                || work.hasRunningAttempt()
                || unfinishedWorkOwnedBusinessRowCount(taskId, lockRows) != 0
                || hasPublishedReconciliationOrDownstreamRows(taskId, lockRows)
                || hasArtifactCoordinates(taskId, lockRows)) {
            return false;
        }
        return hasZeroWriteReconciliationPageState(taskId, work, lockRows);
    }

    /**
     * Admits a structural failure only through the audited V2 page-state proof.
     * The generic {@code structured_output_invalid} branch remains closed to every work that owns a reconciliation run.
     *
     * [Req-ID]: REQ-ESR-013
     */
    private boolean isZeroWriteReconciliationStructuralFailureRetry(
            String taskId, RetryWorkRow work, RetryAttemptRow attempt, boolean lockRows) {
        if (!"FAILED".equals(work.status())
                || !isV2ReconciliationWork(work)
                || !"FAILED".equals(attempt.status())
                || !"structured_output_invalid".equals(attempt.failureType())
                || work.acceptedResultSha256() != null
                || work.hasLease()
                || work.hasRunningAttempt()
                || unfinishedWorkOwnedBusinessRowCount(taskId, lockRows) != 0
                || hasPublishedReconciliationOrDownstreamRows(taskId, lockRows)
                || hasArtifactCoordinates(taskId, lockRows)) {
            return false;
        }
        return hasZeroWriteReconciliationPageState(taskId, work, lockRows);
    }

    /**
     * Locks and validates the reusable V2 run graph without interpreting any project, document, page number, or
     * business content. Completed pages may legitimately own zero or partial relations; only unfinished pages must
     * be zero-write. Task-level completeness is still enforced after all pages complete and uses ESR-011 on failure.
     *
     * [Req-ID]: REQ-ESR-012, REQ-ESR-013
     */
    private boolean hasZeroWriteReconciliationPageState(
            String taskId, RetryWorkRow work, boolean lockRows) {
        List<RetryReconciliationRunRow> runs = jdbcTemplate.query("""
                        SELECT work_item_id, run_key, catalog_sha256, status, accepted_result_sha256
                        FROM structured_reconciliation_run
                        WHERE task_id = ? ORDER BY work_item_id%s
                        """.formatted(lockRows ? " FOR UPDATE" : ""),
                (row, ignored) -> new RetryReconciliationRunRow(
                        row.getString("work_item_id"), row.getString("run_key"), row.getString("catalog_sha256"),
                        row.getString("status"), row.getString("accepted_result_sha256")),
                taskId);
        if (runs.size() != 1 || !work.id().equals(runs.get(0).workItemId())
                || !"STAGING".equals(runs.get(0).status()) || runs.get(0).acceptedResultSha256() != null) {
            return false;
        }
        RetryReconciliationRunRow run = runs.get(0);
        List<RetryReconciliationPageRow> pages = jdbcTemplate.query("""
                        SELECT page_key, run_key, catalog_sha256, parent_page_key, status,
                               completed_owner_source_refs_json, result_sha256,
                               (completed_at IS NOT NULL) AS has_completed_at
                        FROM structured_reconciliation_page_stage
                        WHERE work_item_id = ? ORDER BY page_key%s
                        """.formatted(lockRows ? " FOR UPDATE" : ""),
                (row, ignored) -> new RetryReconciliationPageRow(
                        row.getString("page_key"), row.getString("run_key"), row.getString("catalog_sha256"),
                        row.getString("parent_page_key"), row.getString("status"),
                        row.getString("completed_owner_source_refs_json"), row.getString("result_sha256"),
                        row.getBoolean("has_completed_at")),
                work.id());
        Set<String> parentPageKeys = pages.stream()
                .map(RetryReconciliationPageRow::parentPageKey)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (pages.isEmpty()
                || pages.stream().noneMatch(page -> "PLANNED".equals(page.status()))
                // A planned page that already owns children is a stale split parent, not an executable leaf.
                || pages.stream().anyMatch(page -> "PLANNED".equals(page.status())
                        && parentPageKeys.contains(page.pageKey()))
                || pages.stream().anyMatch(page -> "SPLIT".equals(page.status())
                        && !parentPageKeys.contains(page.pageKey()))
                || pages.stream().anyMatch(page -> !("PLANNED".equals(page.status())
                        || "COMPLETED".equals(page.status()) || "SPLIT".equals(page.status())))
                || pages.stream().anyMatch(page -> !run.runKey().equals(page.runKey())
                        || !run.catalogSha256().equals(page.catalogSha256()))
                || pages.stream().filter(page -> "COMPLETED".equals(page.status())).anyMatch(page ->
                        page.completedOwnerSourceRefsJson() == null
                                || page.resultSha256() == null || page.resultSha256().isBlank()
                                || !page.hasCompletedAt())
                || pages.stream().filter(page -> !"COMPLETED".equals(page.status())).anyMatch(page ->
                        page.completedOwnerSourceRefsJson() != null
                                || page.resultSha256() != null || page.hasCompletedAt())) {
            return false;
        }
        List<String> unfinishedStageRows = jdbcTemplate.queryForList("""
                SELECT relation.reconciliation_key
                FROM structured_reconciliation_relation_stage relation
                JOIN structured_reconciliation_page_stage page
                  ON page.work_item_id = relation.work_item_id AND page.page_key = relation.page_key
                WHERE relation.work_item_id = ? AND page.status <> 'COMPLETED'
                ORDER BY relation.reconciliation_key%s
                """.formatted(lockRows ? " FOR UPDATE" : ""), String.class, work.id());
        if (!unfinishedStageRows.isEmpty()) return false;
        List<String> unfinishedStageBindings = jdbcTemplate.queryForList("""
                SELECT binding.reconciliation_key
                FROM structured_reconciliation_relation_stage_binding binding
                JOIN structured_reconciliation_page_stage page
                  ON page.work_item_id = binding.work_item_id
                JOIN structured_reconciliation_relation_stage relation
                  ON relation.work_item_id = binding.work_item_id
                 AND relation.reconciliation_key = binding.reconciliation_key
                 AND relation.page_key = page.page_key
                WHERE binding.work_item_id = ? AND page.status <> 'COMPLETED'
                ORDER BY binding.reconciliation_key, binding.reference_type, binding.reference_key%s
                """.formatted(lockRows ? " FOR UPDATE" : ""), String.class, work.id());
        return unfinishedStageBindings.isEmpty();
    }

    private boolean hasExecutionSlot(String taskId, boolean lockRows) {
        if (lockRows) {
            return !jdbcTemplate.queryForList("""
                    SELECT slot_number FROM task_execution_slot
                    WHERE task_id = ? ORDER BY slot_number FOR UPDATE
                    """, Integer.class, taskId).isEmpty();
        }
        return count("SELECT COUNT(*) FROM task_execution_slot WHERE task_id = ?", taskId) != 0;
    }

    private boolean hasCompleteFrozenInventory(String taskId, boolean lockRows) {
        if (!lockRows) {
            return count("SELECT COUNT(*) FROM material_inventory_document WHERE task_id = ?", taskId) > 0
                    && count("SELECT COUNT(*) FROM material_inventory_document WHERE task_id = ? AND complete = FALSE",
                            taskId) == 0;
        }
        List<Boolean> completion = jdbcTemplate.queryForList("""
                SELECT complete FROM material_inventory_document
                WHERE task_id = ? ORDER BY document_id FOR UPDATE
                """, Boolean.class, taskId);
        return !completion.isEmpty() && completion.stream().allMatch(Boolean.TRUE::equals);
    }

    private boolean hasReconciliationRun(String workItemId, boolean lockRows) {
        if (lockRows) {
            return !jdbcTemplate.queryForList("""
                    SELECT work_item_id FROM structured_reconciliation_run
                    WHERE work_item_id = ? LIMIT 1 FOR UPDATE
                    """, String.class, workItemId).isEmpty();
        }
        return count("SELECT COUNT(*) FROM structured_reconciliation_run WHERE work_item_id = ?", workItemId) != 0;
    }

    private static boolean isV2ReconciliationWork(RetryWorkRow work) {
        return "feature-scope-reconciliation".equals(work.skillName())
                && "FEATURE_SCOPE_RECONCILIATION_V2".equals(work.operationName());
    }

    private boolean hasArtifactCoordinates(String taskId, boolean lockRows) {
        if (lockRows) {
            return !jdbcTemplate.queryForList("""
                    SELECT id FROM generation_task
                    WHERE id = ? AND (artifact_id IS NOT NULL OR artifact_sha256 IS NOT NULL OR artifact_path IS NOT NULL)
                    LIMIT 1 FOR UPDATE
                    """, String.class, taskId).isEmpty();
        }
        return count("""
                SELECT COUNT(*) FROM generation_task
                WHERE id = ? AND (artifact_id IS NOT NULL OR artifact_sha256 IS NOT NULL OR artifact_path IS NOT NULL)
                """, taskId) != 0;
    }

    /**
     * Allows one user-triggered recovery only after the existing bounded transient attempts were exhausted.
     *
     * <p>The database intentionally stores KEE's stable {@code model_execution_failed} type rather than log-only
     * network details. Requiring the exact extraction operation and an all-transient terminal history prevents a
     * first failure, a mixed business failure, or another structured operation from entering this recovery branch.
     * The caller separately proves the failed work is the sole unfinished zero-write leaf under task/work locks.</p>
     *
     * [Req-ID]: REQ-ESR-009
     */
    private boolean isExhaustedFeatureExtractionModelFailure(
            RetryWorkRow work, RetryAttemptRow attempt) {
        if (!"feature-scope-reconciliation".equals(work.skillName())
                || !"FEATURE_SCOPE_EXTRACT".equals(work.operationName())
                || !"model_execution_failed".equals(attempt.failureType())) {
            return false;
        }
        int attempts = count(
                "SELECT COUNT(*) FROM structured_generation_attempt WHERE work_item_id = ?", work.id());
        if (attempts < StructuredGenerationAcceptanceStore.MAX_ATTEMPTS) return false;
        return count("""
                SELECT COUNT(*) FROM structured_generation_attempt
                WHERE work_item_id = ?
                  AND (status <> 'FAILED' OR failure_type IS NULL OR failure_type <> 'model_execution_failed')
                """, work.id()) == 0;
    }

    /**
     * Proves that a capacity failure belongs to one persisted parsed unit and therefore cannot be split again.
     *
     * <p>{@code material_key} is intentionally not used here: the isolated-skill contract defines it as an
     * opaque caller key, not a document identifier. The task inventory must instead contain exactly one unit
     * matching the frozen evidence key and ordinal; a cross-document key collision fails closed.</p>
     *
     * [Req-ID]: REQ-ESR-005
     */
    private boolean isIrreducibleRequirementReviewLeaf(String taskId, RetryWorkRow work) {
        if (!"requirement-material-quality-review".equals(work.skillName())
                || !"REQUIREMENT_MATERIAL_REVIEW".equals(work.operationName())
                || work.ordinalStart() == null
                || !work.ordinalStart().equals(work.ordinalEnd())
                || !"ARRAY".equals(work.evidenceJsonType())
                || work.evidenceKeyCount() != 1
                || work.evidenceKey() == null
                || work.evidenceKey().isBlank()) {
            return false;
        }
        return count("""
                SELECT COUNT(*) FROM material_inventory_unit
                WHERE task_id = ? AND unit_id = ? AND ordinal = ?
                """, taskId, work.evidenceKey(), work.ordinalStart()) == 1;
    }

    private StructuredRetryDecision unavailableRetry(String reason) {
        return new StructuredRetryDecision(
                StructuredRetryEligibility.unavailable(reason), null, StructuredRetryMutation.REQUEUE_FAILED_WORK);
    }

    private static Integer nullableInteger(ResultSet row, String column) throws SQLException {
        Number value = (Number) row.getObject(column);
        return value == null ? null : Math.toIntExact(value.longValue());
    }

    private int unfinishedWorkOwnedBusinessRowCount(String taskId, boolean lockRows) {
        for (String table : UNFINISHED_WORK_BUSINESS_TABLES) {
            // Each query starts from the task-owned unfinished work and exits after the first matching row. This
            // keeps advisory reads bounded by one task instead of materializing result rows from every retained task.
            if (!jdbcTemplate.queryForList(
                    unfinishedWorkOwnedBusinessRowSql(table, lockRows), String.class, taskId).isEmpty()) {
                return 1;
            }
        }
        return 0;
    }

    static String unfinishedWorkOwnedBusinessRowSql(String table, boolean lockRows) {
        if (!UNFINISHED_WORK_BUSINESS_TABLES.contains(table)) {
            throw new IllegalArgumentException("Unsupported work-owned business table");
        }
        // The table identifier comes only from the fixed server-owned catalog. FOR UPDATE makes the mutation path a
        // MySQL current read; the already locked work row prevents a later child insert from crossing this gate.
        return """
                SELECT business_row.work_item_id
                FROM structured_generation_work_item work
                JOIN %s business_row ON business_row.work_item_id = work.id
                WHERE work.task_id = ? AND work.status NOT IN ('COMPLETED', 'SPLIT', 'SUPERSEDED')
                LIMIT 1%s
                """.formatted(table, lockRows ? " FOR UPDATE" : "");
    }

    private boolean hasTaskOwnedRowCurrent(String table, String taskId) {
        // This helper accepts only fixed table names from its private callers; no request data becomes SQL syntax.
        return !jdbcTemplate.queryForList("SELECT task_id FROM " + table
                + " WHERE task_id = ? LIMIT 1 FOR UPDATE", String.class, taskId).isEmpty();
    }

    private boolean hasStructuredWork(String taskId) {
        return count("SELECT COUNT(*) FROM structured_generation_work_item WHERE task_id = ?", taskId) > 0;
    }

    private boolean isUnbatchedAllTask(String taskId) {
        Integer batches = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM generation_batch WHERE task_id = ?", Integer.class, taskId);
        String mode = jdbcTemplate.queryForObject("SELECT task_mode FROM generation_task WHERE id = ?", String.class, taskId);
        return batches != null && batches == 0 && GenerationTaskMode.ALL.name().equals(mode);
    }

    /**
     * [Req-ID]: REQ-TSK-005, REQ-TSK-007, REQ-TSK-009, REQ-ANA-006
     *
     * <p>Atomically accepts one running Markdown batch. A completed replay is rejected before rows are
     * changed, while a failed batch that has been retried can accept exactly its new running attempt.</p>
     */
    public void acceptMarkdownBatch(String batchId, String attemptId, MarkdownGenerationResult result) {
        transactionTemplate.executeWithoutResult(ignored -> {
            if (batchStatus(batchId) == GenerationBatchStatus.ACCEPTED) {
                throw new IllegalStateException("Batch is already accepted and cannot be replayed: " + batchId);
            }
            requireBatchStatus(batchId, GenerationBatchStatus.RUNNING);
            int batchChanged = jdbcTemplate.update("""
                            UPDATE generation_batch
                            SET status = 'ACCEPTED', raw_completed_markdown = ?, lease_owner = NULL, lease_expires_at = NULL
                            WHERE id = ? AND status = 'RUNNING'
                            """, result.rawMarkdown(), batchId);
            if (batchChanged != 1) {
                throw new IllegalStateException("Batch acceptance did not update exactly one running batch");
            }
            jdbcTemplate.update("DELETE FROM generation_audit_row WHERE batch_id = ?", batchId);
            jdbcTemplate.update("DELETE FROM generation_test_case_row WHERE batch_id = ?", batchId);
            persistMarkdownRows(batchId, result);
            int attemptChanged = jdbcTemplate.update("""
                            UPDATE generation_attempt
                            SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP(6)
                            WHERE id = ? AND batch_id = ? AND status = 'RUNNING'
                            """, attemptId, batchId);
            if (attemptChanged != 1) {
                throw new IllegalStateException("Batch acceptance did not update exactly one running attempt");
            }
        });
    }

    public void failBatch(String batchId, String attemptId, String failureReason) {
        failBatch(batchId, attemptId, failureReason, false);
    }

    public void failBatch(String batchId, String attemptId, String failureReason, boolean retryable) {
        requireBatchStatus(batchId, GenerationBatchStatus.RUNNING);
        int attemptChanged = jdbcTemplate.update("""
                        UPDATE generation_attempt
                        SET status = 'FAILED', failure_reason = ?, retryable = ?, completed_at = CURRENT_TIMESTAMP(6)
                        WHERE id = ? AND status = 'RUNNING'
                        """, failureReason, retryable, attemptId);
        int batchChanged = jdbcTemplate.update("UPDATE generation_batch SET status = 'FAILED', lease_owner = NULL, lease_expires_at = NULL WHERE id = ? AND status = 'RUNNING'", batchId);
        if (batchChanged != 1 || attemptChanged != 1) {
            throw new IllegalStateException("Batch failure did not update exactly one batch and attempt");
        }
    }

    public GenerationTaskStatus finishTaskFromBatches(String taskId) {
        requireTaskStatus(taskId, GenerationTaskStatus.VALIDATING);
        FinalizationReadiness readiness = finalizationReadiness(taskId);
        if (readiness.artifactRequired()) {
            throw new IllegalStateException("A validated artifact is required before task finalization");
        }
        finishWithoutArtifact(taskId, readiness.terminalStatus());
        return readiness.terminalStatus();
    }

    public boolean requestCancellation(String taskId) {
        GenerationTaskStatus current = taskStatus(taskId);
        if (current.isTerminal()) {
            return false;
        }
        jdbcTemplate.update("""
                        UPDATE generation_task SET cancellation_requested_at = COALESCE(cancellation_requested_at, CURRENT_TIMESTAMP(6))
                        WHERE id = ?
                        """, taskId);
        if (current == GenerationTaskStatus.QUEUED) {
            cancelQueuedTask(taskId);
        }
        return true;
    }

    public boolean isCancellationRequested(String taskId) {
        Boolean requested = jdbcTemplate.queryForObject(
                "SELECT cancellation_requested_at IS NOT NULL FROM generation_task WHERE id = ?", Boolean.class, taskId);
        return Boolean.TRUE.equals(requested);
    }

    /**
     * Commits a user-requested cancellation observed between durable ALL-mode audit work items.
     *
     * <p>An auditing task has not created generation batches yet, so it must transition directly instead of using the
     * batch checkpoint path. This preserves cancellation as a truthful terminal state rather than recording it as an
     * audit failure.</p>
     *
     * [Req-ID]: REQ-CAG-004
     */
    public boolean cancelAuditingAtCheckpoint(String taskId) {
        if (!isCancellationRequested(taskId)) {
            return false;
        }
        GenerationTaskStatus current = taskStatus(taskId);
        if (current.isTerminal()) {
            return false;
        }
        if (current != GenerationTaskStatus.AUDITING) {
            throw new IllegalStateException("Auditing cancellation checkpoint reached in unexpected task status: " + current);
        }
        transitionTask(taskId, GenerationTaskStatus.CANCELLED);
        clearArtifactMetadata(taskId);
        return true;
    }

    public boolean cancelAtCheckpoint(String taskId, String batchId, String attemptId) {
        if (!isCancellationRequested(taskId)) {
            return false;
        }
        GenerationTaskStatus current = taskStatus(taskId);
        if (current.isTerminal()) {
            return false;
        }
        if (!batchStatus(batchId).isTerminal()) {
            jdbcTemplate.update("UPDATE generation_batch SET status = 'CANCELLED' WHERE id = ?", batchId);
            jdbcTemplate.update("""
                            UPDATE generation_attempt SET status = 'CANCELLED', completed_at = CURRENT_TIMESTAMP(6)
                            WHERE id = ? AND status IN ('QUEUED', 'RUNNING')
                            """, attemptId);
        }
        jdbcTemplate.update("""
                        UPDATE generation_attempt a JOIN generation_batch b ON b.id = a.batch_id
                        SET a.status = 'CANCELLED', a.completed_at = CURRENT_TIMESTAMP(6)
                        WHERE b.task_id = ? AND a.status = 'QUEUED'
                        """, taskId);
        jdbcTemplate.update("UPDATE generation_batch SET status = 'CANCELLED' WHERE task_id = ? AND status = 'QUEUED'", taskId);
        transitionTask(taskId, GenerationTaskStatus.CANCELLED);
        clearArtifactMetadata(taskId);
        return true;
    }

    public TaskExecutionWork requireQueuedWork(String taskId) {
        return nextQueuedWork(taskId).orElseThrow(() -> new IllegalStateException("Claimed task has no queued batch: " + taskId));
    }

    public Optional<TaskExecutionWork> nextQueuedWork(String taskId) {
        CreateGenerationTaskRequest request = jdbcTemplate.query("""
                        SELECT request_snapshot FROM generation_task
                        WHERE id = ? AND status IN ('AUDITING', 'GENERATING')
                        """, (resultSet, ignored) -> fromJson(resultSet.getString("request_snapshot"), CreateGenerationTaskRequest.class), taskId)
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("Claimed task is not active: " + taskId));
        return jdbcTemplate.query("""
                        SELECT b.id AS batch_id, b.feature_id, a.id AS attempt_id
                        FROM generation_batch b JOIN generation_attempt a ON a.batch_id = b.id
                        WHERE b.task_id = ? AND b.status = 'QUEUED' AND a.status = 'QUEUED'
                        ORDER BY b.batch_sequence, a.attempt_number
                        LIMIT 1
        """, (resultSet, ignored) -> new TaskExecutionWork(
                taskId, resultSet.getString("batch_id"), resultSet.getString("attempt_id"), resultSet.getString("feature_id"), request), taskId)
                .stream().findFirst();
    }

    /**
     * Returns only the immediately preceding failed attempt reason for a retry attempt. The caller must map this
     * persisted diagnostic to fixed safe guidance before it can influence an agent prompt.
     *
     * [Req-ID]: REQ-CAG-007
     */
    public Optional<String> previousFailureReason(String batchId, String attemptId) {
        return jdbcTemplate.query("""
                        SELECT previous.failure_reason
                        FROM generation_attempt current_attempt
                        JOIN generation_attempt previous ON previous.batch_id = current_attempt.batch_id
                            AND previous.attempt_number = current_attempt.attempt_number - 1
                        WHERE current_attempt.id = ? AND current_attempt.batch_id = ?
                          AND current_attempt.attempt_number BETWEEN 2 AND ?
                          AND previous.status = 'FAILED' AND previous.failure_reason IS NOT NULL
                        """, (resultSet, ignored) -> resultSet.getString("failure_reason"),
                attemptId, batchId, MAX_ATTEMPTS).stream().findFirst();
    }

    public CreateGenerationTaskRequest request(String taskId) {
        return jdbcTemplate.query("SELECT request_snapshot FROM generation_task WHERE id = ?",
                (resultSet, ignored) -> fromJson(resultSet.getString("request_snapshot"), CreateGenerationTaskRequest.class), taskId)
                .stream().findFirst().orElseThrow(() -> new GenerationTaskNotFoundException(taskId));
    }

    /**
     * [Req-ID]: REQ-TSK-005, REQ-TSK-009, REQ-ANA-006
     *
     * <p>Reads only accepted Markdown rows in the durable batch and row order used by preview and export.
     * Raw Markdown remains a batch diagnostic snapshot and is intentionally excluded from this aggregate.</p>
     */
    public MarkdownTaskRows acceptedMarkdownRows(String taskId) {
        List<MarkdownAuditRow> auditRows = jdbcTemplate.query("""
                        SELECT audit.row_sequence, audit.subject_or_feature, audit.issue_category, audit.evidence_comparison
                        FROM generation_audit_row audit
                        JOIN generation_batch batch ON batch.id = audit.batch_id
                        WHERE batch.task_id = ? AND batch.status = 'ACCEPTED'
                        ORDER BY batch.batch_sequence, audit.row_sequence
                        """, (resultSet, ignored) -> new MarkdownAuditRow(
                resultSet.getInt("row_sequence"),
                stripMachineEvidenceTokens(resultSet.getString("subject_or_feature")),
                stripMachineEvidenceTokens(resultSet.getString("issue_category")),
                stripMachineEvidenceTokens(resultSet.getString("evidence_comparison"))), taskId);
        List<MarkdownTestCaseRow> testCaseRows = jdbcTemplate.query("""
                        SELECT test_case.case_name, test_case.feature_module, test_case.preconditions,
                               test_case.execution_steps, test_case.expected_result, test_case.requirement_content
                        FROM generation_test_case_row test_case
                        JOIN generation_batch batch ON batch.id = test_case.batch_id
                        WHERE batch.task_id = ? AND batch.status = 'ACCEPTED'
                        ORDER BY batch.batch_sequence, test_case.row_sequence
                        """, (resultSet, ignored) -> new MarkdownTestCaseRow(
                stripMachineEvidenceTokens(resultSet.getString("case_name")),
                stripMachineEvidenceTokens(resultSet.getString("feature_module")),
                stripMachineEvidenceTokens(resultSet.getString("preconditions")),
                stripMachineEvidenceTokens(resultSet.getString("execution_steps")),
                stripMachineEvidenceTokens(resultSet.getString("expected_result")),
                stripMachineEvidenceTokens(resultSet.getString("requirement_content"))), taskId);
        return new MarkdownTaskRows(auditRows, testCaseRows);
    }

    /**
     * Assembles the two workbook sheets from the task-owned durable sources only.
     *
     * <p>Batch-local audit rows are diagnostics for an individual model call and cannot represent the completed
     * bidirectional material review. The export therefore uses the final review ledger for the first sheet and only
     * accepted batch rows for the second sheet. Candidate identifiers remain a persistence-time validation aid and
     * are intentionally removed from the business-facing workbook.</p>
     *
     * [Req-ID]: REQ-CAG-005, REQ-CAG-006
     */
    public MarkdownTaskRows exportMarkdownRows(String taskId) {
        List<MarkdownAuditRow> auditRows = jdbcTemplate.query("""
                        SELECT conclusion_sequence, conclusion_type, explanation, evidence_text
                        FROM feature_review_conclusion
                        WHERE task_id = ?
                        ORDER BY conclusion_sequence
                        """, (resultSet, ignored) -> new MarkdownAuditRow(
                resultSet.getInt("conclusion_sequence"),
                stripMachineEvidenceTokens(resultSet.getString("explanation")),
                exportIssueCategory(FeatureReviewConclusionType.valueOf(resultSet.getString("conclusion_type"))),
                stripMachineEvidenceTokens(resultSet.getString("evidence_text"))), taskId);
        List<MarkdownTestCaseRow> testCaseRows = jdbcTemplate.query("""
                        SELECT test_case.case_name, test_case.feature_module, test_case.preconditions,
                               test_case.execution_steps, test_case.expected_result, test_case.requirement_content
                        FROM generation_test_case_row test_case
                        JOIN generation_batch batch ON batch.id = test_case.batch_id
                        WHERE batch.task_id = ? AND batch.status = 'ACCEPTED'
                        ORDER BY batch.batch_sequence, test_case.row_sequence
                        """, (resultSet, ignored) -> new MarkdownTestCaseRow(
                stripMachineEvidenceTokens(resultSet.getString("case_name")),
                stripMachineEvidenceTokens(resultSet.getString("feature_module")),
                stripMachineEvidenceTokens(resultSet.getString("preconditions")),
                stripMachineEvidenceTokens(resultSet.getString("execution_steps")),
                stripMachineEvidenceTokens(resultSet.getString("expected_result")),
                stripMachineEvidenceTokens(resultSet.getString("requirement_content"))), taskId);
        return new MarkdownTaskRows(auditRows, testCaseRows);
    }

    private static String exportIssueCategory(FeatureReviewConclusionType conclusionType) {
        return switch (conclusionType) {
            case MATCHED -> "未发现问题";
            case FUNCTION_LIST_MISSING -> "功能清单遗漏";
            case REQUIREMENT_MISSING -> "需求未覆盖该功能点";
            case CONFLICT -> "需求与功能清单冲突";
            case SPLIT -> "功能点拆分";
            case MERGE -> "功能点合并";
            case DUPLICATE -> "功能点重复";
            case INSUFFICIENT_EVIDENCE -> "证据不足";
        };
    }

    /** Removes internal candidate, source-unit and reconciliation-group binding tokens from reader-facing projections. [Req-ID]: REQ-CWR-003 */
    private static String stripMachineEvidenceTokens(String value) {
        if (value == null || value.isBlank()) return value == null ? "" : value.strip();
        String sanitized = value.replaceAll("(?i)(?:candidateIds|groupAnchorId|documentId|unitId)\\s*=\\s*[^;\\r\\n<]*(?:;\\s*)?", "");
        return sanitized.replaceAll("(?:<br>|\\R)\\s*(?=<br>|\\R|$)", "").strip();
    }

    /** Completes or partially completes a task using its already-persisted Markdown rows. */
    public void completeMarkdownTask(String taskId, GenerationTaskStatus terminalStatus, WorkbookArtifact artifact) {
        if (terminalStatus != GenerationTaskStatus.COMPLETED && terminalStatus != GenerationTaskStatus.PARTIAL) {
            throw new IllegalArgumentException("Markdown task must finish COMPLETED or PARTIAL");
        }
        transactionTemplate.executeWithoutResult(ignored -> {
            requireTaskStatus(taskId, GenerationTaskStatus.VALIDATING);
            FinalizationReadiness readiness = finalizationReadiness(taskId);
            if (readiness.terminalStatus() != terminalStatus || !readiness.artifactRequired()) {
                throw new IllegalStateException("ALL completion or partial artifact gate is not satisfied");
            }
            int changed = jdbcTemplate.update("""
                            UPDATE generation_task SET status = ?, artifact_id = ?, artifact_sha256 = ?, artifact_path = ?
                            WHERE id = ? AND status = 'VALIDATING'
                            """, terminalStatus.name(), artifact.artifactId(), artifact.sha256(), artifact.path().toString(), taskId);
            if (changed != 1) throw new IllegalStateException("Markdown task completion did not update exactly one validating task");
        });
    }

    /** Finishes a non-exportable terminal result while removing stale artifact metadata. [Req-ID]: REQ-CAG-004 */
    public void finishWithoutArtifact(String taskId, GenerationTaskStatus terminalStatus) {
        if (terminalStatus != GenerationTaskStatus.FAILED && terminalStatus != GenerationTaskStatus.PARTIAL) {
            throw new IllegalArgumentException("Only FAILED or artifact-free PARTIAL may finish without an artifact");
        }
        requireTaskStatus(taskId, GenerationTaskStatus.VALIDATING);
        int changed = jdbcTemplate.update("""
                        UPDATE generation_task
                        SET status = ?, artifact_id = NULL, artifact_sha256 = NULL, artifact_path = NULL
                        WHERE id = ? AND status = 'VALIDATING'
                        """, terminalStatus.name(), taskId);
        if (changed != 1) throw new IllegalStateException("Task finalization did not update exactly one validating task");
    }

    /**
     * Computes the only truthful terminal outcome from durable state. ALL mode is deliberately stricter than the
     * legacy specified-feature path: an exporter can never turn incomplete material/audit/freeze state into a
     * downloadable result.
     *
     * [Req-ID]: REQ-CAG-004, REQ-CAG-005
     */
    public FinalizationReadiness finalizationReadiness(String taskId) {
        CreateGenerationTaskRequest request = request(taskId);
        BatchCounts batches = batchCounts(taskId);
        if (request.taskMode() != GenerationTaskMode.ALL) {
            GenerationTaskStatus terminal = batches.accepted() == 0 ? GenerationTaskStatus.FAILED : batches.failed() > 0
                    ? batches.accepted() > 0 ? GenerationTaskStatus.PARTIAL : GenerationTaskStatus.FAILED
                    : GenerationTaskStatus.COMPLETED;
            return new FinalizationReadiness(terminal, terminal != GenerationTaskStatus.FAILED && batches.accepted() > 0);
        }
        if (!hasCompleteAllAuditAndFreeze(taskId, request.requirementScope())) {
            return new FinalizationReadiness(GenerationTaskStatus.FAILED, false);
        }
        List<FrozenFeatureTarget> targets = frozenFeatureTargets(taskId);
        int eligibleTargets = (int) targets.stream().filter(FrozenFeatureTarget::generationEligible).count();
        boolean hasIneligibleTarget = eligibleTargets != targets.size();
        boolean acceptedRowsAreExact = acceptedBatchesHaveExactlyTwoRows(taskId)
                && acceptedTestCaseRowCount(taskId) == batches.accepted() * 2;
        boolean everyEligibleTargetAccepted = batches.total() == eligibleTargets && batches.completed() == eligibleTargets
                && batches.accepted() == eligibleTargets && acceptedFrozenBatchCount(taskId) == eligibleTargets;
        if (!hasIneligibleTarget && everyEligibleTargetAccepted && acceptedRowsAreExact) {
            return new FinalizationReadiness(GenerationTaskStatus.COMPLETED, true);
        }
        int permanentGenerationFailureCount = permanentlyFailedBatchCount(taskId);
        boolean everyEligibleTargetTerminal = batches.total() == eligibleTargets && batches.completed() == eligibleTargets
                && acceptedFrozenBatchCount(taskId) == batches.accepted()
                && batches.accepted() + permanentGenerationFailureCount == eligibleTargets;
        if ((hasIneligibleTarget || permanentGenerationFailureCount > 0) && everyEligibleTargetTerminal
                && batches.accepted() > 0 && acceptedRowsAreExact) {
            return new FinalizationReadiness(GenerationTaskStatus.PARTIAL, true);
        }
        return new FinalizationReadiness(GenerationTaskStatus.FAILED, false);
    }

    public void failTask(String taskId, String batchId, String attemptId, String failureReason) {
        if (batchStatus(batchId) == GenerationBatchStatus.RUNNING) {
            failBatch(batchId, attemptId, failureReason);
        }
        GenerationTaskStatus current = taskStatus(taskId);
        if (!current.isTerminal()) {
            transitionTask(taskId, GenerationTaskStatus.FAILED);
        }
    }

    private void cancelQueuedTask(String taskId) {
        jdbcTemplate.update("""
                        UPDATE generation_attempt a JOIN generation_batch b ON b.id = a.batch_id
                        SET a.status = 'CANCELLED', a.completed_at = CURRENT_TIMESTAMP(6)
                        WHERE b.task_id = ? AND a.status = 'QUEUED'
                        """, taskId);
        jdbcTemplate.update("UPDATE generation_batch SET status = 'CANCELLED' WHERE task_id = ? AND status = 'QUEUED'", taskId);
        transitionTask(taskId, GenerationTaskStatus.CANCELLED);
        clearArtifactMetadata(taskId);
    }

    public Optional<GenerationTaskDetail> findDetail(String taskId) {
        return findDetail(taskId, StructuredDetailQuery.defaults());
    }

    /** Reads a bounded V2 detail projection; V1 ignores the V2-only paging query. [Req-ID]: REQ-TGV2-009 */
    public Optional<GenerationTaskDetail> findDetail(String taskId, StructuredDetailQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        Optional<GenerationTaskDetail> detail = detailSnapshotTemplate.execute(
                ignored -> findDetailInSnapshot(taskId, query));
        return detail == null ? Optional.empty() : detail;
    }

    /** Keeps the count, page rows, and their batched children on one repeatable-read database snapshot. */
    private Optional<GenerationTaskDetail> findDetailInSnapshot(String taskId, StructuredDetailQuery query) {
        return jdbcTemplate.query("""
                        SELECT t.id, t.task_mode, t.status, t.structured_processing_status, t.structured_coverage_status,
                               t.request_snapshot, t.result_snapshot,
                               t.artifact_id, t.artifact_sha256,
                               (SELECT a.failure_reason FROM generation_attempt a
                                JOIN generation_batch b ON b.id = a.batch_id
                                WHERE b.task_id = t.id AND b.status = 'FAILED'
                                  AND a.attempt_number = (SELECT MAX(latest.attempt_number)
                                      FROM generation_attempt latest WHERE latest.batch_id = b.id)
                                  AND a.failure_reason IS NOT NULL
                                ORDER BY b.batch_sequence, a.id LIMIT 1) AS failure_summary
                        FROM generation_task t WHERE t.id = ?
        """, (resultSet, ignored) -> new TaskRow(
                resultSet.getString("id"),
                GenerationTaskMode.valueOf(resultSet.getString("task_mode")),
                GenerationTaskStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("structured_processing_status"),
                resultSet.getString("structured_coverage_status"),
                resultSet.getString("request_snapshot"),
                resultSet.getString("result_snapshot"),
                resultSet.getString("artifact_id"),
                resultSet.getString("artifact_sha256"),
                resultSet.getString("failure_summary")), taskId).stream().findFirst().map(row -> {
            BatchCounts counts = batchCounts(taskId);
            String failureSummary = row.failureSummary() != null ? row.failureSummary()
                    : taskFailureSummary(row.resultSnapshot());
            CreateGenerationTaskRequest request = fromJson(row.requestSnapshot(), CreateGenerationTaskRequest.class);
            return new GenerationTaskDetail(
                    row.id(), row.taskMode(), row.status(), counts.total(), counts.completed(),
                    row.artifactId() != null, row.artifactId(), row.artifactSha256(), failureSummary, failureSummary, batches(taskId),
                    acceptedMarkdownRows(taskId),
                    request, businessProgress(taskId, row.taskMode(), row.status(), request),
                    structuredResult(taskId, row.structuredProcessingStatus(), row.structuredCoverageStatus(),
                            () -> request.requirementScope().documents().size(), query));
        });
    }

    /** Reads the same committed structured tables as task detail and maps them directly to the fixed workbook. */
    public StructuredWorkbookExportRequest structuredWorkbookRequest(String taskId) {
        boolean v2 = GenerationContractVersions.V2.equals(taskWorkflowVersion(taskId));
        List<StructuredReviewRow> reviewRows = new ArrayList<>();
        reviewRows.addAll(jdbcTemplate.query("""
                SELECT w.id, f.finding_key, w.source_label, f.issue_type, f.description, f.root_cause_kind,
                       f.affected_scope_summary, f.bad_source_quote, f.proposed_good_text, f.test_design_impact,
                       f.current_project_recommendation, f.design_center_guideline_recommendation, f.handling_level
                FROM structured_review_finding f
                JOIN structured_generation_work_item w ON w.id = f.work_item_id
                WHERE f.task_id = ? AND w.status = 'COMPLETED' AND w.accepted_result_sha256 IS NOT NULL
                ORDER BY w.created_at, f.finding_key
                """, (row, index) -> new StructuredReviewRow(row.getString("id") + ":finding:" + row.getString("finding_key"),
                index + 1, StructuredReviewRow.Source.REQUIREMENT_MATERIAL_REVIEW,
                 readerSafeText(orDefault(row.getString("source_label"), "需求材料")),
                 reviewClassification(row.getString("root_cause_kind"), row.getString("issue_type")),
                 readerSafeText(row.getString("affected_scope_summary")), readerSafeText(row.getString("description")),
                 readerSafeText(row.getString("bad_source_quote")), readerSafeText(row.getString("proposed_good_text")),
                 readerSafeText(row.getString("test_design_impact")), readerSafeText(row.getString("current_project_recommendation")),
                 readerSafeText(row.getString("design_center_guideline_recommendation")), handlingDisplay(row.getString("handling_level")),
                 readerSafeText(orDefault(row.getString("source_label"), "需求材料")), true), taskId));
        for (CandidateAuditProjection candidate : functionCandidateAudit(taskId)) {
            reviewRows.add(new StructuredReviewRow(candidate.sourceId(), reviewRows.size() + 1,
                    StructuredReviewRow.Source.FUNCTION_CANDIDATE_AUDIT, candidate.subject(), candidate.status(),
                    candidate.affectedScope(), candidate.summary(), "", "", "", "", "", candidate.severity(),
                    "功能清单原文", true));
        }
        int offset = reviewRows.size();
        reviewRows.addAll(jdbcTemplate.query("""
                SELECT r.work_item_id, r.reconciliation_key, r.classification, r.scope_recommendation
                FROM structured_feature_reconciliation r
                JOIN structured_generation_work_item w ON w.id = r.work_item_id
                WHERE r.task_id = ? AND w.status = 'COMPLETED' AND w.accepted_result_sha256 IS NOT NULL
                ORDER BY w.created_at, r.reconciliation_key
                """, (row, index) -> {
            String workItemId = row.getString("work_item_id");
            String key = row.getString("reconciliation_key");
            List<String> subjects = new ArrayList<>(reconciliationFunctionListPaths(taskId, workItemId, key));
            subjects.addAll(reconciliationRequirementFunctions(taskId, workItemId, key));
            return new StructuredReviewRow(workItemId + ":reconciliation:" + key, offset + index + 1,
                    StructuredReviewRow.Source.FEATURE_RECONCILIATION,
                    readerSafeText(subjects.isEmpty() ? "功能范围" : String.join(" / ", subjects)),
                    reconciliationDisplay(row.getString("classification")), "", readerSafeText(row.getString("scope_recommendation")),
                    "", "", "", "", "", "", "功能清单与需求事实", true);
        }, taskId));
        if (v2) {
            // V2 replaces admission review and feature reconciliation with non-blocking testability feedback.
            reviewRows.clear();
            reviewRows.addAll(v2TestabilityFeedbackRows(taskId));
            reviewRows.addAll(v2GenerationOutcomeRows(taskId, reviewRows.size()));
        }
        List<StructuredTestCaseRow> cases = jdbcTemplate.query("""
                SELECT c.work_item_id, c.case_key, c.name_text, c.title, c.priority, c.preconditions_json,
                       c.hardware_configuration_json, c.software_configuration_json, c.test_configuration_json,
                       c.parameter_configuration_json, c.inputs_json, c.expected_results_json, c.evaluation_criteria,
                       c.result_evaluation_criteria, c.termination_conditions_json, c.result_collection,
                       c.author_name, c.author_date, c.case_status, c.missing_information_json, p.function_name
                FROM structured_test_case c
                JOIN structured_test_point p ON p.work_item_id = c.work_item_id
                JOIN structured_generation_work_item w ON w.id = c.work_item_id
                WHERE c.task_id = ? AND w.status = 'COMPLETED' AND w.accepted_result_sha256 IS NOT NULL
                  %s
                ORDER BY w.created_at, c.case_key
                """.formatted(v2 ? "AND c.case_status = 'FORMAL'" : ""), (row, ignored) -> {
            String workItemId = row.getString("work_item_id");
            String caseKey = row.getString("case_key");
            List<StructuredTestStep> steps = structuredTestcaseSteps(workItemId, caseKey).stream()
                    .map(step -> new StructuredTestStep(step.stepNo(), step.action(), step.expected(),
                            step.evaluationCriteria(), step.terminationOrError(), step.resultCollection())).toList();
            List<String> expectedResults = readerSafeNullableList(row.getString("expected_results_json"));
            if (expectedResults.isEmpty()) expectedResults = steps.stream().map(StructuredTestStep::expected).toList();
            String title = readerSafeText(row.getString("title"));
            return new StructuredTestCaseRow(workItemId + ":case:" + caseKey,
                    readerSafeText(orDefault(row.getString("name_text"), title)), title,
                    readerSafeText(row.getString("function_name")), priority(row.getString("priority")),
                    StructuredTestCaseRow.Status.valueOf(row.getString("case_status")),
                    readerSafeList(row.getString("preconditions_json")),
                    new StructuredTestCaseRow.Initialization(
                            readerSafeNullableList(row.getString("hardware_configuration_json")),
                            readerSafeNullableList(row.getString("software_configuration_json")),
                            readerSafeNullableList(row.getString("test_configuration_json")),
                            readerSafeNullableList(row.getString("parameter_configuration_json"))),
                    readerSafeInputs(row.getString("inputs_json")), steps, expectedResults,
                    readerSafeText(row.getString("evaluation_criteria")), readerSafeText(row.getString("result_evaluation_criteria")),
                    readerSafeNullableList(row.getString("termination_conditions_json")), readerSafeText(row.getString("result_collection")),
                    new StructuredTestCaseRow.AuthoringInformation(readerSafeText(row.getString("author_name")),
                            readerSafeText(row.getString("author_date"))),
                    testcaseRequirementSummaries(taskId, workItemId, caseKey),
                    readerSafeList(row.getString("missing_information_json")), true);
        }, taskId);
        return new StructuredWorkbookExportRequest(taskId, reviewRows, cases);
    }

    /**
     * Opens a V2-only database row source whose individual queries remain bounded as task size grows.
     * Counts are frozen when the source is created; any later row drift is rejected by the exporter.
     *
     * [Req-ID]: REQ-TGV2-009
     */
    public StructuredWorkbookRowSource structuredWorkbookRows(String taskId) {
        if (!GenerationContractVersions.V2.equals(taskWorkflowVersion(taskId))) {
            throw new IllegalStateException("Historical task is read-only");
        }
        long feedbackCount = countV2TestabilityFeedback(taskId);
        long outcomeCount = countV2NonFormalOutcomes(taskId);
        long testcaseCount = countV2FormalTestcases(taskId);
        return new StructuredWorkbookRowSource() {
            @Override public String taskId() { return taskId; }
            @Override public long reviewRowCount() { return feedbackCount + outcomeCount; }
            @Override public long testCaseRowCount() { return testcaseCount; }
            @Override public void forEachReview(Consumer<StructuredReviewRow> consumer) {
                streamV2TestabilityFeedback(taskId, feedbackCount, consumer);
                streamV2GenerationOutcomes(taskId, feedbackCount, outcomeCount, consumer);
            }
            @Override public void forEachTestCase(Consumer<StructuredTestCaseRow> consumer) {
                streamV2FormalTestcases(taskId, testcaseCount, consumer);
            }
        };
    }

    private long countV2TestabilityFeedback(String taskId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM v2_testability_feedback feedback
                JOIN structured_generation_work_item work ON work.id = feedback.work_item_id
                WHERE feedback.task_id = ? AND work.status = 'COMPLETED'
                  AND work.accepted_result_sha256 IS NOT NULL
                """, Long.class, taskId);
        return count == null ? 0 : count;
    }

    private long countV2NonFormalOutcomes(String taskId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM v2_generation_outcome outcome
                JOIN structured_generation_work_item work ON work.id = outcome.work_item_id
                WHERE outcome.task_id = ? AND work.status = 'COMPLETED'
                  AND work.accepted_result_sha256 IS NOT NULL
                  AND (outcome.generation_outcome IN ('pending_only', 'unable_to_generate') OR EXISTS (
                      SELECT 1 FROM structured_test_case candidate
                      WHERE candidate.task_id = outcome.task_id
                        AND candidate.work_item_id = outcome.work_item_id
                        AND candidate.case_status = 'PENDING_CONFIRMATION'))
                """, Long.class, taskId);
        return count == null ? 0 : count;
    }

    private long countV2FormalTestcases(String taskId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM structured_test_case candidate
                JOIN structured_generation_work_item work ON work.id = candidate.work_item_id
                WHERE candidate.task_id = ? AND candidate.case_status = 'FORMAL'
                  AND work.status = 'COMPLETED' AND work.accepted_result_sha256 IS NOT NULL
                """, Long.class, taskId);
        return count == null ? 0 : count;
    }

    private void streamV2TestabilityFeedback(
            String taskId, long expected, Consumer<StructuredReviewRow> consumer) {
        long[] emitted = {0};
        streamV2ExportQuery("""
                SELECT feedback.feedback_key, approved.name_text, feedback.observation_type,
                       feedback.description_text, feedback.affected_fact_types_json
                FROM v2_testability_feedback feedback
                JOIN v2_approved_function approved
                  ON approved.task_id = feedback.task_id AND approved.function_key = feedback.function_key
                JOIN structured_generation_work_item work ON work.id = feedback.work_item_id
                WHERE feedback.task_id = ? AND work.status = 'COMPLETED'
                  AND work.accepted_result_sha256 IS NOT NULL
                ORDER BY BINARY feedback.feedback_key
                """, row -> {
            List<String> factTypes = readerSafeList(row.getString("affected_fact_types_json")).stream()
                    .map(GenerationTaskRepository::factTypeDisplay).toList();
            consumer.accept(new StructuredReviewRow("v2-feedback:" + row.getString("feedback_key"),
                    Math.toIntExact(++emitted[0]), StructuredReviewRow.Source.TESTABILITY_FEEDBACK,
                    readerSafeText(row.getString("name_text")),
                    feedbackTypeDisplay(row.getString("observation_type")),
                    factTypes.isEmpty() ? "" : String.join("、", factTypes),
                    readerSafeText(row.getString("description_text")), "", "", "", "", "", "",
                    "正式需求材料", true));
        }, taskId);
        requireExportedRowCount("V2 feedback", emitted[0], expected);
        if (countV2TestabilityFeedback(taskId) != expected) {
            throw new IllegalStateException("V2 feedback rows changed during export");
        }
    }

    private void streamV2GenerationOutcomes(
            String taskId, long sequenceOffset, long expected, Consumer<StructuredReviewRow> consumer) {
        long[] emitted = {0};
        // Correlated aggregation keeps pending-case details in the same streaming row without N+1 lookups.
        streamV2ExportQuery("""
                SELECT outcome.work_item_id, outcome.test_point_key, outcome.generation_outcome,
                       outcome.missing_information_json, point.function_name,
                       (SELECT JSON_ARRAYAGG(candidate.missing_information_json)
                        FROM structured_test_case candidate
                        WHERE candidate.task_id = outcome.task_id
                          AND candidate.work_item_id = outcome.work_item_id
                          AND candidate.case_status = 'PENDING_CONFIRMATION') AS pending_missing_json
                FROM v2_generation_outcome outcome
                JOIN structured_test_point point
                  ON point.task_id = outcome.task_id AND point.work_item_id = outcome.work_item_id
                 AND point.test_point_key = outcome.test_point_key
                JOIN structured_generation_work_item work ON work.id = outcome.work_item_id
                JOIN v2_approved_function approved
                  ON approved.task_id = outcome.task_id AND approved.function_key = outcome.function_key
                WHERE outcome.task_id = ? AND work.status = 'COMPLETED'
                  AND work.accepted_result_sha256 IS NOT NULL
                  AND (outcome.generation_outcome IN ('pending_only', 'unable_to_generate') OR EXISTS (
                      SELECT 1 FROM structured_test_case candidate
                      WHERE candidate.task_id = outcome.task_id
                        AND candidate.work_item_id = outcome.work_item_id
                        AND candidate.case_status = 'PENDING_CONFIRMATION'))
                ORDER BY BINARY outcome.test_point_key, BINARY outcome.work_item_id
                """, row -> {
            // Missing information is a semantic set. Canonical sorting makes regenerated artifacts independent of
            // MySQL JSON aggregation and physical insertion order. [Req-ID]: REQ-TGV2-009
            Set<String> missingItems = new java.util.TreeSet<>(
                    readerSafeList(row.getString("missing_information_json")));
            missingItems.addAll(readerSafeNestedList(row.getString("pending_missing_json")));
            if (missingItems.isEmpty()) {
                throw new IllegalStateException("Committed non-formal V2 outcome must retain missing information");
            }
            String classification = switch (row.getString("generation_outcome")) {
                case "generated" -> "含待确认用例";
                case "pending_only" -> "仅生成待确认用例";
                case "unable_to_generate" -> "无法生成用例";
                default -> throw new IllegalStateException("Unknown committed V2 generation outcome");
            };
            emitted[0]++;
            consumer.accept(new StructuredReviewRow(
                    "v2-outcome:" + row.getString("test_point_key") + ":" + row.getString("work_item_id"),
                    Math.toIntExact(sequenceOffset + emitted[0]), StructuredReviewRow.Source.GENERATION_OUTCOME,
                    readerSafeText(row.getString("function_name")), classification, "测试用例生成",
                    String.join("；", missingItems), "", "", "未计入正式覆盖", "补充缺失信息后重新生成", "",
                    "信息待补充", "已保存的生成结果", true));
        }, taskId);
        requireExportedRowCount("V2 generation outcome", emitted[0], expected);
        if (countV2NonFormalOutcomes(taskId) != expected) {
            throw new IllegalStateException("V2 generation outcome rows changed during export");
        }
    }

    private void streamV2FormalTestcases(
            String taskId, long expected, Consumer<StructuredTestCaseRow> consumer) {
        long[] emitted = {0};
        // Each accepted case is streamed once; scalar aggregates prevent per-case step and evidence queries.
        streamV2ExportQuery("""
                SELECT candidate.work_item_id, candidate.case_key, candidate.name_text, candidate.title,
                       candidate.priority, candidate.preconditions_json, candidate.hardware_configuration_json,
                       candidate.software_configuration_json, candidate.test_configuration_json,
                       candidate.parameter_configuration_json, candidate.inputs_json,
                       candidate.expected_results_json, candidate.evaluation_criteria,
                       candidate.result_evaluation_criteria, candidate.termination_conditions_json,
                       candidate.result_collection, candidate.author_name, candidate.author_date,
                       candidate.missing_information_json, point.function_name, point.test_point_key,
                       (SELECT JSON_ARRAYAGG(JSON_OBJECT(
                            'stepNo', step.step_no,
                            'action', step.action_text,
                            'expected', step.expected_text,
                            'evaluationCriteria', step.evaluation_criteria,
                            'terminationOrError', step.termination_or_error,
                            'resultCollection', step.result_collection))
                        FROM structured_test_case_step step
                        WHERE step.work_item_id = candidate.work_item_id
                          AND step.case_key = candidate.case_key) AS steps_json,
                       (SELECT JSON_ARRAYAGG(fact.statement_text)
                        FROM structured_reference_binding binding
                        JOIN v2_requirement_fact fact
                          ON fact.task_id = candidate.task_id AND fact.fact_key = binding.reference_key
                        JOIN structured_generation_work_item source ON source.id = fact.first_work_item_id
                        WHERE binding.work_item_id = candidate.work_item_id
                          AND binding.subject_key = candidate.case_key
                          AND binding.subject_type = 'TEST_CASE'
                          AND binding.reference_type = 'REQUIREMENT_FACT'
                          AND source.task_id = candidate.task_id AND source.status = 'COMPLETED'
                          AND source.accepted_result_sha256 IS NOT NULL) AS requirement_summaries_json
                FROM structured_test_case candidate
                JOIN structured_test_point point ON point.work_item_id = candidate.work_item_id
                JOIN structured_generation_work_item work ON work.id = candidate.work_item_id
                JOIN v2_generation_outcome outcome ON outcome.work_item_id = candidate.work_item_id
                JOIN v2_approved_function approved
                  ON approved.task_id = outcome.task_id AND approved.function_key = outcome.function_key
                WHERE candidate.task_id = ? AND candidate.case_status = 'FORMAL'
                  AND work.status = 'COMPLETED' AND work.accepted_result_sha256 IS NOT NULL
                ORDER BY BINARY point.test_point_key, BINARY candidate.case_key,
                         BINARY candidate.work_item_id
                """, row -> {
            V2ExportTestcaseRow persisted = new V2ExportTestcaseRow(
                    row.getString("work_item_id"), row.getString("case_key"), row.getString("name_text"),
                    row.getString("title"), row.getString("function_name"), row.getString("priority"),
                    row.getString("preconditions_json"), row.getString("hardware_configuration_json"),
                    row.getString("software_configuration_json"), row.getString("test_configuration_json"),
                    row.getString("parameter_configuration_json"), row.getString("inputs_json"),
                    row.getString("expected_results_json"), row.getString("evaluation_criteria"),
                    row.getString("result_evaluation_criteria"), row.getString("termination_conditions_json"),
                    row.getString("result_collection"), row.getString("author_name"), row.getString("author_date"),
                    row.getString("missing_information_json"), row.getString("test_point_key"),
                    row.getString("steps_json"), row.getString("requirement_summaries_json"));
            consumer.accept(exportTestcase(persisted));
            emitted[0]++;
        }, taskId);
        requireExportedRowCount("V2 formal testcase", emitted[0], expected);
        if (countV2FormalTestcases(taskId) != expected) {
            throw new IllegalStateException("V2 formal testcase rows changed during export");
        }
    }

    /**
     * Executes one MySQL export cursor without letting Connector/J pre-buffer an unbounded task result.
     *
     * <p>The special fetch size is the documented Connector/J row-streaming mode. Callers must consume the callback
     * synchronously and must not issue another statement on this connection until the callback returns.</p>
     * [Req-ID]: REQ-TGV2-009
     */
    private void streamV2ExportQuery(String sql, RowCallbackHandler handler, String taskId) {
        jdbcTemplate.query(sql, statement -> {
            statement.setFetchSize(MYSQL_STREAMING_FETCH_SIZE);
            statement.setString(1, taskId);
        }, handler);
    }

    private StructuredTestCaseRow exportTestcase(V2ExportTestcaseRow row) {
        List<StructuredTestStep> steps = readerSafeSteps(row.steps());
        List<String> expected = readerSafeNullableList(row.expectedResults());
        if (expected.isEmpty()) expected = steps.stream().map(StructuredTestStep::expected).toList();
        String title = readerSafeText(row.title());
        return new StructuredTestCaseRow(
                "v2-case:" + row.testPointKey() + ":" + row.caseKey() + ":" + row.workItemId(),
                readerSafeText(orDefault(row.name(), title)), title, readerSafeText(row.functionName()),
                priority(row.priority()), StructuredTestCaseRow.Status.FORMAL,
                readerSafeList(row.preconditions()),
                new StructuredTestCaseRow.Initialization(
                        readerSafeNullableList(row.hardwareConfiguration()),
                        readerSafeNullableList(row.softwareConfiguration()),
                        readerSafeNullableList(row.testConfiguration()),
                        readerSafeNullableList(row.parameterConfiguration())),
                readerSafeInputs(row.inputs()), steps, expected,
                readerSafeText(row.evaluationCriteria()), readerSafeText(row.resultEvaluationCriteria()),
                readerSafeNullableList(row.terminationConditions()), readerSafeText(row.resultCollection()),
                new StructuredTestCaseRow.AuthoringInformation(
                        readerSafeText(row.author()), readerSafeText(row.authorDate())),
                readerSafeNullableList(row.requirementSummaries()).stream().distinct().sorted().toList(),
                readerSafeList(row.missingInformation()), true);
    }

    private static void requireExportedRowCount(String label, long actual, long expected) {
        if (actual != expected) throw new IllegalStateException(label + " rows changed during export");
    }

    private record V2ExportTestcaseRow(
            String workItemId, String caseKey, String name, String title, String functionName, String priority,
            String preconditions, String hardwareConfiguration, String softwareConfiguration,
            String testConfiguration, String parameterConfiguration, String inputs, String expectedResults,
            String evaluationCriteria, String resultEvaluationCriteria, String terminationConditions,
            String resultCollection, String author, String authorDate, String missingInformation,
            String testPointKey, String steps, String requirementSummaries) { }

    /** Atomically publishes structured task axes and optional validated workbook metadata. */
    public void completeStructuredTask(String taskId, WorkbookArtifact artifact,
            StructuredProcessingStatus processingStatus, StructuredCoverageStatus coverageStatus) {
        completeStructuredTask(taskId, artifact, processingStatus, coverageStatus, false);
    }

    /**
     * Atomically publishes candidate-protocol delivery without conflating completed processing with coverage.
     * Historical callers keep the four-argument method and therefore retain their prior top-level semantics.
     * [Req-ID]: REQ-AFCE-006
     */
    public void completeStructuredTask(String taskId, WorkbookArtifact artifact,
            StructuredProcessingStatus processingStatus, StructuredCoverageStatus coverageStatus,
            boolean candidateProtocol) {
        if (artifact == null) throw new IllegalArgumentException("Validated structured workbook artifact is required");
        if (processingStatus != StructuredProcessingStatus.COMPLETED) {
            throw new IllegalArgumentException("Structured completion requires COMPLETED processing");
        }
        if (candidateProtocol && coverageStatus == StructuredCoverageStatus.UNABLE_TO_GENERATE) {
            throw new IllegalArgumentException("Candidate completion requires a trusted formal delivery");
        }
        GenerationTaskStatus taskStatus = candidateProtocol && coverageStatus == StructuredCoverageStatus.PARTIAL
                ? GenerationTaskStatus.PARTIAL
                : GenerationTaskStatus.COMPLETED;
        transactionTemplate.executeWithoutResult(ignored -> {
            requireTaskStatus(taskId, GenerationTaskStatus.VALIDATING);
            int changed = jdbcTemplate.update("""
                    UPDATE generation_task
                    SET status = ?, structured_processing_status = ?, structured_coverage_status = ?,
                        artifact_id = ?, artifact_sha256 = ?, artifact_path = ?
                    WHERE id = ? AND status = 'VALIDATING'
                    """, taskStatus.name(), processingStatus.name(), coverageStatus.name(), artifact.artifactId(),
                    artifact.sha256(), artifact.path().toString(), taskId);
            if (changed != 1) throw new IllegalStateException("Structured completion did not update exactly one task");
        });
    }

    /**
     * Publishes a V2 artifact while preserving the independent processing and formal-coverage axes.
     * A technically finished task is top-level COMPLETED only when formal coverage is complete; every other
     * auditable artifact is PARTIAL rather than being mislabeled as complete.
     * [Req-ID]: REQ-TGV2-007, REQ-TGV2-009
     */
    public void completeV2StructuredTask(String taskId, WorkbookArtifact artifact,
            StructuredProcessingStatus processingStatus, StructuredCoverageStatus coverageStatus) {
        Objects.requireNonNull(artifact, "Validated V2 workbook artifact is required");
        if (processingStatus != StructuredProcessingStatus.COMPLETED
                && processingStatus != StructuredProcessingStatus.FAILED) {
            throw new IllegalArgumentException("V2 artifact requires a terminal processing status");
        }
        GenerationTaskStatus taskStatus = processingStatus == StructuredProcessingStatus.COMPLETED
                && coverageStatus == StructuredCoverageStatus.COMPLETE
                ? GenerationTaskStatus.COMPLETED : GenerationTaskStatus.PARTIAL;
        transactionTemplate.executeWithoutResult(ignored -> {
            requireTaskStatus(taskId, GenerationTaskStatus.VALIDATING);
            int changed = jdbcTemplate.update("""
                    UPDATE generation_task
                    SET status = ?, structured_processing_status = ?, structured_coverage_status = ?,
                        artifact_id = ?, artifact_sha256 = ?, artifact_path = ?
                    WHERE id = ? AND status = 'VALIDATING'
                    """, taskStatus.name(), processingStatus.name(), coverageStatus.name(), artifact.artifactId(),
                    artifact.sha256(), artifact.path().toString(), taskId);
            if (changed != 1) throw new IllegalStateException("V2 completion did not update exactly one task");
        });
    }

    /** Fails the structured route without invoking Markdown finalization or retaining an artifact. */
    public void failStructuredTask(String taskId, StructuredCoverageStatus coverageStatus) {
        int changed = jdbcTemplate.update("""
                UPDATE generation_task
                SET status = 'FAILED', structured_processing_status = 'FAILED', structured_coverage_status = ?,
                    artifact_id = NULL, artifact_sha256 = NULL, artifact_path = NULL
                WHERE id = ? AND status IN ('AUDITING','GENERATING','VALIDATING')
                """, coverageStatus.name(), taskId);
        if (changed != 1) throw new IllegalStateException("Structured failure did not update exactly one task");
    }

    /**
     * Fails a structured task before a work attempt exists while retaining only an enumerated safe diagnostic.
     * This is used for task-level planning closure failures; rejected source/model text is never persisted.
     *
     * [Req-ID]: REQ-FSC-008, REQ-TGV2-012
     */
    public void failStructuredTask(String taskId, StructuredCoverageStatus coverageStatus,
            StructuredValidationFailure failure) {
        Objects.requireNonNull(failure, "failure must not be null");
        int changed = jdbcTemplate.update("""
                UPDATE generation_task
                SET status = 'FAILED', structured_processing_status = 'FAILED', structured_coverage_status = ?,
                    artifact_id = NULL, artifact_sha256 = NULL, artifact_path = NULL,
                    validation_error_code = ?, validation_error_path = ?, validation_error_message = ?
                WHERE id = ? AND status IN ('AUDITING','GENERATING','VALIDATING')
                """, coverageStatus.name(), failure.code(), failure.path(), failure.storageMessage(), taskId);
        if (changed != 1) throw new IllegalStateException("Structured planning failure did not update exactly one task");
    }

    /** Publishes user cancellation on the structured processing axis without retaining an artifact. */
    public void cancelStructuredTask(String taskId, StructuredCoverageStatus coverageStatus) {
        transactionTemplate.executeWithoutResult(ignored -> {
            List<GenerationTaskStatus> statuses = jdbcTemplate.query(
                    "SELECT status FROM generation_task WHERE id = ? FOR UPDATE",
                    (row, index) -> GenerationTaskStatus.valueOf(row.getString(1)), taskId);
            if (statuses.isEmpty()) throw new GenerationTaskNotFoundException(taskId);
            if (statuses.get(0) == GenerationTaskStatus.CANCELLED) return;
            if (statuses.get(0).isTerminal()) {
                throw new IllegalStateException("A terminal structured task cannot be cancelled");
            }
            int changed = jdbcTemplate.update("""
                    UPDATE generation_task
                    SET status = 'CANCELLED', structured_processing_status = 'CANCELLED',
                        structured_coverage_status = ?, artifact_id = NULL, artifact_sha256 = NULL, artifact_path = NULL
                    WHERE id = ? AND status IN ('AUDITING','GENERATING','VALIDATING')
                    """, coverageStatus.name(), taskId);
            if (changed != 1) throw new IllegalStateException("Structured cancellation did not update exactly one task");
        });
    }

    /**
     * Projects only committed structured rows. Internal keys remain inside the join predicates and never cross the
     * browser boundary; a task without structured state retains its legacy detail response.
     *
     * [Req-ID]: REQ-STG-006
     */
    StructuredGenerationTaskDetail structuredResult(
            String taskId, String processingValue, String coverageValue, IntSupplier materialDocumentTotal) {
        return structuredResult(taskId, processingValue, coverageValue, materialDocumentTotal,
                StructuredDetailQuery.defaults());
    }

    private StructuredGenerationTaskDetail structuredResult(
            String taskId, String processingValue, String coverageValue, IntSupplier materialDocumentTotal,
            StructuredDetailQuery query) {
        if (processingValue == null || coverageValue == null) return null;
        String workflowVersion = taskWorkflowVersion(taskId);
        boolean v2 = GenerationContractVersions.V2.equals(workflowVersion);
        boolean terminalProjection = terminalStructuredProjection(processingValue);
        String processing = structuredProcessingDisplay(processingValue);
        String coverage = structuredCoverageDisplay(coverageValue);
        return new StructuredGenerationTaskDetail(workflowVersion, processing, coverage,
                v2 && StructuredProcessingStatus.FAILED.name().equals(processingValue)
                        ? structuredValidationFailure(taskId) : null,
                structuredRetryEligibility(taskId),
                v2 && !terminalProjection ? 0 : pendingCandidateCaseCount(taskId),
                v2 ? null : structuredFunctionCandidateSummary(taskId),
                structuredPhaseProgress(taskId, materialDocumentTotal.getAsInt(), v2),
                v2 ? List.of() : structuredReviewFindings(taskId),
                List.of(),
                v2 ? List.of() : structuredReconciliations(taskId),
                v2 ? List.of() : structuredTestPoints(taskId, false),
                v2 ? v2Collections(taskId, processingValue, query) : null);
    }

    private StructuredGenerationTaskDetail.V2Collections v2Collections(
            String taskId, String processingValue, StructuredDetailQuery query) {
        if (terminalStructuredProjection(processingValue)) {
            V2TestPointPage pointPage = structuredTestPointsPageV2(taskId, query);
            return new StructuredGenerationTaskDetail.V2Collections(
                    structuredTestabilityFeedbackPage(taskId, query),
                    pointPage.page(),
                    structuredV2TestcasesPage(taskId, pointPage.workItemId(), query));
        }
        // Cross-request offsets cannot describe a moving set. Keep progress visible, but publish page contents
        // only from a terminal task snapshot. [Req-ID]: REQ-TGV2-009
        return new StructuredGenerationTaskDetail.V2Collections(
                new StructuredGenerationTaskDetail.DetailPage<>(
                        List.of(), query.feedbackPage(), query.size(), 0, false),
                new StructuredGenerationTaskDetail.DetailPage<>(
                        List.of(), query.testPointPage(), query.testPointSize(), 0, false),
                new StructuredGenerationTaskDetail.DetailPage<>(
                        List.of(), query.testcasePage(), query.testcaseSize(), 0, false));
    }

    private static boolean terminalStructuredProjection(String processingValue) {
        return StructuredProcessingStatus.COMPLETED.name().equals(processingValue)
                || StructuredProcessingStatus.FAILED.name().equals(processingValue)
                || StructuredProcessingStatus.CANCELLED.name().equals(processingValue);
    }

    private String taskWorkflowVersion(String taskId) {
        String version = jdbcTemplate.queryForObject(
                "SELECT workflow_version FROM generation_task WHERE id = ?", String.class, taskId);
        return version == null ? GenerationContractVersions.V1 : version;
    }

    /** Returns whether a user mutation may target the current V2 workflow. Historical V1 rows remain read-only. */
    public boolean isV2Task(String taskId) {
        return GenerationContractVersions.V2.equals(taskWorkflowVersion(taskId));
    }

    /**
     * Reads the strict stored diagnostic form and projects only its fixed reader-safe fields.
     * [Req-ID]: REQ-FSC-007, REQ-TGV2-012
     */
    private StructuredGenerationTaskDetail.ValidationFailure structuredValidationFailure(String taskId) {
        List<StructuredGenerationTaskDetail.ValidationFailure> rows = jdbcTemplate.query("""
                        SELECT validation_error_code, validation_error_path, validation_error_message
                        FROM generation_task WHERE id = ?
                        """, (row, ignored) -> {
                    String code = row.getString("validation_error_code");
                    String path = row.getString("validation_error_path");
                    String message = row.getString("validation_error_message");
                    if (code == null && path == null && message == null) return null;
                    if (code == null || path == null || message == null) {
                        throw new IllegalStateException("Structured validation diagnostic is incomplete");
                    }
                    StructuredValidationFailure safe;
                    try {
                        safe = StructuredValidationFailure.fromStored(
                                StructuredValidationFailure.Code.valueOf(code), path, message);
                    } catch (IllegalArgumentException exception) {
                        // Stored diagnostic text is untrusted at this boundary and must not survive in a loggable cause.
                        throw new IllegalStateException("Structured validation diagnostic is not recognized");
                    }
                    return new StructuredGenerationTaskDetail.ValidationFailure(safe.code(), safe.path(), safe.message());
                }, taskId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private StructuredGenerationTaskDetail.PhaseProgress structuredPhaseProgress(
            String taskId, int materialDocumentTotal, boolean v2) {
        Integer completedMaterials = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM material_inventory_document
                WHERE task_id = ? AND complete = TRUE
                """, Integer.class, taskId);
        return new StructuredGenerationTaskDetail.PhaseProgress(
                new StructuredGenerationTaskDetail.PhaseCount(
                        materialDocumentTotal, completedMaterials == null ? 0 : completedMaterials, 0),
                v2 ? structuredWorkPhase(taskId, "operation_name = 'REQUIREMENT_FACT_EXTRACTION_V2'") : emptyPhase(),
                v2 ? emptyPhase() : structuredWorkPhase(taskId, "operation_name = 'REQUIREMENT_MATERIAL_REVIEW'"),
                v2 ? emptyPhase() : structuredWorkPhase(taskId,
                        "operation_name IN ('FEATURE_SCOPE_EXTRACT','FEATURE_SCOPE_RECONCILIATION')"),
                structuredWorkPhase(taskId, v2
                        ? "operation_name = 'FUNCTIONAL_TESTCASE_DESIGN_V2'"
                        : "operation_name = 'FUNCTIONAL_TESTCASE_DESIGN'"));
    }

    private static StructuredGenerationTaskDetail.PhaseCount emptyPhase() {
        return new StructuredGenerationTaskDetail.PhaseCount(0, 0, 0);
    }

    /** Projects only committed V2 feedback; quotes and stable identities remain server-side. */
    private List<StructuredGenerationTaskDetail.TestabilityFeedback> structuredTestabilityFeedback(String taskId) {
        return jdbcTemplate.query("""
                SELECT approved.name_text, feedback.observation_type, feedback.description_text,
                       feedback.affected_fact_types_json
                FROM v2_testability_feedback feedback
                JOIN v2_approved_function approved
                  ON approved.task_id = feedback.task_id AND approved.function_key = feedback.function_key
                JOIN structured_generation_work_item work ON work.id = feedback.work_item_id
                WHERE feedback.task_id = ? AND work.status = 'COMPLETED'
                  AND work.accepted_result_sha256 IS NOT NULL
                ORDER BY approved.stable_sequence, feedback.feedback_key
                """, (row, ignored) -> new StructuredGenerationTaskDetail.TestabilityFeedback(
                readerSafeText(row.getString("name_text")),
                feedbackTypeDisplay(row.getString("observation_type")),
                readerSafeText(row.getString("description_text")),
                readerSafeList(row.getString("affected_fact_types_json")).stream()
                        .map(GenerationTaskRepository::factTypeDisplay).toList()), taskId);
    }

    /** Reads one committed V2 feedback page without materializing the task-owned feedback set. */
    private StructuredGenerationTaskDetail.DetailPage<StructuredGenerationTaskDetail.TestabilityFeedback>
            structuredTestabilityFeedbackPage(String taskId, StructuredDetailQuery query) {
        Long total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM v2_testability_feedback feedback
                JOIN structured_generation_work_item work ON work.id = feedback.work_item_id
                WHERE feedback.task_id = ? AND work.status = 'COMPLETED'
                  AND work.accepted_result_sha256 IS NOT NULL
                """, Long.class, taskId);
        List<StructuredGenerationTaskDetail.TestabilityFeedback> items = jdbcTemplate.query("""
                SELECT approved.name_text, feedback.observation_type, feedback.description_text,
                       feedback.affected_fact_types_json
                FROM v2_testability_feedback feedback
                JOIN v2_approved_function approved
                  ON approved.task_id = feedback.task_id AND approved.function_key = feedback.function_key
                JOIN structured_generation_work_item work ON work.id = feedback.work_item_id
                WHERE feedback.task_id = ? AND work.status = 'COMPLETED'
                  AND work.accepted_result_sha256 IS NOT NULL
                ORDER BY approved.stable_sequence, feedback.feedback_key
                LIMIT ? OFFSET ?
                """, (row, ignored) -> new StructuredGenerationTaskDetail.TestabilityFeedback(
                readerSafeText(row.getString("name_text")),
                feedbackTypeDisplay(row.getString("observation_type")),
                readerSafeText(row.getString("description_text")),
                readerSafeList(row.getString("affected_fact_types_json")).stream()
                        .map(GenerationTaskRepository::factTypeDisplay).toList()),
                taskId, query.size(), query.feedbackOffset());
        long totalItems = total == null ? 0 : total;
        return new StructuredGenerationTaskDetail.DetailPage<>(items, query.feedbackPage(), query.size(), totalItems,
                query.feedbackOffset() + items.size() < totalItems);
    }

    /** Builds the V2 first-sheet projection from the same committed feedback rows used by task detail. */
    private List<StructuredReviewRow> v2TestabilityFeedbackRows(String taskId) {
        return jdbcTemplate.query("""
                SELECT feedback.feedback_key, approved.name_text, feedback.observation_type,
                       feedback.description_text, feedback.affected_fact_types_json
                FROM v2_testability_feedback feedback
                JOIN v2_approved_function approved
                  ON approved.task_id = feedback.task_id AND approved.function_key = feedback.function_key
                JOIN structured_generation_work_item work ON work.id = feedback.work_item_id
                WHERE feedback.task_id = ? AND work.status = 'COMPLETED'
                  AND work.accepted_result_sha256 IS NOT NULL
                ORDER BY approved.stable_sequence, feedback.feedback_key
                """, (row, index) -> {
            List<String> factTypes = readerSafeList(row.getString("affected_fact_types_json")).stream()
                    .map(GenerationTaskRepository::factTypeDisplay).toList();
            return new StructuredReviewRow("v2-feedback:" + row.getString("feedback_key"), index + 1,
                    StructuredReviewRow.Source.TESTABILITY_FEEDBACK,
                    readerSafeText(row.getString("name_text")),
                    feedbackTypeDisplay(row.getString("observation_type")),
                    factTypes.isEmpty() ? "" : String.join("、", factTypes),
                    readerSafeText(row.getString("description_text")), "", "", "", "", "", "",
                    "正式需求材料", true);
        }, taskId);
    }

    /**
     * Keeps every saved non-formal outcome visible on the first sheet without promoting it to formal coverage.
     * Pending details come from the committed candidate rows because a mixed generated outcome has no top-level
     * missing-information list. [Req-ID]: REQ-TGV2-009, REQ-TGV2-010
     */
    private List<StructuredReviewRow> v2GenerationOutcomeRows(String taskId, int sequenceOffset) {
        Map<String, List<String>> pendingMissingByPoint = new LinkedHashMap<>();
        List<PendingOutcomeMissing> pendingMissingRows = jdbcTemplate.query("""
                SELECT candidate.work_item_id, point.test_point_key, candidate.missing_information_json
                FROM structured_test_case candidate
                JOIN structured_test_point point
                  ON point.task_id = candidate.task_id AND point.work_item_id = candidate.work_item_id
                JOIN structured_generation_work_item work ON work.id = candidate.work_item_id
                WHERE candidate.task_id = ? AND candidate.case_status = 'PENDING_CONFIRMATION'
                  AND work.status = 'COMPLETED' AND work.accepted_result_sha256 IS NOT NULL
                ORDER BY work.created_at, point.test_point_key, candidate.case_key
                """, (row, ignored) -> new PendingOutcomeMissing(
                        outcomePointKey(row.getString("work_item_id"), row.getString("test_point_key")),
                        readerSafeList(row.getString("missing_information_json"))), taskId);
        pendingMissingRows.forEach(row -> pendingMissingByPoint
                .computeIfAbsent(row.pointKey(), ignored -> new ArrayList<>()).addAll(row.missingInformation()));
        return jdbcTemplate.query("""
                SELECT outcome.work_item_id, outcome.test_point_key, outcome.generation_outcome,
                       outcome.missing_information_json,
                       point.function_name
                FROM v2_generation_outcome outcome
                JOIN structured_test_point point
                  ON point.task_id = outcome.task_id AND point.work_item_id = outcome.work_item_id
                 AND point.test_point_key = outcome.test_point_key
                JOIN structured_generation_work_item work ON work.id = outcome.work_item_id
                WHERE outcome.task_id = ? AND work.status = 'COMPLETED'
                  AND work.accepted_result_sha256 IS NOT NULL
                  AND (outcome.generation_outcome IN ('pending_only', 'unable_to_generate') OR EXISTS (
                      SELECT 1 FROM structured_test_case candidate
                      WHERE candidate.task_id = outcome.task_id
                        AND candidate.work_item_id = outcome.work_item_id
                        AND candidate.case_status = 'PENDING_CONFIRMATION'))
                ORDER BY work.created_at, outcome.test_point_key
                """, (row, index) -> {
            String outcome = row.getString("generation_outcome");
            LinkedHashSet<String> missingItems = new LinkedHashSet<>(
                    readerSafeList(row.getString("missing_information_json")));
            missingItems.addAll(pendingMissingByPoint.getOrDefault(
                    outcomePointKey(row.getString("work_item_id"), row.getString("test_point_key")), List.of()));
            List<String> missing = List.copyOf(missingItems);
            if (missing.isEmpty()) {
                throw new IllegalStateException("Committed non-formal V2 outcome must retain missing information");
            }
            String classification = switch (outcome) {
                case "generated" -> "含待确认用例";
                case "pending_only" -> "仅生成待确认用例";
                case "unable_to_generate" -> "无法生成用例";
                default -> throw new IllegalStateException("Unknown committed V2 generation outcome");
            };
            return new StructuredReviewRow("v2-outcome:" + row.getString("test_point_key"),
                    sequenceOffset + index + 1, StructuredReviewRow.Source.GENERATION_OUTCOME,
                    readerSafeText(row.getString("function_name")), classification, "测试用例生成",
                    String.join("；", missing), "", "", "未计入正式覆盖", "补充缺失信息后重新生成", "",
                    "信息待补充", "已保存的生成结果", true);
        }, taskId);
    }

    private static String outcomePointKey(String workItemId, String testPointKey) {
        return workItemId + '\0' + testPointKey;
    }

    private record PendingOutcomeMissing(String pointKey, List<String> missingInformation) {}

    private StructuredGenerationTaskDetail.PhaseCount structuredWorkPhase(String taskId, String operationPredicate) {
        return jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) AS total,
                               COALESCE(SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END), 0) AS completed,
                               COALESCE(SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END), 0) AS failed
                        FROM structured_generation_work_item
                        WHERE task_id = ? AND status NOT IN ('SPLIT', 'SUPERSEDED') AND %s
                        """.formatted(operationPredicate), (row, ignored) ->
                        new StructuredGenerationTaskDetail.PhaseCount(
                                row.getInt("total"), row.getInt("completed"), row.getInt("failed")), taskId);
    }

    private int pendingCandidateCaseCount(String taskId) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM structured_test_case c
                        JOIN structured_generation_work_item w ON w.id = c.work_item_id
                        WHERE w.task_id = ? AND w.status = 'COMPLETED' AND w.accepted_result_sha256 IS NOT NULL
                          AND c.case_status = 'PENDING_CONFIRMATION'
                        """, Integer.class, taskId);
        return count == null ? 0 : count;
    }

    /**
     * Reads candidate counts and gaps from the committed V19 tables only. Internal keys are retained solely in the
     * private projection identity used to de-duplicate workbook rows; browser fields are fixed Chinese business text.
     * [Req-ID]: REQ-AFCE-005, REQ-AFCE-006, REQ-AFCE-008
     */
    private StructuredGenerationTaskDetail.FunctionCandidateSummary structuredFunctionCandidateSummary(String taskId) {
        Map<String, Object> candidateCounts = jdbcTemplate.queryForMap("""
                SELECT COALESCE(SUM(candidate.java_final_decision = 'ACCEPTED'), 0) AS accepted_count,
                       COALESCE(SUM(candidate.java_final_decision = 'PENDING_CONFIRMATION'), 0) AS pending_count,
                       COALESCE(SUM(candidate.java_final_decision = 'REJECTED'), 0) AS rejected_count
                FROM structured_function_candidate candidate
                JOIN structured_generation_work_item work ON work.id = candidate.work_item_id
                WHERE candidate.task_id = ? AND work.status = 'COMPLETED'
                  AND work.accepted_result_sha256 IS NOT NULL
                """, taskId);
        Map<String, Object> sourceCounts = jdbcTemplate.queryForMap("""
                SELECT COALESCE(SUM(outcome.kee_disposition = 'NO_FUNCTION'), 0) AS no_function_count,
                       COALESCE(SUM(outcome.kee_disposition = 'UNRESOLVED'), 0) AS unresolved_count
                FROM structured_function_source_outcome outcome
                JOIN structured_generation_work_item work ON work.id = outcome.work_item_id
                WHERE outcome.task_id = ? AND work.status = 'COMPLETED'
                  AND work.accepted_result_sha256 IS NOT NULL
                """, taskId);
        int incompleteWindows = countValue(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM structured_generation_work_item
                WHERE task_id = ? AND operation_name = 'FEATURE_SCOPE_EXTRACT' AND status = 'FAILED'
                """, Integer.class, taskId));
        List<CandidateAuditProjection> audit = functionCandidateAudit(taskId);
        return new StructuredGenerationTaskDetail.FunctionCandidateSummary(
                countValue(candidateCounts.get("accepted_count")), countValue(candidateCounts.get("pending_count")),
                countValue(candidateCounts.get("rejected_count")), countValue(sourceCounts.get("no_function_count")),
                countValue(sourceCounts.get("unresolved_count")), incompleteWindows,
                audit.stream().map(row -> new StructuredGenerationTaskDetail.FunctionCandidateIssue(
                        row.subject(), row.status(), row.summary(), row.missingInformation())).toList());
    }

    private List<CandidateAuditProjection> functionCandidateAudit(String taskId) {
        List<CandidateAuditProjection> rows = new ArrayList<>();
        rows.addAll(jdbcTemplate.query("""
                SELECT c.work_item_id, c.candidate_ref, c.path_text, c.description,
                       c.java_final_decision, c.missing_information_json, w.source_label
                FROM structured_function_candidate c
                JOIN structured_generation_work_item w ON w.id = c.work_item_id
                WHERE c.task_id = ? AND c.java_final_decision <> 'ACCEPTED'
                  AND w.status = 'COMPLETED' AND w.accepted_result_sha256 IS NOT NULL
                ORDER BY w.created_at, c.candidate_ref
                """, (result, ignored) -> {
            String decision = result.getString("java_final_decision");
            List<String> missing = readerSafeList(result.getString("missing_information_json"));
            String status = switch (decision) {
                case "PENDING_CONFIRMATION" -> "待确认功能候选";
                case "REJECTED" -> "已拒绝功能候选";
                default -> throw new IllegalStateException("Candidate decision is not reader-projectable");
            };
            String description = readerSafeText(result.getString("description"));
            String summary = missing.isEmpty() ? description
                    : description + "；缺失信息：" + String.join("；", missing);
            return new CandidateAuditProjection(
                    result.getString("work_item_id") + ":candidate:" + result.getString("candidate_ref"),
                    readerSafeText(result.getString("path_text")), status,
                    readerSafeText(orDefault(result.getString("source_label"), "功能清单")), summary,
                    "PENDING_CONFIRMATION".equals(decision) ? "待确认" : "未纳入", missing);
        }, taskId));
        rows.addAll(jdbcTemplate.query("""
                SELECT o.work_item_id, o.unit_key, w.source_label
                FROM structured_function_source_outcome o
                JOIN structured_generation_work_item w ON w.id = o.work_item_id
                WHERE o.task_id = ? AND o.kee_disposition = 'UNRESOLVED'
                  AND w.status = 'COMPLETED' AND w.accepted_result_sha256 IS NOT NULL
                ORDER BY w.created_at, o.source_ordinal
                """, (result, ignored) -> new CandidateAuditProjection(
                result.getString("work_item_id") + ":source:" + result.getString("unit_key"),
                readerSafeText(orDefault(result.getString("source_label"), "功能清单原文")),
                "原文功能归属无法确定", "功能清单原文",
                "该原文尚未形成可信功能候选，未计入正式覆盖。", "待确认", List.of()), taskId));
        rows.addAll(jdbcTemplate.query("""
                SELECT id, source_label FROM structured_generation_work_item
                WHERE task_id = ? AND operation_name = 'FEATURE_SCOPE_EXTRACT' AND status = 'FAILED'
                ORDER BY created_at, id
                """, (result, ignored) -> new CandidateAuditProjection(
                result.getString("id") + ":failed-window",
                readerSafeText(orDefault(result.getString("source_label"), "功能清单")),
                "功能候选提取未完成", "功能清单范围",
                "该材料范围未完成候选审查，未计入正式覆盖。", "阻断", List.of()), taskId));
        return List.copyOf(rows);
    }

    private static int countValue(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    private List<StructuredGenerationTaskDetail.ReviewFinding> structuredReviewFindings(String taskId) {
        return jdbcTemplate.query("""
                        SELECT w.source_label, f.issue_type, f.description, f.handling_level,
                               f.affected_scope_summary, f.bad_source_quote, f.proposed_good_text, f.test_design_impact,
                               f.current_project_recommendation, f.design_center_guideline_recommendation
                        FROM structured_review_finding f
                        JOIN structured_generation_work_item w ON w.id = f.work_item_id
                        WHERE w.task_id = ? AND w.status = 'COMPLETED' AND w.accepted_result_sha256 IS NOT NULL
                        ORDER BY w.created_at, f.finding_key
                        """, (row, ignored) -> new StructuredGenerationTaskDetail.ReviewFinding(
                 readerSafeText(orDefault(row.getString("source_label"), "需求材料")), "需求材料审查",
                 readerSafeText(row.getString("issue_type")), readerSafeText(row.getString("description")),
                 handlingDisplay(row.getString("handling_level")), readerSafeText(row.getString("affected_scope_summary")),
                 readerSafeText(row.getString("bad_source_quote")), readerSafeText(row.getString("proposed_good_text")),
                 readerSafeText(row.getString("test_design_impact")),
                 readerSafeText(row.getString("current_project_recommendation")),
                 readerSafeText(row.getString("design_center_guideline_recommendation"))), taskId);
    }

    private List<StructuredGenerationTaskDetail.Reconciliation> structuredReconciliations(String taskId) {
        return jdbcTemplate.query("""
                        SELECT r.work_item_id, r.reconciliation_key, r.classification, r.scope_recommendation,
                               r.confirmation_status
                        FROM structured_feature_reconciliation r
                        JOIN structured_generation_work_item w ON w.id = r.work_item_id
                        WHERE w.task_id = ? AND w.status = 'COMPLETED' AND w.accepted_result_sha256 IS NOT NULL
                        ORDER BY w.created_at, r.reconciliation_key
                        """, (row, ignored) -> new StructuredGenerationTaskDetail.Reconciliation(
                reconciliationFunctionListPaths(taskId, row.getString("work_item_id"), row.getString("reconciliation_key")),
                reconciliationRequirementFunctions(taskId, row.getString("work_item_id"), row.getString("reconciliation_key")),
                reconciliationDisplay(row.getString("classification")), readerSafeText(row.getString("scope_recommendation")),
                confirmationDisplay(row.getString("confirmation_status"))), taskId);
    }

    private List<String> reconciliationFunctionListPaths(String taskId, String workItemId, String reconciliationKey) {
        return jdbcTemplate.query("""
                        SELECT DISTINCT item.path_text
                        FROM structured_reference_binding b
                        JOIN structured_function_list_item item ON item.item_key = b.reference_key
                        JOIN structured_generation_work_item source ON source.id = item.work_item_id
                        WHERE b.work_item_id = ? AND b.subject_key = ? AND b.subject_type = 'RECONCILIATION'
                          AND b.reference_type = 'FUNCTION_LIST_ITEM' AND source.status = 'COMPLETED'
                          AND source.accepted_result_sha256 IS NOT NULL AND source.task_id = ?
                        ORDER BY item.path_text
                        """, (row, ignored) -> readerSafeText(row.getString(1)), workItemId, reconciliationKey, taskId);
    }

    private List<String> reconciliationRequirementFunctions(String taskId, String workItemId, String reconciliationKey) {
        return jdbcTemplate.query("""
                        SELECT DISTINCT fact.function_name
                        FROM structured_reference_binding b
                        JOIN structured_requirement_fact fact ON fact.fact_key = b.reference_key
                        JOIN structured_generation_work_item source ON source.id = fact.work_item_id
                        WHERE b.work_item_id = ? AND b.subject_key = ? AND b.subject_type = 'RECONCILIATION'
                          AND b.reference_type = 'REQUIREMENT_FACT' AND source.status = 'COMPLETED'
                          AND source.accepted_result_sha256 IS NOT NULL AND source.task_id = ?
                        ORDER BY fact.function_name
                        """, (row, ignored) -> readerSafeText(row.getString(1)), workItemId, reconciliationKey, taskId);
    }

    private List<StructuredGenerationTaskDetail.TestPoint> structuredTestPoints(String taskId, boolean v2) {
        return jdbcTemplate.query("""
                        SELECT point.work_item_id, point.function_name, point.test_point_type, point.basis, point.description,
                               point.missing_information_json, point.formal_coverage_satisfied,
                               outcome.generation_outcome, outcome.missing_information_json AS outcome_missing_information
                        FROM structured_test_point point
                        JOIN structured_generation_work_item w ON w.id = point.work_item_id
                        LEFT JOIN v2_generation_outcome outcome
                          ON outcome.work_item_id = point.work_item_id
                         AND outcome.task_id = point.task_id
                         AND outcome.test_point_key = point.test_point_key
                        WHERE w.task_id = ? AND w.status = 'COMPLETED' AND w.accepted_result_sha256 IS NOT NULL
                        ORDER BY w.created_at, point.test_point_key
                        """, (row, ignored) -> {
            String workItemId = row.getString("work_item_id");
            return new StructuredGenerationTaskDetail.TestPoint(
                    readerSafeText(row.getString("function_name")),
                    v2 ? testPointTypeDisplay(row.getString("test_point_type"))
                            : readerSafeText(row.getString("test_point_type")),
                    readerSafeText(row.getString("description")),
                    basisDisplay(row.getString("basis")),
                    generationOutcomeDisplay(row.getString("generation_outcome")),
                    readerSafeNullableList(row.getString("outcome_missing_information")),
                    readerSafeList(row.getString("missing_information_json")), row.getBoolean("formal_coverage_satisfied"),
                    structuredTestcases(taskId, workItemId));
        }, taskId);
    }

    /** Loads one V2 test-point metadata page; its independently paged testcase is assembled separately. */
    private V2TestPointPage
            structuredTestPointsPageV2(String taskId, StructuredDetailQuery query) {
        Long total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM structured_test_point point
                JOIN structured_generation_work_item work ON work.id = point.work_item_id
                JOIN v2_generation_outcome outcome
                  ON outcome.work_item_id = point.work_item_id
                 AND outcome.task_id = point.task_id
                 AND outcome.test_point_key = point.test_point_key
                WHERE point.task_id = ? AND work.status = 'COMPLETED'
                  AND work.accepted_result_sha256 IS NOT NULL
                """, Long.class, taskId);
        List<V2TestPointProjectionRow> rows = jdbcTemplate.query("""
                SELECT point.work_item_id, point.test_point_key, point.function_name, point.test_point_type,
                       point.basis, point.description, point.missing_information_json,
                       point.formal_coverage_satisfied, outcome.generation_outcome,
                       outcome.missing_information_json AS outcome_missing_information
                FROM structured_test_point point
                JOIN structured_generation_work_item work ON work.id = point.work_item_id
                JOIN v2_generation_outcome outcome
                  ON outcome.work_item_id = point.work_item_id
                 AND outcome.task_id = point.task_id
                 AND outcome.test_point_key = point.test_point_key
                WHERE point.task_id = ? AND work.status = 'COMPLETED'
                  AND work.accepted_result_sha256 IS NOT NULL
                ORDER BY point.test_point_key
                LIMIT ? OFFSET ?
                """, (row, ignored) -> new V2TestPointProjectionRow(
                row.getString("work_item_id"), row.getString("test_point_key"), row.getString("function_name"),
                row.getString("test_point_type"), row.getString("basis"), row.getString("description"),
                row.getString("missing_information_json"), row.getBoolean("formal_coverage_satisfied"),
                row.getString("generation_outcome"), row.getString("outcome_missing_information")),
                 taskId, query.testPointSize(), query.testPointOffset());
        List<StructuredGenerationTaskDetail.TestPoint> items = rows.stream().map(row ->
                new StructuredGenerationTaskDetail.TestPoint(
                        readerSafeText(row.functionName()), testPointTypeDisplay(row.testPointType()),
                        readerSafeText(row.description()), basisDisplay(row.basis()),
                        generationOutcomeDisplay(row.generationOutcome()),
                         readerSafeNullableList(row.outcomeMissingInformation()),
                         readerSafeList(row.missingInformation()), row.formalCoverageSatisfied(),
                         List.of())).toList();
        long totalItems = total == null ? 0 : total;
        var page = new StructuredGenerationTaskDetail.DetailPage<>(items, query.testPointPage(), query.testPointSize(),
                totalItems, query.testPointOffset() + items.size() < totalItems);
        return new V2TestPointPage(page, rows.isEmpty() ? null : rows.get(0).workItemId());
    }

    private StructuredGenerationTaskDetail.DetailPage<StructuredGenerationTaskDetail.Testcase>
            structuredV2TestcasesPage(String taskId, String workItemId, StructuredDetailQuery query) {
        if (workItemId == null) {
            return new StructuredGenerationTaskDetail.DetailPage<>(
                    List.of(), query.testcasePage(), query.testcaseSize(), 0, false);
        }
        Long total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM structured_test_case candidate
                JOIN structured_generation_work_item work ON work.id = candidate.work_item_id
                WHERE candidate.task_id = ? AND candidate.work_item_id = ?
                  AND work.status = 'COMPLETED' AND work.accepted_result_sha256 IS NOT NULL
                """, Long.class, taskId, workItemId);
        List<V2TestcaseProjectionRow> cases = jdbcTemplate.query("""
                SELECT work_item_id, case_key, name_text, title, priority, case_status, preconditions_json,
                       hardware_configuration_json, software_configuration_json, test_configuration_json,
                       parameter_configuration_json, inputs_json, expected_results_json, evaluation_criteria,
                       result_evaluation_criteria, termination_conditions_json, result_collection,
                       author_name, author_date, missing_information_json
                FROM structured_test_case
                WHERE task_id = ? AND work_item_id = ?
                ORDER BY case_key
                LIMIT ? OFFSET ?
                """, (row, ignored) -> new V2TestcaseProjectionRow(
                row.getString("work_item_id"), row.getString("case_key"), row.getString("name_text"),
                row.getString("title"), row.getString("priority"), row.getString("case_status"),
                row.getString("preconditions_json"), row.getString("hardware_configuration_json"),
                row.getString("software_configuration_json"), row.getString("test_configuration_json"),
                row.getString("parameter_configuration_json"), row.getString("inputs_json"),
                row.getString("expected_results_json"), row.getString("evaluation_criteria"),
                row.getString("result_evaluation_criteria"), row.getString("termination_conditions_json"),
                row.getString("result_collection"), row.getString("author_name"), row.getString("author_date"),
                row.getString("missing_information_json")), taskId, workItemId,
                query.testcaseSize(), query.testcaseOffset());
        if (cases.isEmpty()) {
            long totalItems = total == null ? 0 : total;
            return new StructuredGenerationTaskDetail.DetailPage<>(
                    List.of(), query.testcasePage(), query.testcaseSize(), totalItems, false);
        }

        V2TestcaseProjectionRow selected = cases.get(0);
        Map<StructuredCaseIdentity, List<StructuredGenerationTaskDetail.Step>> steps = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT work_item_id, case_key, step_no, action_text, expected_text, evaluation_criteria,
                       termination_or_error, result_collection
                FROM structured_test_case_step
                WHERE work_item_id = ? AND case_key = ?
                ORDER BY work_item_id, case_key, step_no
                """, row -> {
            StructuredCaseIdentity identity = new StructuredCaseIdentity(
                    row.getString("work_item_id"), row.getString("case_key"));
            steps.computeIfAbsent(identity, ignored -> new ArrayList<>()).add(
                    new StructuredGenerationTaskDetail.Step(row.getInt("step_no"),
                            readerSafeText(row.getString("action_text")), readerSafeText(row.getString("expected_text")),
                            readerSafeText(row.getString("evaluation_criteria")),
                            readerSafeText(row.getString("termination_or_error")),
                            readerSafeText(row.getString("result_collection"))));
        }, selected.workItemId(), selected.caseKey());

        Map<StructuredCaseIdentity, LinkedHashSet<String>> summaries = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT binding.work_item_id, binding.subject_key, fact.statement_text
                FROM structured_reference_binding binding
                JOIN v2_requirement_fact fact
                  ON fact.task_id = ? AND fact.fact_key = binding.reference_key
                JOIN structured_generation_work_item source ON source.id = fact.first_work_item_id
                WHERE binding.work_item_id = ? AND binding.subject_key = ?
                  AND binding.subject_type = 'TEST_CASE' AND binding.reference_type = 'REQUIREMENT_FACT'
                  AND source.task_id = ? AND source.status = 'COMPLETED'
                  AND source.accepted_result_sha256 IS NOT NULL
                ORDER BY binding.work_item_id, binding.subject_key, fact.statement_text
                """, row -> {
            StructuredCaseIdentity identity = new StructuredCaseIdentity(
                    row.getString("work_item_id"), row.getString("subject_key"));
            summaries.computeIfAbsent(identity, ignored -> new LinkedHashSet<>())
                    .add(readerSafeText(row.getString("statement_text")));
        }, taskId, selected.workItemId(), selected.caseKey(), taskId);

        List<StructuredGenerationTaskDetail.Testcase> items = new ArrayList<>();
        for (V2TestcaseProjectionRow row : cases) {
            StructuredCaseIdentity identity = new StructuredCaseIdentity(row.workItemId(), row.caseKey());
            List<StructuredGenerationTaskDetail.Step> caseSteps = List.copyOf(
                    steps.getOrDefault(identity, List.of()));
            List<String> expectedResults = readerSafeNullableList(row.expectedResults());
            if (expectedResults.isEmpty()) {
                expectedResults = caseSteps.stream().map(StructuredGenerationTaskDetail.Step::expected).toList();
            }
            String title = readerSafeText(row.title());
            StructuredGenerationTaskDetail.Testcase testcase = new StructuredGenerationTaskDetail.Testcase(
                    readerSafeText(orDefault(row.name(), title)), title, priority(row.priority()).display(),
                    caseStatusDisplay(row.caseStatus()), readerSafeList(row.preconditions()),
                    new StructuredGenerationTaskDetail.Initialization(
                            readerSafeNullableList(row.hardwareConfiguration()),
                            readerSafeNullableList(row.softwareConfiguration()),
                            readerSafeNullableList(row.testConfiguration()),
                            readerSafeNullableList(row.parameterConfiguration())),
                    readerSafeDetailInputs(row.inputs()), caseSteps, expectedResults,
                    readerSafeText(row.evaluationCriteria()), readerSafeText(row.resultEvaluationCriteria()),
                    readerSafeNullableList(row.terminationConditions()), readerSafeText(row.resultCollection()),
                    new StructuredGenerationTaskDetail.AuthoringInformation(
                            readerSafeText(row.author()), readerSafeText(row.authorDate())),
                    summaries.getOrDefault(identity, new LinkedHashSet<>()).stream().sorted().toList(),
                    readerSafeList(row.missingInformation()));
            items.add(testcase);
        }
        long totalItems = total == null ? 0 : total;
        return new StructuredGenerationTaskDetail.DetailPage<>(items, query.testcasePage(), query.testcaseSize(),
                totalItems, query.testcaseOffset() + items.size() < totalItems);
    }

    private record V2TestPointProjectionRow(
            String workItemId, String testPointKey, String functionName, String testPointType, String basis,
            String description, String missingInformation, boolean formalCoverageSatisfied,
            String generationOutcome, String outcomeMissingInformation) { }

    private record V2TestPointPage(
            StructuredGenerationTaskDetail.DetailPage<StructuredGenerationTaskDetail.TestPoint> page,
            String workItemId) { }

    private record V2TestcaseProjectionRow(
            String workItemId, String caseKey, String name, String title, String priority, String caseStatus,
            String preconditions, String hardwareConfiguration, String softwareConfiguration,
            String testConfiguration, String parameterConfiguration, String inputs, String expectedResults,
            String evaluationCriteria, String resultEvaluationCriteria, String terminationConditions,
            String resultCollection, String author, String authorDate, String missingInformation) { }

    private record StructuredCaseIdentity(String workItemId, String caseKey) { }

    private List<StructuredGenerationTaskDetail.Testcase> structuredTestcases(String taskId, String workItemId) {
        return jdbcTemplate.query("""
                        SELECT case_key, name_text, title, priority, case_status, preconditions_json,
                               hardware_configuration_json, software_configuration_json, test_configuration_json,
                               parameter_configuration_json, inputs_json, expected_results_json, evaluation_criteria,
                               result_evaluation_criteria, termination_conditions_json, result_collection,
                               author_name, author_date, missing_information_json
                        FROM structured_test_case WHERE work_item_id = ? ORDER BY case_key
                        """, (row, ignored) -> {
            String caseKey = row.getString("case_key");
            List<StructuredGenerationTaskDetail.Step> steps = structuredTestcaseSteps(workItemId, caseKey);
            List<String> expectedResults = readerSafeNullableList(row.getString("expected_results_json"));
            if (expectedResults.isEmpty()) expectedResults = steps.stream().map(StructuredGenerationTaskDetail.Step::expected).toList();
            String title = readerSafeText(row.getString("title"));
            return new StructuredGenerationTaskDetail.Testcase(readerSafeText(orDefault(row.getString("name_text"), title)),
                    title, priority(row.getString("priority")).display(), caseStatusDisplay(row.getString("case_status")),
                    readerSafeList(row.getString("preconditions_json")),
                    new StructuredGenerationTaskDetail.Initialization(
                            readerSafeNullableList(row.getString("hardware_configuration_json")),
                            readerSafeNullableList(row.getString("software_configuration_json")),
                            readerSafeNullableList(row.getString("test_configuration_json")),
                            readerSafeNullableList(row.getString("parameter_configuration_json"))),
                    readerSafeDetailInputs(row.getString("inputs_json")), steps, expectedResults,
                    readerSafeText(row.getString("evaluation_criteria")), readerSafeText(row.getString("result_evaluation_criteria")),
                    readerSafeNullableList(row.getString("termination_conditions_json")), readerSafeText(row.getString("result_collection")),
                    new StructuredGenerationTaskDetail.AuthoringInformation(readerSafeText(row.getString("author_name")),
                            readerSafeText(row.getString("author_date"))),
                    testcaseRequirementSummaries(taskId, workItemId, caseKey), readerSafeList(row.getString("missing_information_json")));
        }, workItemId);
    }

    private List<StructuredGenerationTaskDetail.Step> structuredTestcaseSteps(String workItemId, String caseKey) {
        return jdbcTemplate.query("""
                        SELECT step_no, action_text, expected_text, evaluation_criteria, termination_or_error, result_collection
                        FROM structured_test_case_step
                        WHERE work_item_id = ? AND case_key = ? ORDER BY step_no
                        """, (row, ignored) -> new StructuredGenerationTaskDetail.Step(row.getInt("step_no"),
                readerSafeText(row.getString("action_text")), readerSafeText(row.getString("expected_text")),
                readerSafeText(row.getString("evaluation_criteria")), readerSafeText(row.getString("termination_or_error")),
                readerSafeText(row.getString("result_collection"))), workItemId, caseKey);
    }

    private List<String> testcaseRequirementSummaries(String taskId, String workItemId, String caseKey) {
        LinkedHashSet<String> summaries = new LinkedHashSet<>(jdbcTemplate.query("""
                        SELECT DISTINCT fact.function_name
                        FROM structured_reference_binding b
                        JOIN structured_requirement_fact fact ON fact.fact_key = b.reference_key
                        JOIN structured_generation_work_item source ON source.id = fact.work_item_id
                        WHERE b.work_item_id = ? AND b.subject_key = ? AND b.subject_type = 'TEST_CASE'
                          AND b.reference_type = 'REQUIREMENT_FACT' AND source.status = 'COMPLETED'
                          AND source.accepted_result_sha256 IS NOT NULL AND source.task_id = ?
                        ORDER BY fact.function_name
                        """, (row, ignored) -> readerSafeText(row.getString(1)), workItemId, caseKey, taskId));
        // V2 facts use task-scoped stable keys and keep their first accepted work only as provenance.
        summaries.addAll(jdbcTemplate.query("""
                        SELECT DISTINCT fact.statement_text
                        FROM structured_reference_binding binding
                        JOIN v2_requirement_fact fact
                          ON fact.task_id = ? AND fact.fact_key = binding.reference_key
                        JOIN structured_generation_work_item source ON source.id = fact.first_work_item_id
                        WHERE binding.work_item_id = ? AND binding.subject_key = ?
                          AND binding.subject_type = 'TEST_CASE' AND binding.reference_type = 'REQUIREMENT_FACT'
                          AND source.task_id = ? AND source.status = 'COMPLETED'
                          AND source.accepted_result_sha256 IS NOT NULL
                        ORDER BY fact.statement_text
                        """, (row, ignored) -> readerSafeText(row.getString(1)),
                taskId, workItemId, caseKey, taskId));
        return summaries.stream().sorted().toList();
    }

    /**
     * Reads only task-scoped aggregates for the browser detail projection. No source text, KEE coordinates, prompts,
     * raw Markdown, or per-row lookups cross this boundary.
     *
     * [Req-ID]: REQ-CWR-001, REQ-CWR-002
     */
    private GenerationTaskBusinessProgress businessProgress(
            String taskId, GenerationTaskMode taskMode, GenerationTaskStatus status, CreateGenerationTaskRequest request) {
        BusinessProgressCounts counts = jdbcTemplate.queryForObject("""
                        WITH material_document_counts AS (
                            SELECT COUNT(*) AS complete_documents
                            FROM material_inventory_document WHERE task_id = ? AND complete = TRUE
                        ),
                        material_unit_counts AS (
                            SELECT COUNT(*) AS total_units FROM material_inventory_unit WHERE task_id = ?
                        ),
                        processed_unit_counts AS (
                            SELECT COUNT(*) AS processed_units FROM (
                                SELECT document_id, unit_id
                                FROM material_audit_work WHERE task_id = ?
                                GROUP BY document_id, unit_id
                                HAVING SUM(CASE WHEN status = 'COMPLETED' THEN 0 ELSE 1 END) = 0
                            ) completed_units
                        ),
                        audit_work_counts AS (
                            SELECT COUNT(*) AS total_work,
                                   COALESCE(SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END), 0) AS completed_work,
                                   COALESCE(SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END), 0) AS failed_work,
                                   COALESCE(SUM(CASE WHEN status <> 'COMPLETED' AND audit_stage = 'FEATURE_LIST_SCAN' THEN 1 ELSE 0 END), 0) AS pending_function_list,
                                   COALESCE(SUM(CASE WHEN status <> 'COMPLETED' AND audit_stage = 'REQUIREMENT_SCAN' AND audit_pass = 1 THEN 1 ELSE 0 END), 0) AS pending_requirement_first_pass,
                                   COALESCE(SUM(CASE WHEN status <> 'COMPLETED' AND audit_stage = 'REQUIREMENT_SCAN' AND audit_pass = 2 THEN 1 ELSE 0 END), 0) AS pending_requirement_second_pass
                            FROM material_audit_work WHERE task_id = ?
                        ),
                        candidate_counts AS (
                            SELECT COUNT(*) AS candidates FROM feature_source_candidate WHERE task_id = ?
                        ),
                        conclusion_counts AS (
                            SELECT COUNT(*) AS covered_candidates,
                                   COUNT(DISTINCT CASE WHEN conclusion_type = 'FUNCTION_LIST_MISSING' THEN conclusion.id END) AS function_list_missing,
                                   COUNT(DISTINCT CASE WHEN conclusion_type = 'REQUIREMENT_MISSING' THEN conclusion.id END) AS requirement_missing,
                                   COUNT(DISTINCT CASE WHEN conclusion_type = 'CONFLICT' THEN conclusion.id END) AS conflicts,
                                   COUNT(DISTINCT CASE WHEN conclusion_type = 'SPLIT' THEN conclusion.id END) AS splits,
                                   COUNT(DISTINCT CASE WHEN conclusion_type = 'MERGE' THEN conclusion.id END) AS merges,
                                   COUNT(DISTINCT CASE WHEN conclusion_type = 'INSUFFICIENT_EVIDENCE' THEN conclusion.id END) AS insufficient_evidence
                            FROM feature_review_conclusion_candidate link
                            JOIN feature_review_conclusion conclusion
                              ON conclusion.task_id = link.task_id AND conclusion.id = link.conclusion_id
                            WHERE link.task_id = ?
                        ),
                        frozen_feature_counts AS (
                            SELECT COUNT(*) AS frozen_features,
                                   COALESCE(SUM(CASE WHEN generation_eligible = TRUE THEN 1 ELSE 0 END), 0) AS eligible_features,
                                   COALESCE(SUM(CASE WHEN generation_eligible = FALSE THEN 1 ELSE 0 END), 0) AS ineligible_features
                            FROM frozen_feature_target WHERE task_id = ?
                        ),
                        accepted_test_case_counts AS (
                            SELECT COUNT(*) AS accepted_cases
                            FROM generation_test_case_row test_case
                            JOIN generation_batch batch ON batch.id = test_case.batch_id
                            WHERE batch.task_id = ? AND batch.status = 'ACCEPTED'
                        )
                        SELECT material_document_counts.complete_documents,
                               material_unit_counts.total_units,
                               processed_unit_counts.processed_units,
                               audit_work_counts.total_work, audit_work_counts.completed_work, audit_work_counts.failed_work,
                               audit_work_counts.pending_function_list, audit_work_counts.pending_requirement_first_pass,
                               audit_work_counts.pending_requirement_second_pass,
                               candidate_counts.candidates, conclusion_counts.covered_candidates,
                               conclusion_counts.function_list_missing, conclusion_counts.requirement_missing,
                               conclusion_counts.conflicts, conclusion_counts.splits, conclusion_counts.merges,
                               conclusion_counts.insufficient_evidence,
                               frozen_feature_counts.frozen_features, frozen_feature_counts.eligible_features,
                               frozen_feature_counts.ineligible_features, accepted_test_case_counts.accepted_cases
                        FROM material_document_counts
                        CROSS JOIN material_unit_counts
                        CROSS JOIN processed_unit_counts
                        CROSS JOIN audit_work_counts
                        CROSS JOIN candidate_counts
                        CROSS JOIN conclusion_counts
                        CROSS JOIN frozen_feature_counts
                        CROSS JOIN accepted_test_case_counts
                        """, (resultSet, ignored) -> new BusinessProgressCounts(
                resultSet.getInt("complete_documents"), resultSet.getInt("total_units"), resultSet.getInt("processed_units"),
                resultSet.getInt("total_work"), resultSet.getInt("completed_work"), resultSet.getInt("failed_work"),
                resultSet.getInt("pending_function_list"), resultSet.getInt("pending_requirement_first_pass"),
                resultSet.getInt("pending_requirement_second_pass"), resultSet.getInt("candidates"),
                resultSet.getInt("covered_candidates"), resultSet.getInt("function_list_missing"),
                resultSet.getInt("requirement_missing"), resultSet.getInt("conflicts"), resultSet.getInt("splits"),
                resultSet.getInt("merges"), resultSet.getInt("insufficient_evidence"), resultSet.getInt("frozen_features"),
                resultSet.getInt("eligible_features"), resultSet.getInt("ineligible_features"), resultSet.getInt("accepted_cases")),
                taskId, taskId, taskId, taskId, taskId, taskId, taskId, taskId);
        int materialDocumentTotal = request.requirementScope().documents().size();
        boolean frozenComplete = taskMode == GenerationTaskMode.ALL
                && materialDocumentTotal > 0 && counts.completeDocuments() == materialDocumentTotal
                && counts.totalUnits() > 0 && counts.totalWork() > 0 && counts.totalWork() == counts.completedWork()
                && counts.failedWork() == 0 && counts.candidates() > 0 && counts.candidates() == counts.coveredCandidates()
                && counts.conflicts() == 0 && counts.frozenFeatures() > 0;
        Integer frozenFeatureTotal = frozenComplete ? counts.frozenFeatures() : null;
        Integer eligibleFeatureCount = frozenComplete ? counts.eligibleFeatures() : null;
        Integer ineligibleFeatureCount = frozenComplete ? counts.ineligibleFeatures() : null;
        // V2 case volume is determined by persisted test-point outcomes; retained V1 audit rows must not revive 2N.
        Integer expectedTestCaseTotal = !request.isV2() && frozenComplete ? counts.eligibleFeatures() * 2 : null;
        return new GenerationTaskBusinessProgress(
                currentBusinessStage(status, materialDocumentTotal, counts, frozenComplete), materialDocumentTotal,
                counts.completeDocuments(), counts.totalUnits(), counts.processedUnits(), counts.totalWork(),
                counts.completedWork(), counts.failedWork(), counts.candidates(), counts.functionListMissing(),
                counts.requirementMissing(), counts.conflicts(), counts.splits(), counts.merges(), counts.insufficientEvidence(),
                frozenComplete, frozenFeatureTotal, eligibleFeatureCount, ineligibleFeatureCount, expectedTestCaseTotal,
                counts.acceptedCases(), coverageStatus(status, frozenComplete), businessReason(status, frozenComplete));
    }

    private static String currentBusinessStage(
            GenerationTaskStatus status, int materialDocumentTotal, BusinessProgressCounts counts, boolean frozenComplete) {
        return switch (status) {
            case QUEUED -> "排队等待";
            case AUDITING -> {
                if (counts.completeDocuments() < materialDocumentTotal) yield "材料清单";
                if (counts.pendingFunctionList() > 0) yield "功能清单扫描";
                if (counts.pendingRequirementFirstPass() > 0) yield "需求扫描（第一遍）";
                if (counts.pendingRequirementSecondPass() > 0) yield "需求扫描（第二遍）";
                if (counts.candidates() != counts.coveredCandidates()) yield "双向核对";
                yield frozenComplete ? "功能冻结" : "审查收敛";
            }
            case GENERATING -> "测试用例生成";
            case VALIDATING -> "结果校验";
            case COMPLETED -> "已完成";
            case PARTIAL -> "部分完成";
            case FAILED -> "已失败";
            case CANCELLED -> "已取消";
        };
    }

    private static String coverageStatus(GenerationTaskStatus status, boolean frozenComplete) {
        if (status == GenerationTaskStatus.COMPLETED) return "完整";
        if (status == GenerationTaskStatus.PARTIAL || (status == GenerationTaskStatus.FAILED && frozenComplete)) {
            return "审查完整但用例不完整";
        }
        if (status == GenerationTaskStatus.FAILED) return "材料或审查失败";
        return status == GenerationTaskStatus.CANCELLED ? "已取消" : "进行中";
    }

    private static String businessReason(GenerationTaskStatus status, boolean frozenComplete) {
        if (status == GenerationTaskStatus.COMPLETED) return "材料审查、功能冻结和测试用例均已完成";
        if (status == GenerationTaskStatus.PARTIAL || (status == GenerationTaskStatus.FAILED && frozenComplete)) {
            return "审查已完成，但部分功能未接收测试用例";
        }
        if (status == GenerationTaskStatus.FAILED) return "材料清单或审查未完成，不能视为完整交付";
        if (status == GenerationTaskStatus.CANCELLED) return "任务已取消，未形成完整交付";
        return "正在处理材料、审查或测试用例";
    }

    public GenerationTaskPage findPage(int page, int size, String query) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page must be non-negative and size must be between 1 and 100");
        }
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String likeQuery = "%" + normalizedQuery + "%";
        long totalItems = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM generation_task WHERE LOWER(id) LIKE ?", Long.class, likeQuery);
        List<GenerationTaskListItem> items = jdbcTemplate.query("""
                        SELECT t.id, t.task_mode, t.status, t.created_at, t.artifact_id, t.result_snapshot,
                               (SELECT COUNT(*) FROM generation_batch b WHERE b.task_id = t.id) AS total_batches,
                               (SELECT COUNT(*) FROM generation_batch b WHERE b.task_id = t.id
                                   AND b.status IN ('ACCEPTED', 'FAILED', 'CANCELLED')) AS completed_batches,
                               (SELECT a.failure_reason FROM generation_attempt a
                                JOIN generation_batch b ON b.id = a.batch_id
                                WHERE b.task_id = t.id AND b.status = 'FAILED'
                                  AND a.attempt_number = (SELECT MAX(latest.attempt_number)
                                      FROM generation_attempt latest WHERE latest.batch_id = b.id)
                                  AND a.failure_reason IS NOT NULL
                                ORDER BY b.batch_sequence, a.id LIMIT 1) AS failure_summary
                        FROM generation_task t
                        WHERE LOWER(t.id) LIKE ?
                        ORDER BY t.created_at DESC, t.id DESC
                        LIMIT ? OFFSET ?
                        """, (resultSet, ignored) -> new GenerationTaskListItem(
                resultSet.getString("id"),
                GenerationTaskMode.valueOf(resultSet.getString("task_mode")),
                GenerationTaskStatus.valueOf(resultSet.getString("status")),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getInt("total_batches"),
                                resultSet.getInt("completed_batches"),
                                resultSet.getString("failure_summary") != null ? resultSet.getString("failure_summary")
                                        : taskFailureSummary(resultSet.getString("result_snapshot")),
                                resultSet.getString("artifact_id") != null), likeQuery, size, page * size);
        return new GenerationTaskPage(items, page, size, totalItems);
    }

    public Optional<StoredArtifact> findReadyArtifact(String artifactId) {
        return jdbcTemplate.query("""
                        SELECT artifact_id, artifact_sha256, artifact_path
                        FROM generation_task
                        WHERE artifact_id = ? AND status IN ('COMPLETED', 'PARTIAL')
                          AND artifact_sha256 IS NOT NULL AND artifact_path IS NOT NULL
                        """, (resultSet, ignored) -> new StoredArtifact(
                resultSet.getString("artifact_id"),
                resultSet.getString("artifact_sha256"),
                Path.of(resultSet.getString("artifact_path"))), artifactId).stream().findFirst();
    }

    /**
     * Returns the current artifact identity only for a completed structured ALL task with an existing validated file.
     * The returned identity is the compare-and-swap baseline for a later publication attempt.
     *
     * [Req-ID]: REQ-SGD-005
     */
    public String structuredArtifactRegenerationBaseline(String taskId) {
        List<StructuredArtifactBaseline> rows = jdbcTemplate.query("""
                        SELECT task_mode, status, structured_processing_status, artifact_id, artifact_sha256, artifact_path
                        FROM generation_task WHERE id = ?
                        """, (row, ignored) -> new StructuredArtifactBaseline(
                row.getString("task_mode"), row.getString("status"), row.getString("structured_processing_status"),
                row.getString("artifact_id"), row.getString("artifact_sha256"), row.getString("artifact_path")), taskId);
        if (rows.isEmpty()) throw new GenerationTaskNotFoundException(taskId);
        StructuredArtifactBaseline baseline = rows.get(0);
        if (!GenerationTaskMode.ALL.name().equals(baseline.taskMode())
                || !GenerationTaskStatus.COMPLETED.name().equals(baseline.status())
                || !StructuredProcessingStatus.COMPLETED.name().equals(baseline.processingStatus())
                || baseline.artifactId() == null || baseline.artifactId().isBlank()
                || baseline.sha256() == null || baseline.sha256().isBlank()
                || baseline.path() == null || baseline.path().isBlank()) {
            throw new IllegalStateException("Structured artifact regeneration is not available for this task");
        }
        return baseline.artifactId();
    }

    /**
     * Atomically publishes a regenerated file only if no competing request has replaced the expected artifact.
     * Structured business tables are never touched by this compare-and-swap update.
     *
     * [Req-ID]: REQ-SGD-005
     */
    public void replaceStructuredArtifact(String taskId, String expectedArtifactId, WorkbookArtifact artifact) {
        if (expectedArtifactId == null || expectedArtifactId.isBlank()) {
            throw new IllegalArgumentException("Expected artifact identity is required");
        }
        WorkbookArtifact replacement = java.util.Objects.requireNonNull(artifact, "Replacement artifact is required");
        int changed = jdbcTemplate.update("""
                UPDATE generation_task
                SET artifact_id = ?, artifact_sha256 = ?, artifact_path = ?
                WHERE id = ? AND artifact_id = ? AND task_mode = 'ALL' AND status = 'COMPLETED'
                  AND structured_processing_status = 'COMPLETED'
                  AND artifact_sha256 IS NOT NULL AND artifact_path IS NOT NULL
                """, replacement.artifactId(), replacement.sha256(), replacement.path().toString(), taskId, expectedArtifactId);
        if (changed != 1) throw new IllegalStateException("Structured artifact regeneration conflict");
    }

    private boolean hasCompleteAllAuditAndFreeze(String taskId, RequirementScope scope) {
        if (!hasCompleteMaterialInventory(taskId, scope)) {
            return false;
        }
        FeatureAuditCounts counts = featureAuditCounts(taskId);
        if (counts.totalWork() == 0 || counts.totalWork() != counts.completedWork()
                || counts.permanentlyFailedWork() != 0 || counts.candidateCount() == 0
                || counts.candidateCount() != counts.coveredCandidateCount()) {
            return false;
        }
        List<FrozenFeatureTarget> targets = frozenFeatureTargets(taskId);
        if (targets.isEmpty()) {
            return false;
        }
        try {
            requireFrozenTargetsMatchConclusions(taskId, targets);
        } catch (IllegalStateException invalidFreeze) {
            return false;
        }
        Integer conflicts = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM feature_review_conclusion
                        WHERE task_id = ? AND conclusion_type = 'CONFLICT'
                        """, Integer.class, taskId);
        return conflicts != null && conflicts == 0;
    }

    private int acceptedFrozenBatchCount(String taskId) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM generation_batch batch
                        JOIN frozen_feature_target target
                          ON target.task_id = batch.task_id AND target.stable_feature_id = batch.feature_id
                        WHERE batch.task_id = ? AND batch.status = 'ACCEPTED' AND target.generation_eligible = TRUE
                        """, Integer.class, taskId);
        return count == null ? 0 : count;
    }

    private boolean acceptedBatchesHaveExactlyTwoRows(String taskId) {
        Integer invalid = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM (
                            SELECT batch.id
                            FROM generation_batch batch
                            LEFT JOIN generation_test_case_row test_case ON test_case.batch_id = batch.id
                            WHERE batch.task_id = ? AND batch.status = 'ACCEPTED'
                            GROUP BY batch.id
                            HAVING COUNT(test_case.batch_id) <> 2
                        ) invalid_batches
                        """, Integer.class, taskId);
        return invalid != null && invalid == 0;
    }

    private int acceptedTestCaseRowCount(String taskId) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM generation_test_case_row test_case
                        JOIN generation_batch batch ON batch.id = test_case.batch_id
                        WHERE batch.task_id = ? AND batch.status = 'ACCEPTED'
                        """, Integer.class, taskId);
        return count == null ? 0 : count;
    }

    private int permanentlyFailedBatchCount(String taskId) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM generation_batch batch
                        JOIN generation_attempt attempt ON attempt.batch_id = batch.id
                        WHERE batch.task_id = ? AND batch.status = 'FAILED'
                          AND attempt.attempt_number = (
                              SELECT MAX(latest.attempt_number) FROM generation_attempt latest
                              WHERE latest.batch_id = batch.id)
                          AND (attempt.retryable = FALSE OR attempt.attempt_number >= ?)
                        """, Integer.class, taskId, MAX_ATTEMPTS);
        return count == null ? 0 : count;
    }

    private void clearArtifactMetadata(String taskId) {
        jdbcTemplate.update("""
                        UPDATE generation_task
                        SET artifact_id = NULL, artifact_sha256 = NULL, artifact_path = NULL
                        WHERE id = ?
                        """, taskId);
    }

    private int count(String sql, Object... arguments) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, arguments);
        return value == null ? 0 : value;
    }

    private void transitionBatch(String batchId, GenerationBatchStatus target) {
        GenerationBatchStatus current = batchStatus(batchId);
        if (!current.canTransitionTo(target)) {
            throw new IllegalStateException("Illegal batch status transition from " + current + " to " + target);
        }
        int changed = jdbcTemplate.update("UPDATE generation_batch SET status = ? WHERE id = ? AND status = ?",
                target.name(), batchId, current.name());
        if (changed != 1) {
            throw new IllegalStateException("Batch state changed concurrently: " + batchId);
        }
    }

    private int nextBatchSequence(String taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(batch_sequence), 0) + 1 FROM generation_batch WHERE task_id = ?", Integer.class, taskId);
    }

    private void persistMarkdownRows(String batchId, MarkdownGenerationResult result) {
        for (MarkdownAuditRow row : result.auditRows()) {
            jdbcTemplate.update("""
                            INSERT INTO generation_audit_row (
                                batch_id, row_sequence, subject_or_feature, issue_category, evidence_comparison)
                            VALUES (?, ?, ?, ?, ?)
                            """, batchId, row.sequence(), row.subjectOrFeature(), row.issueCategory(), row.evidenceComparison());
        }
        for (int index = 0; index < result.testCaseRows().size(); index++) {
            MarkdownTestCaseRow row = result.testCaseRows().get(index);
            jdbcTemplate.update("""
                            INSERT INTO generation_test_case_row (
                                batch_id, row_sequence, case_name, feature_module, preconditions,
                                execution_steps, expected_result, requirement_content)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            """, batchId, index + 1, row.caseName(), row.featureModule(), row.preconditions(),
                    row.executionSteps(), row.expectedResult(), row.requirementContent());
        }
    }

    private void requireRunningAuditClaim(AuditWorkClaim claim) {
        int active = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM material_audit_work w
                        JOIN material_audit_attempt a ON a.work_id = w.id
                        WHERE w.id = ? AND w.task_id = ? AND w.status = 'RUNNING'
                          AND a.id = ? AND a.status = 'RUNNING'
                        FOR UPDATE
                        """, Integer.class, claim.workId(), claim.taskId(), claim.attemptId());
        if (active != 1) throw new IllegalStateException("Audit work claim is no longer running: " + claim.workId());
    }

    private void completeAuditWorkInTransaction(AuditWorkClaim claim) {
        int workUpdated = jdbcTemplate.update("""
                        UPDATE material_audit_work w JOIN material_audit_attempt a ON a.work_id = w.id
                        SET w.status = 'COMPLETED', w.lease_owner = NULL, w.lease_expires_at = NULL
                        WHERE w.id = ? AND w.task_id = ? AND w.status = 'RUNNING' AND a.id = ? AND a.status = 'RUNNING'
                        """, claim.workId(), claim.taskId(), claim.attemptId());
        int attemptUpdated = jdbcTemplate.update("""
                        UPDATE material_audit_attempt
                        SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP(6)
                        WHERE id = ? AND work_id = ? AND status = 'RUNNING'
                        """, claim.attemptId(), claim.workId());
        if (workUpdated != 1 || attemptUpdated != 1) {
            throw new IllegalStateException("Audit work claim is no longer running: " + claim.workId());
        }
    }

    private void persistFeatureSourceCandidate(AuditWorkClaim claim, FeatureSourceCandidate candidate) {
        if (!claim.documentId().equals(candidate.documentId()) || !claim.unitId().equals(candidate.unitId())
                || claim.passNumber() != candidate.passNumber()) {
            throw new IllegalArgumentException("Candidate does not belong to the claimed task unit and pass");
        }
        try {
            jdbcTemplate.update("""
                            INSERT INTO feature_source_candidate (
                                id, task_id, document_id, unit_id, source_ordinal, model_sequence, candidate_kind,
                                candidate_text, candidate_category, evidence_text, audit_pass, source_row_position)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """, candidate.occurrenceId(), claim.taskId(), candidate.documentId(), candidate.unitId(),
                    candidate.ordinal(), candidate.modelSequence(), candidate.kind().name(), candidate.featureText(),
                    candidate.category(), candidate.evidenceText(), candidate.passNumber(), candidate.sourceRowPosition());
        } catch (DuplicateKeyException duplicate) {
            FeatureSourceCandidate stored = jdbcTemplate.query(featureCandidateSelect("WHERE task_id = ? AND id = ? FOR UPDATE"),
                    featureCandidateRowMapper(), claim.taskId(), candidate.occurrenceId()).stream().findFirst().orElseThrow(() -> duplicate);
            if (!stored.equals(candidate)) {
                throw new IllegalStateException("Feature candidate replay conflicts with retained occurrence: "
                        + candidate.occurrenceId());
            }
        }
    }

    private void persistDuplicateOccurrence(AuditWorkClaim claim, FeatureSourceCandidate candidate) {
        if (!claim.documentId().equals(candidate.documentId()) || !claim.unitId().equals(candidate.unitId())
                || claim.passNumber() != candidate.passNumber()) {
            throw new IllegalArgumentException("Duplicate occurrence does not belong to the claimed task unit and pass");
        }
        try {
            jdbcTemplate.update("""
                            INSERT INTO material_audit_duplicate_occurrence (
                                work_id, occurrence_id, model_sequence, source_row_position, candidate_text,
                                candidate_category, evidence_text)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """, claim.workId(), candidate.occurrenceId(), candidate.modelSequence(), candidate.sourceRowPosition(),
                    candidate.featureText(), candidate.category(), candidate.evidenceText());
        } catch (DuplicateKeyException duplicate) {
            Integer same = jdbcTemplate.queryForObject("""
                            SELECT COUNT(*) FROM material_audit_duplicate_occurrence
                            WHERE work_id = ? AND occurrence_id = ? AND model_sequence = ? AND source_row_position = ?
                              AND candidate_text = ? AND candidate_category = ? AND evidence_text = ?
                            """, Integer.class, claim.workId(), candidate.occurrenceId(), candidate.modelSequence(),
                    candidate.sourceRowPosition(), candidate.featureText(), candidate.category(), candidate.evidenceText());
            if (same == null || same != 1) throw new IllegalStateException("Duplicate occurrence replay conflicts with retained audit result");
        }
    }

    private void persistScanOutcome(AuditWorkClaim claim, int acceptedCount, int duplicateCount, boolean converged) {
        try {
            jdbcTemplate.update("""
                            INSERT INTO material_audit_scan_outcome (
                                work_id, accepted_candidate_count, duplicate_occurrence_count, converged)
                            VALUES (?, ?, ?, ?)
                            """, claim.workId(), acceptedCount, duplicateCount, converged);
        } catch (DuplicateKeyException duplicate) {
            Integer same = jdbcTemplate.queryForObject("""
                            SELECT COUNT(*) FROM material_audit_scan_outcome
                            WHERE work_id = ? AND accepted_candidate_count = ? AND duplicate_occurrence_count = ?
                              AND converged = ?
                            """, Integer.class, claim.workId(), acceptedCount, duplicateCount, converged);
            if (same == null || same != 1) throw new IllegalStateException("Audit scan outcome replay conflicts with retained result");
        }
    }

    private void persistFeatureReviewConclusion(String taskId, FeatureReviewConclusion conclusion) {
        try {
            jdbcTemplate.update("""
                            INSERT INTO feature_review_conclusion (
                                id, task_id, conclusion_sequence, conclusion_type, explanation, evidence_text)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """, conclusion.conclusionId(), taskId, conclusion.sequence(), conclusion.type().name(),
                    conclusion.explanation(), conclusion.evidenceText());
        } catch (DuplicateKeyException duplicate) {
            FeatureReviewConclusion stored = jdbcTemplate.query("""
                            SELECT id, conclusion_sequence, conclusion_type, explanation, evidence_text
                            FROM feature_review_conclusion WHERE task_id = ? AND conclusion_sequence = ? FOR UPDATE
                            """, (resultSet, ignored) -> new FeatureReviewConclusion(resultSet.getString("id"),
                    resultSet.getInt("conclusion_sequence"), FeatureReviewConclusionType.valueOf(resultSet.getString("conclusion_type")),
                    resultSet.getString("explanation"), resultSet.getString("evidence_text"), conclusion.candidateIds()), taskId,
                    conclusion.sequence()).stream().findFirst().orElseThrow(() -> duplicate);
            if (!stored.conclusionId().equals(conclusion.conclusionId()) || stored.type() != conclusion.type()
                    || !stored.explanation().equals(conclusion.explanation()) || !stored.evidenceText().equals(conclusion.evidenceText())) {
                throw new IllegalStateException("Feature conclusion replay conflicts with retained sequence: " + conclusion.sequence());
            }
        }
        for (String candidateId : conclusion.candidateIds()) {
            try {
                jdbcTemplate.update("""
                                INSERT INTO feature_review_conclusion_candidate (task_id, conclusion_id, source_candidate_id)
                                VALUES (?, ?, ?)
                                """, taskId, conclusion.conclusionId(), candidateId);
            } catch (DuplicateKeyException duplicate) {
                Integer same = jdbcTemplate.queryForObject("""
                                SELECT COUNT(*) FROM feature_review_conclusion_candidate
                                WHERE task_id = ? AND conclusion_id = ? AND source_candidate_id = ?
                                """, Integer.class, taskId, conclusion.conclusionId(), candidateId);
                if (same == null || same != 1) {
                    throw new IllegalStateException("Feature conclusion candidate replay conflicts with retained conclusion");
                }
            }
        }
    }

    private void requireTaskExistsForUpdate(String taskId) {
        Integer retained = jdbcTemplate.query("SELECT 1 FROM generation_task WHERE id = ? FOR UPDATE",
                (resultSet, ignored) -> resultSet.getInt(1), taskId).stream().findFirst().orElse(null);
        if (retained == null) throw new GenerationTaskNotFoundException(taskId);
    }

    private void requireFrozenTargetsMatchConclusions(String taskId, List<FrozenFeatureTarget> targets) {
        List<FeatureReviewConclusion> conclusions = featureReviewConclusions(taskId);
        if (conclusions.isEmpty()) throw new IllegalStateException("Frozen feature targets require retained conclusions");
        Map<String, FeatureReviewConclusion> byId = new LinkedHashMap<>();
        for (FeatureReviewConclusion conclusion : conclusions) {
            if (byId.put(conclusion.conclusionId(), conclusion) != null) {
                throw new IllegalStateException("Retained conclusion ids must be unique");
            }
        }
        Set<String> targetIds = new LinkedHashSet<>();
        Set<Integer> sequences = new LinkedHashSet<>();
        Set<String> coveredCandidateIds = new LinkedHashSet<>();
        Set<String> coveredConclusionIds = new LinkedHashSet<>();
        for (FrozenFeatureTarget target : targets) {
            if (!targetIds.add(target.stableFeatureId()) || !sequences.add(target.stableSequence())
                    || target.stableSequence() < 1 || target.stableSequence() > targets.size()) {
                throw new IllegalStateException("Frozen feature ids and sequences must be distinct and contiguous");
            }
            FeatureReviewConclusion conclusion = byId.get(target.source().conclusionId());
            if (conclusion == null || conclusion.type() == FeatureReviewConclusionType.CONFLICT
                    || conclusion.type() != target.source().conclusionType()
                    || !conclusion.explanation().equals(target.source().decisionReason())
                    || !conclusion.candidateIds().stream().sorted().toList().equals(target.source().candidateIds())
                    || target.generationEligible() == (conclusion.type() == FeatureReviewConclusionType.INSUFFICIENT_EVIDENCE)) {
                throw new IllegalStateException("Frozen target conflicts with its retained conclusion");
            }
            coveredConclusionIds.add(conclusion.conclusionId());
            coveredCandidateIds.addAll(conclusion.candidateIds());
        }
        if (!coveredConclusionIds.equals(byId.keySet())) {
            throw new IllegalStateException("Every retained conclusion requires at least one frozen target");
        }
        Set<String> candidateIds = new LinkedHashSet<>(jdbcTemplate.query(
                "SELECT id FROM feature_source_candidate WHERE task_id = ?", (resultSet, ignored) -> resultSet.getString("id"), taskId));
        if (!coveredCandidateIds.equals(candidateIds)) {
            throw new IllegalStateException("Frozen target candidate coverage conflicts with retained task candidates");
        }
    }

    private static String featureCandidateSelect(String whereAndOrder) {
        return """
                SELECT id, candidate_kind, document_id, unit_id, source_ordinal, model_sequence, candidate_text,
                       candidate_category, evidence_text, audit_pass, source_row_position
                FROM feature_source_candidate
                """ + whereAndOrder;
    }

    private static org.springframework.jdbc.core.RowMapper<FeatureSourceCandidate> featureCandidateRowMapper() {
        return (resultSet, ignored) -> new FeatureSourceCandidate(resultSet.getString("id"),
                com.testcaseagent.featureaudit.FeatureCandidateKind.valueOf(resultSet.getString("candidate_kind")),
                resultSet.getString("document_id"), resultSet.getString("unit_id"), resultSet.getInt("source_ordinal"),
                resultSet.getInt("model_sequence"), resultSet.getString("candidate_text"),
                resultSet.getString("candidate_category"), resultSet.getString("evidence_text"), resultSet.getInt("audit_pass"),
                resultSet.getInt("source_row_position"));
    }

    private void persistMaterialUnit(String taskId, MaterialInventoryUnit unit) {
        try {
            jdbcTemplate.update("""
                            INSERT INTO material_inventory_unit (
                                task_id, document_id, unit_id, document_role, chunk_index, ordinal,
                                content, start_at, end_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """, taskId, unit.documentId(), unit.unitId(), unit.documentRole(), unit.chunkIndex(),
                    unit.ordinal(), unit.content(), unit.startAt(), unit.endAt());
        } catch (DuplicateKeyException duplicate) {
            MaterialInventoryUnit stored = jdbcTemplate.query("""
                            SELECT document_id, document_role, unit_id, chunk_index, ordinal, content, start_at, end_at
                            FROM material_inventory_unit
                            WHERE task_id = ? AND document_id = ? AND unit_id = ?
                            FOR UPDATE
                            """, (resultSet, ignoredRow) -> new MaterialInventoryUnit(
                    resultSet.getString("document_id"), resultSet.getString("document_role"), resultSet.getString("unit_id"),
                    resultSet.getInt("chunk_index"), resultSet.getInt("ordinal"), resultSet.getString("content"),
                    resultSet.getLong("start_at"), resultSet.getLong("end_at")), taskId, unit.documentId(), unit.unitId())
                    .stream().findFirst().orElseThrow(() -> duplicate);
            if (!stored.equals(unit)) {
                throw new IllegalStateException("Material inventory replay conflicts with the retained unit: "
                        + unit.documentId() + "/" + unit.unitId());
            }
        }
    }

    private void persistMaterialDocument(String taskId, MaterialInventoryDocument document) {
        jdbcTemplate.update("""
                        INSERT INTO material_inventory_document (
                            task_id, document_id, knowledge_id, document_role, total_units, complete)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, taskId, document.documentId(), document.knowledgeId(), document.documentRole(),
                document.totalUnits(), document.complete());
    }

    private boolean hasAnyMaterialInventory(String taskId) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT (SELECT COUNT(*) FROM material_inventory_document WHERE task_id = ?)
                             + (SELECT COUNT(*) FROM material_inventory_unit WHERE task_id = ?)
                        """, Integer.class, taskId, taskId);
        return count != null && count > 0;
    }

    private boolean matchesCompleteMaterialInventory(String taskId, List<MaterialInventoryDocument> replacement) {
        List<MaterialInventoryDocument> retained = materialInventoryDocuments(taskId);
        return retained.equals(replacement) && retained.stream().allMatch(document -> document.complete()
                && document.totalUnits() == document.units().size());
    }

    private void clearMaterialAuditState(String taskId) {
        jdbcTemplate.update("DELETE FROM feature_review_conclusion_candidate WHERE task_id = ?", taskId);
        jdbcTemplate.update("DELETE FROM feature_review_conclusion WHERE task_id = ?", taskId);
        jdbcTemplate.update("DELETE FROM frozen_feature_target WHERE task_id = ?", taskId);
        jdbcTemplate.update("DELETE FROM feature_source_candidate WHERE task_id = ?", taskId);
        jdbcTemplate.update("""
                        DELETE d FROM material_audit_duplicate_occurrence d
                        JOIN material_audit_work w ON w.id = d.work_id
                        WHERE w.task_id = ?
                        """, taskId);
        jdbcTemplate.update("""
                        DELETE o FROM material_audit_scan_outcome o
                        JOIN material_audit_work w ON w.id = o.work_id
                        WHERE w.task_id = ?
                        """, taskId);
        jdbcTemplate.update("""
                        DELETE a FROM material_audit_attempt a
                        JOIN material_audit_work w ON w.id = a.work_id
                        WHERE w.task_id = ?
                        """, taskId);
        jdbcTemplate.update("DELETE FROM material_audit_work WHERE task_id = ?", taskId);
        jdbcTemplate.update("DELETE FROM material_inventory_unit WHERE task_id = ?", taskId);
        jdbcTemplate.update("DELETE FROM material_inventory_document WHERE task_id = ?", taskId);
    }

    private void createInitialAuditWork(String taskId, MaterialInventoryUnit unit) {
        if ("FUNCTION_LIST".equals(unit.documentRole())) {
            insertInitialAuditWork(taskId, unit, 1, "FEATURE_LIST_SCAN");
            return;
        }
        insertInitialAuditWork(taskId, unit, 1, "REQUIREMENT_SCAN");
        insertInitialAuditWork(taskId, unit, 2, "REQUIREMENT_SCAN");
    }

    private void insertInitialAuditWork(String taskId, MaterialInventoryUnit unit, int passNumber, String stage) {
        String workId = UUID.nameUUIDFromBytes((taskId + "|" + unit.documentId() + "|" + unit.unitId() + "|"
                + passNumber + "|" + stage).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        jdbcTemplate.update("""
                        INSERT INTO material_audit_work (
                            id, task_id, document_id, unit_id, audit_pass, audit_stage, status)
                        VALUES (?, ?, ?, ?, ?, ?, 'QUEUED')
                        """, workId, taskId, unit.documentId(), unit.unitId(), passNumber, stage);
    }

    private static void requireCompleteDistinctDocuments(List<MaterialInventoryDocument> documents) {
        if (documents.isEmpty()) {
            throw new IllegalArgumentException("A complete material inventory must contain at least one document");
        }
        long distinctIds = documents.stream().map(MaterialInventoryDocument::documentId).distinct().count();
        if (distinctIds != documents.size()) {
            throw new IllegalArgumentException("Material inventory documents must not contain duplicates");
        }
    }

    private void recoverExpiredAuditWorkInTransaction() {
        jdbcTemplate.update("""
                        UPDATE material_audit_attempt a JOIN material_audit_work w ON w.id = a.work_id
                        SET a.status = 'FAILED', a.failure_summary = 'Audit work lease expired',
                            a.completed_at = CURRENT_TIMESTAMP(6)
                        WHERE w.status = 'RUNNING' AND w.lease_expires_at < CURRENT_TIMESTAMP(6)
                          AND a.status = 'RUNNING'
                        """);
        jdbcTemplate.update("""
                        UPDATE material_audit_work w
                        SET status = CASE WHEN (
                                SELECT COUNT(*) FROM material_audit_attempt a
                                WHERE a.work_id = w.id AND a.status = 'FAILED') >= ?
                                THEN 'FAILED' ELSE 'QUEUED' END,
                            lease_owner = NULL, lease_expires_at = NULL
                        WHERE status = 'RUNNING' AND lease_expires_at < CURRENT_TIMESTAMP(6)
                        """, MAX_ATTEMPTS);
    }

    private void requireTaskStatus(String taskId, GenerationTaskStatus expected) {
        GenerationTaskStatus current = taskStatus(taskId);
        if (current != expected) {
            throw new IllegalStateException("Expected task status " + expected + " but was " + current);
        }
    }

    private void requireBatchStatus(String batchId, GenerationBatchStatus expected) {
        GenerationBatchStatus current = batchStatus(batchId);
        if (current != expected) {
            throw new IllegalStateException("Expected batch status " + expected + " but was " + current);
        }
    }

    public GenerationTaskStatus taskStatus(String taskId) {
        String status = jdbcTemplate.query("SELECT status FROM generation_task WHERE id = ?",
                (resultSet, ignored) -> resultSet.getString("status"), taskId).stream().findFirst()
                .orElseThrow(() -> new GenerationTaskNotFoundException(taskId));
        return GenerationTaskStatus.valueOf(status);
    }

    private GenerationBatchStatus batchStatus(String batchId) {
        String status = jdbcTemplate.query("SELECT status FROM generation_batch WHERE id = ?",
                (resultSet, ignored) -> resultSet.getString("status"), batchId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Batch not found: " + batchId));
        return GenerationBatchStatus.valueOf(status);
    }

    public BatchCounts batchCounts(String taskId) {
        return jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) AS total,
                               SUM(CASE WHEN status IN ('ACCEPTED', 'FAILED', 'CANCELLED') THEN 1 ELSE 0 END) AS completed,
                               SUM(CASE WHEN status = 'ACCEPTED' THEN 1 ELSE 0 END) AS accepted,
                               SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed
                        FROM generation_batch WHERE task_id = ?
                        """, (resultSet, ignored) -> new BatchCounts(
                resultSet.getInt("total"), resultSet.getInt("completed"),
                resultSet.getInt("accepted"), resultSet.getInt("failed")), taskId);
    }

    private List<GenerationBatchDetail> batches(String taskId) {
        return jdbcTemplate.query("""
                        SELECT b.id, b.feature_id, b.status,
                                CASE WHEN b.status = 'FAILED' THEN
                                    (SELECT a.failure_reason FROM generation_attempt a
                                     WHERE a.batch_id = b.id
                                       AND a.attempt_number = (SELECT MAX(latest.attempt_number)
                                           FROM generation_attempt latest WHERE latest.batch_id = b.id)
                                       AND a.failure_reason IS NOT NULL
                                     ORDER BY a.id LIMIT 1)
                                END AS failure_summary
                        FROM generation_batch b WHERE b.task_id = ?
                        ORDER BY b.batch_sequence
                        """, (resultSet, ignored) -> new GenerationBatchDetail(
                resultSet.getString("id"), resultSet.getString("feature_id"),
                GenerationBatchStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("failure_summary")), taskId);
    }

    private String asJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize durable task data", exception);
        }
    }

    private <T> T fromJson(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to read durable task data", exception);
        }
    }

    private List<String> readerSafeList(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<List<String>>() { }).stream()
                    .map(GenerationTaskRepository::readerSafeText).toList();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to read validated structured list", exception);
        }
    }

    private List<String> readerSafeNestedList(String value) {
        if (value == null) return List.of();
        try {
            return objectMapper.readValue(value, new TypeReference<List<List<String>>>() { }).stream()
                    .flatMap(List::stream)
                    .map(GenerationTaskRepository::readerSafeText)
                    .toList();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to read validated nested structured list", exception);
        }
    }

    private List<StructuredTestStep> readerSafeSteps(String value) {
        if (value == null) return List.of();
        try {
            return objectMapper.readValue(value, new TypeReference<List<PersistedExportStep>>() { }).stream()
                    .sorted(java.util.Comparator.comparingInt(PersistedExportStep::stepNo))
                    .map(step -> new StructuredTestStep(step.stepNo(), readerSafeText(step.action()),
                            readerSafeText(step.expected()), readerSafeText(step.evaluationCriteria()),
                            readerSafeText(step.terminationOrError()), readerSafeText(step.resultCollection())))
                    .toList();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to read validated structured testcase steps", exception);
        }
    }

    private static String documentRoleForScope(
            com.testcaseagent.scope.RequirementDocumentCoordinate document) {
        if (document == null || document.materialTypeKey() == null) {
            throw new IllegalStateException("V2 frozen material is missing its persisted type");
        }
        return switch (document.materialTypeKey()) {
            case "function_list" -> "FUNCTION_LIST";
            case "work_order_plan" -> "WORK_ORDER_PLAN";
            case "requirements_spec" -> "REQUIREMENT";
            case "prototype" -> "PROTOTYPE";
            case "requirement_list" -> "REQUIREMENT_LIST";
            default -> throw new IllegalStateException("V2 frozen material type is unsupported");
        };
    }

    private List<String> readerSafeNullableList(String value) {
        return value == null ? List.of() : readerSafeList(value);
    }

    private List<StructuredTestCaseRow.TestInput> readerSafeInputs(String value) {
        if (value == null) return List.of();
        try {
            return objectMapper.readValue(value, new TypeReference<List<PersistedTestInput>>() { }).stream()
                    .map(input -> new StructuredTestCaseRow.TestInput(readerSafeText(input.content()),
                            StructuredTestCaseRow.InputNature.valueOf(input.nature().toUpperCase(Locale.ROOT)),
                            StructuredTestCaseRow.InputSource.valueOf(input.source().toUpperCase(Locale.ROOT)),
                            StructuredTestCaseRow.TestMethod.valueOf(input.method().toUpperCase(Locale.ROOT)),
                            StructuredTestCaseRow.Authenticity.valueOf(input.authenticity().toUpperCase(Locale.ROOT)),
                            readerSafeText(input.sequence())))
                    .toList();
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalStateException("Unable to read validated structured testcase inputs", exception);
        }
    }

    private List<StructuredGenerationTaskDetail.TestInput> readerSafeDetailInputs(String value) {
        return readerSafeInputs(value).stream().map(input -> new StructuredGenerationTaskDetail.TestInput(
                input.content(), input.nature().display(), input.source().display(), input.method().display(),
                input.authenticity().display(), input.sequence())).toList();
    }

    private static StructuredTestCaseRow.Priority priority(String value) {
        return value == null ? StructuredTestCaseRow.Priority.MEDIUM : StructuredTestCaseRow.Priority.valueOf(value);
    }

    private static String feedbackTypeDisplay(String value) {
        return switch (value) {
            case "ambiguous" -> "表述含糊";
            case "contradictory" -> "内容矛盾";
            case "unquantified" -> "未量化";
            case "unobservable_result" -> "结果不可观察";
            case "placeholder_or_todo" -> "占位或待补充";
            default -> throw new IllegalStateException("Unknown validated V2 feedback type");
        };
    }

    private static String factTypeDisplay(String value) {
        return switch (value) {
            case "role" -> "角色";
            case "trigger_condition" -> "触发条件";
            case "input" -> "输入";
            case "business_rule" -> "业务规则";
            case "output" -> "输出";
            case "permission" -> "权限";
            case "state_change" -> "状态变化";
            case "exception_handling" -> "异常处理";
            case "external_dependency" -> "外部依赖";
            default -> throw new IllegalStateException("Unknown validated V2 fact type");
        };
    }

    private static String generationOutcomeDisplay(String value) {
        if (value == null) return "历史结果";
        return switch (value) {
            case "generated" -> "已生成";
            case "pending_only" -> "仅待确认";
            case "unable_to_generate" -> "无法生成";
            default -> throw new IllegalStateException("Unknown validated V2 generation outcome");
        };
    }

    private static String testPointTypeDisplay(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "normal_behavior" -> "正常行为";
            case "input_validation" -> "输入校验";
            case "boundary_value" -> "边界值";
            case "permission" -> "权限";
            case "state_transition" -> "状态转换";
            case "business_exception" -> "业务异常";
            case "dependency_failure" -> "依赖失败";
            default -> throw new IllegalStateException("Unknown validated V2 test-point type");
        };
    }

    private static String reviewClassification(String rootCause, String issueType) {
        if (rootCause == null) return readerSafeText(issueType);
        return switch (rootCause) {
            case "MISSING_DOCUMENT_TRACEABILITY" -> "缺少文档追溯";
            case "MISSING_FUNCTION_SCOPE" -> "缺少功能范围";
            case "MISSING_ROLE_PERMISSION_MATRIX" -> "缺少角色权限矩阵";
            case "MISSING_PROCESS_OR_STATE" -> "缺少流程或状态说明";
            case "MISSING_INPUT_OR_DATA_DICTIONARY" -> "缺少输入或数据字典";
            case "MISSING_BUSINESS_RULE" -> "缺少业务规则";
            case "MISSING_OUTPUT" -> "缺少输出说明";
            case "MISSING_EXCEPTION_HANDLING" -> "缺少异常处理";
            case "MISSING_EXTERNAL_DEPENDENCY" -> "缺少外部依赖说明";
            case "MISSING_SECURITY_OR_AUDIT" -> "缺少安全或审计说明";
            case "MISSING_ENVIRONMENT_OR_CONFIGURATION" -> "缺少环境或配置说明";
            case "CONFLICTING_REQUIREMENT" -> "需求冲突";
            case "AMBIGUOUS_REQUIREMENT" -> "需求表述含糊";
            default -> "问题分类不可用";
        };
    }

    private static String handlingDisplay(String value) {
        if (value == null) return "";
        return switch (value) {
            case "BLOCKING" -> "阻断";
            case "CONTINUE_INCOMPLETE" -> "继续执行但信息不完整";
            case "IMPROVEMENT" -> "改进建议";
            default -> "严重程度不可用";
        };
    }

    private static String structuredProcessingDisplay(String value) {
        return switch (value) {
            case "PENDING" -> "待处理";
            case "RUNNING" -> "处理中";
            case "COMPLETED" -> "已完成";
            case "FAILED" -> "失败";
            case "CANCELLED" -> "已取消";
            default -> throw new IllegalStateException("Unknown structured processing status");
        };
    }

    private static String structuredCoverageDisplay(String value) {
        return switch (value) {
            case "PENDING" -> "正式覆盖待完成";
            case "COMPLETE" -> "正式覆盖完整";
            case "PARTIAL" -> "正式覆盖部分完整";
            case "UNABLE_TO_GENERATE" -> "正式覆盖无法生成";
            default -> throw new IllegalStateException("Unknown structured coverage status");
        };
    }

    private static String confirmationDisplay(String value) {
        return switch (value) {
            case "CONFIRMED" -> "已确认";
            case "PENDING_CONFIRMATION" -> "待确认";
            default -> throw new IllegalStateException("Unknown confirmation status");
        };
    }

    private static String basisDisplay(String value) {
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "FORMAL_REQUIREMENT" -> "正式需求依据";
            case "GENERAL_EXPERIENCE" -> "通用经验依据";
            default -> throw new IllegalStateException("Unknown testcase basis");
        };
    }

    private static String caseStatusDisplay(String value) {
        return switch (value) {
            case "FORMAL" -> "正式用例";
            case "PENDING_CONFIRMATION" -> "待确认用例";
            default -> throw new IllegalStateException("Unknown testcase status");
        };
    }

    private static String reconciliationDisplay(String value) {
        if (value == null) return "核对结论不可用";
        return switch (value) {
            case "EXACT_MATCH" -> "完全一致";
            case "FUNCTION_LIST_ONLY" -> "仅功能清单存在";
            case "REQUIREMENTS_ONLY" -> "仅需求材料存在";
            case "CONFLICT" -> "范围冲突";
            case "DUPLICATE" -> "重复功能";
            case "SPLIT" -> "建议拆分";
            case "MERGE" -> "建议合并";
            case "INSUFFICIENT_EVIDENCE" -> "证据不足";
            default -> "核对结论不可用";
        };
    }

    private record CandidateAuditProjection(String sourceId, String subject, String status, String affectedScope,
            String summary, String severity, List<String> missingInformation) { }

    private record PersistedTestInput(String content, String nature, String source, String method,
            String authenticity, String sequence) { }

    private record PersistedExportStep(int stepNo, String action, String expected, String evaluationCriteria,
            String terminationOrError, String resultCollection) { }

    private static String readerSafeText(String value) {
        String redacted = SensitiveValueRedactor.redact(value == null ? "" : value.strip());
        redacted = STACK_EXCEPTION.matcher(redacted).replaceAll("<internal-stack>");
        return ReaderFacingTextPolicy.sanitize(STACK_FRAME.matcher(redacted).replaceAll("<internal-stack>"));
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String taskFailureSummary(String resultSnapshot) {
        if (resultSnapshot == null || resultSnapshot.isBlank()) return null;
        try {
            return safeTaskFailureSummary(objectMapper.readValue(resultSnapshot, TaskFailureSnapshot.class).failureSummary());
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private static String safeTaskFailureSummary(String failureSummary) {
        if (failureSummary == null || failureSummary.isBlank()) return null;
        return SensitiveValueRedactor.redact(failureSummary.strip());
    }

    private record TaskRow(
            String id,
            GenerationTaskMode taskMode,
            GenerationTaskStatus status,
            String structuredProcessingStatus,
            String structuredCoverageStatus,
            String requestSnapshot,
            String resultSnapshot,
            String artifactId,
            String artifactSha256,
            String failureSummary) {
    }

    private record RetryTaskRow(String taskMode, String status, String requestSnapshot, String resultSnapshot,
            boolean cancellationRequested, String validationErrorCode, String validationErrorPath,
            String validationErrorMessage,
            String processingStatus, String coverageStatus, String workflowVersion, String inputVersion,
            String artifactVersion, String artifactId, String artifactSha256, String artifactPath) { }

    private record RetryWorkRow(
            String id,
            String identityKey,
            String status,
            String coverageStatus,
            String acceptedResultSha256,
            String skillName,
            String operationName,
            String functionKey,
            String testPointKey,
            Integer ordinalStart,
            Integer ordinalEnd,
            String validationErrorCode,
            String validationErrorPath,
            String validationErrorMessage,
            String evidenceJsonType,
            int evidenceKeyCount,
            String evidenceKey,
            boolean hasLease,
            boolean hasCompleteLease,
            Instant leaseExpiresAt,
            boolean hasRunningAttempt,
            int runningAttemptCount,
            int attemptCount) { }

    private record RetryAttemptRow(
            String status, String failureType, String validationErrorCode, String validationErrorPath,
            String validationErrorMessage) { }

    private record V2TechnicalRecoveryAttemptRow(
            int attemptNumber, String status, String failureType, Instant completedAt,
            String validationErrorCode, String validationErrorPath, String validationErrorMessage) { }

    private record FailureArtifactOwner(
            String taskId, String artifactId, String artifactSha256, String artifactPath) { }

    private record StoredValidationDiagnostic(String code, String path, String storageMessage) { }

    private record V2FallbackWorkRow(String id, String identityKey, String functionKey, String testPointKey, String status,
            String acceptedResultSha256, boolean hasLease, boolean hasRunningAttempt) { }

    private record V2ApprovedFunctionRow(String functionKey, String name, String path, String description) { }

    private record V2FallbackProjectionRow(
            String functionName, String testPointType, String basis, String description,
            List<String> pointMissing, boolean pointFormal, String generationOutcome,
            List<String> outcomeMissing, boolean outcomeFormal, String inputSha256, String resultSha256,
            String validatedResultReplayJson) { }

    private record V2FactWorkRow(
            String id, String parentWorkItemId, String status, String skillName, String operationName,
            Integer ordinalStart, Integer ordinalEnd, String materialKey, String materialDocumentId,
            String sourceLabel, List<String> evidenceKeys, List<String> contextEvidenceKeys, String identityKey,
            int splitDepth, String functionKey, String testPointKey,
            String acceptedResultSha256, boolean hasLease, boolean runningAttempt) { }

    private record V2TestcaseProjectionHeader(
            String functionName, String testPointType, String basis, String description,
            List<String> pointMissing, boolean pointFormal, String generationOutcome,
            List<String> outcomeMissing, boolean outcomeFormal, String inputSha256, String resultSha256,
            String validatedResultReplayJson) { }

    private record V2ReplayPublication(
            String publicationType, String inputSha256, String resultSha256, String validatedResultReplayJson) { }

    private record V2TaskFactProjection(
            String firstWorkItemId, String functionKey, String factType, String statement,
            List<StructuredSourceQuoteV2> quotes) { }

    private record V2TestPointRow(
            String functionKey, String testPointKey, String functionName, String testPointType, String basis,
            String description, List<String> pointMissing, boolean pointFormal) { }

    private record V2GenerationOutcomeRow(
            String functionKey, String testPointKey, String generationOutcome,
            List<String> outcomeMissing, boolean outcomeFormal) { }

    private record WorkCaseKey(String workItemId, String caseKey) { }

    private record DocumentUnitKey(String documentId, String unitId) { }

    private record V2EvidenceWindow(List<String> evidenceKeys, List<String> contextEvidenceKeys) { }

    private record BindingLookupKey(
            String workItemId, String subjectKey, String subjectType, String referenceType) { }

    private record FunctionPointKey(String functionKey, String testPointKey) { }

    private record V2TestcaseRecoveryData(
            Map<String, List<RetryAttemptRow>> attemptsByWork,
            Map<String, List<V2ReplayPublication>> publicationsByWork,
            Map<String, List<V2TestPointRow>> testPointsByWork,
            Map<String, List<V2GenerationOutcomeRow>> outcomesByWork,
            Map<FunctionPointKey, V2GenerationPlanner.TestPointPlan> testPointPlans,
            Map<String, List<V2PersistedTestcaseRow>> testcasesByWork,
            Map<WorkCaseKey, List<FunctionalTestcaseDesignV2Result.Step>> stepsByWorkCase,
            Map<BindingLookupKey, List<String>> bindingReferencesByKey,
            Map<String, Integer> bindingCountsByWork,
            Set<String> unexpectedTestcaseWorkIds) { }

    private record V2TestcaseBusinessBatch(
            Map<String, List<V2TestPointRow>> testPointsByWork,
            Map<String, List<V2GenerationOutcomeRow>> outcomesByWork,
            Map<FunctionPointKey, V2GenerationPlanner.TestPointPlan> testPointPlans) { }

    private record V2FactRecoveryData(
            Map<String, V2ApprovedFunctionRow> functionsByKey,
            Map<String, String> documentRoles,
            Map<String, Map<String, RequirementFactExtractionV2Input.MaterialUnit>> unitsByDocument,
            Map<String, List<V2ReplayPublication>> publicationsByWork,
            Map<String, V2TaskFactProjection> factsByKey,
            Map<String, List<String>> firstOwnedFactsByWork,
            Map<String, List<V2PersistedObservationRow>> observationsByWork,
            Set<String> unexpectedFactWorkIds) { }

    private record V2FactBusinessBatch(
            Map<String, V2TaskFactProjection> factsByKey,
            Map<String, List<String>> firstOwnedFactsByWork,
            Map<String, List<V2PersistedObservationRow>> observationsByWork) { }

    private record V2FactProjectionValidation(
            Map<String, Boolean> completeByWork, boolean exactTaskProjection) { }

    private record V2RecoverySnapshot(
            List<V2FactWorkRow> works,
            Map<String, V2ApprovedFunctionRow> functionsByKey,
            Map<String, List<RetryAttemptRow>> attemptsByWork,
            Map<String, List<V2ReplayPublication>> publicationsByWork,
            Map<String, Boolean> completeFactProjections,
            boolean exactTaskFactProjection,
            Map<String, Boolean> completeTestcaseProjections,
            Set<String> allWorkOwnedBusinessIds,
            boolean exactSplitLineage,
            boolean exactFailedLeafEvidence) { }

    private record V2PersistedFactHeader(String functionKey, String factType, String statement) { }

    private record V2PersistedObservationRow(String feedbackKey, String functionKey, String windowKey,
            String observationType, String description, String affectedFactTypes, String evidenceKey, String quote) { }

    private record V2PersistedObservationAccumulator(String observationType, String description,
            List<String> affectedFactTypes, List<StructuredSourceQuoteV2> quotes) { }

    private record V2PersistedFactQuoteRow(
            String factKey, String factType, String statement, String evidenceKey, String quote) { }

    private record V2PersistedFactAccumulator(
            String factType, String statement, List<StructuredSourceQuoteV2> quotes) { }

    private record V2PersistedTestcaseRow(
            String caseKey, String name, String title, String priority, String preconditions,
            String hardware, String software, String testConfiguration, String parameters,
            String inputs, String expectedResults, String evaluationCriteria,
            String resultEvaluationCriteria, String terminationConditions, String resultCollection,
            String caseStatus, String missingInformation) { }

    private record DirectEvidenceRecoveryWork(String id, String functionKey, String validationErrorPath) { }

    /** Keeps the historical overall-expectation recovery projection distinct from direct-evidence recovery. */
    private record ExpectedResultsRecoveryWork(String id, String functionKey, String validationErrorPath) { }

    private record IdentityLabelRecoveryWork(
            String id, String coverageStatus, String validationErrorCode,
            String validationErrorPath, String validationErrorMessage) { }

    private record IdentityLabelRecoveryTaskEnvelope(
            String resultSnapshot, String validationErrorCode,
            String validationErrorPath, String validationErrorMessage) { }

    /** Internal marker for historical JSON that cannot participate in a fail-closed recovery proof. */
    private static final class InvalidV2RecoverySnapshotException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private record RetryReconciliationRunRow(
            String workItemId, String runKey, String catalogSha256, String status, String acceptedResultSha256) { }

    private record RetryReconciliationPageRow(
            String pageKey, String runKey, String catalogSha256, String parentPageKey, String status,
            String completedOwnerSourceRefsJson, String resultSha256, boolean hasCompletedAt) { }

    private record StructuredRetryDecision(
            StructuredRetryEligibility eligibility, String workItemId, StructuredRetryMutation mutation) { }

    /** Internal mutation plan chosen only after advisory or locked retry validation. */
    private enum StructuredRetryMutation {
        REQUEUE_FAILED_WORK,
        REBUILD_INVALID_RECONCILIATION_STAGING,
        RESUME_QUEUED_RESIDUE,
        RESUME_EXPIRED_RUNNING_RESIDUE,
        RESUME_STAGE_GAP,
        RECOVER_V2_ATOMICITY_REJECTION,
        RECOVER_V2_DIRECT_EVIDENCE_REJECTION,
        RECOVER_V2_ZERO_WRITE_TECHNICAL_FAILURE,
        RECOVER_V2_EXPECTED_RESULTS_REJECTION,
        RECOVER_V2_IDENTITY_LABEL_REJECTION
    }

    private record TaskFailureSnapshot(String failureSummary) {
    }

    private record StructuredArtifactBaseline(
            String taskMode, String status, String processingStatus, String artifactId, String sha256, String path) {
    }

    private record BusinessProgressCounts(
            int completeDocuments,
            int totalUnits,
            int processedUnits,
            int totalWork,
            int completedWork,
            int failedWork,
            int pendingFunctionList,
            int pendingRequirementFirstPass,
            int pendingRequirementSecondPass,
            int candidates,
            int coveredCandidates,
            int functionListMissing,
            int requirementMissing,
            int conflicts,
            int splits,
            int merges,
            int insufficientEvidence,
            int frozenFeatures,
            int eligibleFeatures,
            int ineligibleFeatures,
            int acceptedCases) {
    }

    private record AuditWorkRow(
            String id,
            String taskId,
            String documentId,
            String unitId,
            int passNumber,
            String stage) {
    }

    private record FeatureReviewConclusionHeader(
            String id, int sequence, FeatureReviewConclusionType type, String explanation, String evidenceText) {
    }

    public record BatchCounts(int total, int completed, int accepted, int failed) {
    }

    /** Durable terminal decision before a workbook may be published. [Req-ID]: REQ-CAG-004, REQ-CAG-005 */
    public record FinalizationReadiness(GenerationTaskStatus terminalStatus, boolean artifactRequired) {
    }

    /** Durable task-owned counts for the feature-audit completion gate. [Req-ID]: REQ-BFA-001, REQ-BFA-003 */
    public record FeatureAuditCounts(
            int totalWork,
            int completedWork,
            int permanentlyFailedWork,
            int candidateCount,
            int conclusionCount,
            int coveredCandidateCount) {
    }

    public record TaskCreation(String taskId, boolean created) {
    }

    public record PlannedBatch(String batchId, String attemptId, String featureId) {
    }

    public record StoredArtifact(String id, String sha256, Path path) {
    }

    public record TaskExecutionWork(
            String taskId,
            String batchId,
            String attemptId,
            String featureId,
            CreateGenerationTaskRequest request) {
    }
}
