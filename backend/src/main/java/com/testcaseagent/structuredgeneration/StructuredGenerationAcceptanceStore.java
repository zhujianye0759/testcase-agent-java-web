package com.testcaseagent.structuredgeneration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testcaseagent.knowledgeagent.FeatureScopeReconciliationPageInput;
import com.testcaseagent.knowledgeagent.FeatureScopeReconciliationResult;
import com.testcaseagent.knowledgeagent.FeatureScopeReconciliationV2Canonicalizer;
import com.testcaseagent.knowledgeagent.FunctionListExtractionResult;
import com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignV2Input;
import com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignV2Result;
import com.testcaseagent.knowledgeagent.RequirementFactExtractionV2Input;
import com.testcaseagent.knowledgeagent.RequirementFactExtractionV2Result;
import com.testcaseagent.knowledgeagent.StructuredSourceQuoteV2;
import com.testcaseagent.validation.FeatureReconciliationValidator;
import com.testcaseagent.validation.FunctionCandidateExtractionValidator;
import com.testcaseagent.validation.FunctionListExtractionValidator;
import com.testcaseagent.validation.FunctionalTestcaseResultValidator;
import com.testcaseagent.validation.FunctionalTestcaseV2Validator;
import com.testcaseagent.validation.RequirementMaterialReviewValidator;
import com.testcaseagent.validation.RequirementFactV2Validator;
import com.testcaseagent.validation.StructuredKeyType;
import com.testcaseagent.validation.StructuredValidationFailure;
import com.testcaseagent.validation.StructuredValidationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Atomically accepts already-validated structured Skill business rows.
 *
 * <p>This store persists typed projections and reference bindings only; it deliberately has no
 * column or parameter for raw model JSON or Markdown. Validation happens before a transaction can
 * write business rows, and a transaction updates the attempt/work terminal state last.</p>
 *
 * [Req-ID]: REQ-STG-001, REQ-STG-006, REQ-FTG-003, REQ-FTG-007, REQ-FTG-008, REQ-FTG-009
 */
public final class StructuredGenerationAcceptanceStore {
    public static final int MAX_ATTEMPTS = 2;
    private static final Duration LEASE_DURATION = Duration.ofMinutes(5);
    private static final Set<String> FAILURE_TYPES = Set.of("invalid_request", "request_too_large", "session_not_found", "forbidden", "unsupported_skill",
            "skill_unavailable", "model_unavailable", "model_execution_failed", "structured_output_invalid", "response_too_large",
            "scope_validation_failed", "business_validation_failed", "worker_interrupted");
    private static final String RECONCILIATION_V2_OPERATION = "FEATURE_SCOPE_RECONCILIATION_V2";
    private static final Set<String> RECONCILIATION_CLASSIFICATIONS = Set.of(
            "exact_match", "function_list_only", "requirements_only", "conflict", "duplicate", "split", "merge", "insufficient_evidence");
    private static final Set<String> RECONCILIATION_CONFIRMATION_STATUSES = Set.of("confirmed", "pending_confirmation");
    private static final List<String> FALLBACK_FORBIDDEN_WORK_OWNED_TABLES = List.of(
            "structured_requirement_fact",
            "structured_review_finding",
            "structured_function_list_item",
            "structured_feature_reconciliation",
            "structured_test_case",
            "structured_test_case_step",
            "structured_reference_binding",
            "structured_function_source_outcome",
            "structured_function_candidate",
            "structured_function_outcome_candidate",
            "structured_reconciliation_run",
            "structured_reconciliation_page_stage",
            "structured_reconciliation_relation_stage",
            "structured_reconciliation_relation_stage_binding",
            "structured_reconciliation_source_terminal",
            "v2_testability_feedback");
    private static final Comparator<ReconciliationSourceRef> SOURCE_REF_ORDER = Comparator
            .comparingInt((ReconciliationSourceRef ref) -> "function_list_item".equals(ref.sourceType()) ? 0 : 1)
            .thenComparing(ReconciliationSourceRef::sourceKey, StructuredGenerationAcceptanceStore::compareUtf8);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    /** Creates the application-owned store; callers provide the existing transaction manager boundary. */
    public StructuredGenerationAcceptanceStore(JdbcTemplate jdbc, TransactionTemplate transaction, Clock clock) {
        this(jdbc, transaction, clock, new ObjectMapper());
    }

    /** Uses the application mapper to persist field-level arrays and derive an accepted-result hash. */
    public StructuredGenerationAcceptanceStore(JdbcTemplate jdbc, TransactionTemplate transaction, Clock clock, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.transaction = Objects.requireNonNull(transaction, "transaction must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /** Registers one stable work identity without changing an already completed work item. */
    public String register(WorkRegistration registration) {
        WorkRegistration item = Objects.requireNonNull(registration, "registration must not be null");
        return transaction.execute(status -> registerLocked(item));
    }

    private String registerLocked(WorkRegistration item) {
        validateMaterialWindow(item);
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO structured_generation_work_item
                (id, task_id, identity_key, skill_name, operation_name, status, ordinal_start, ordinal_end,
                 material_key, material_document_id, source_label, allowed_evidence_keys_json,
                 context_evidence_keys_json, parent_work_item_id, split_depth, function_key, test_point_key)
                VALUES (?, ?, ?, ?, ?, 'QUEUED', ?, ?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON), ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE id = id
                """, id, item.taskId(), item.identityKey(), item.skillName(), item.operationName(), item.ordinalStart(), item.ordinalEnd(),
                item.materialKey(), item.materialDocumentId(), item.sourceLabel(), json(item.allowedEvidenceKeys()),
                item.contextEvidenceKeys().isEmpty() ? null : json(item.contextEvidenceKeys()),
                item.parentWorkItemId(), item.splitDepth(), item.functionKey(), item.testPointKey());
        List<RegistrationRow> rows = jdbc.query("""
                SELECT id, skill_name, operation_name, ordinal_start, ordinal_end, material_key,
                       material_document_id, source_label, allowed_evidence_keys_json, context_evidence_keys_json,
                       parent_work_item_id, split_depth, function_key, test_point_key
                FROM structured_generation_work_item WHERE task_id = ? AND identity_key = ? FOR UPDATE
                """, (row, ignored) -> new RegistrationRow(row.getString("id"), row.getString("skill_name"), row.getString("operation_name"),
                asInteger(row.getObject("ordinal_start")), asInteger(row.getObject("ordinal_end")), row.getString("material_key"),
                row.getString("material_document_id"), row.getString("source_label"),
                stringList(row.getString("allowed_evidence_keys_json")),
                stringList(row.getString("context_evidence_keys_json")), row.getString("parent_work_item_id"),
                row.getInt("split_depth"), row.getString("function_key"), row.getString("test_point_key")),
                item.taskId(), item.identityKey());
        RegistrationRow stored = rows.get(0);
        if (!stored.matches(item)) throw new IllegalStateException("Work identity conflicts with frozen registration coordinates");
        return stored.id();
    }

    /**
     * Registers the planner's exact no-fact point and, after explicit atomicity recovery, restores only its fully
     * audited prior projection. The generic registration path cannot revive historical work. [Req-ID]: REQ-TGV2-011
     */
    public String registerMissingFactFallback(V2GenerationPlanner.TestPointPlan plan) {
        V2GenerationPlanner.TestPointPlan checked = Objects.requireNonNull(plan, "plan must not be null");
        return transaction.execute(status -> {
            String workItemId = registerLocked(checked.registration());
            reactivateExactMissingFactFallback(workItemId, checked);
            return workItemId;
        });
    }

    private void reactivateExactMissingFactFallback(
            String workItemId, V2GenerationPlanner.TestPointPlan plan) {
        WorkRegistration item = plan.registration();
        if (!"functional-testcase-design".equals(item.skillName())
                || !"FUNCTIONAL_TESTCASE_DESIGN_V2".equals(item.operationName())
                || item.functionKey() == null || item.testPointKey() == null
                || !V2GenerationPlanner.missingFormalFactPointKey(item.taskId(), item.functionKey())
                        .equals(item.testPointKey())
                || !plan.input().functionKey().equals(item.functionKey())
                || !plan.input().testPoint().testPointKey().equals(item.testPointKey())
                || !plan.input().requirementFacts().isEmpty()) {
            throw new IllegalArgumentException("Only the planner's exact no-fact fallback can use this registration path");
        }
        List<ReactivationRow> rows = jdbc.query("""
                SELECT status, accepted_result_sha256, coverage_status,
                       (lease_owner IS NOT NULL OR lease_expires_at IS NOT NULL) AS has_lease
                FROM structured_generation_work_item
                WHERE id=? AND task_id=? FOR UPDATE
                """, (row, ignored) -> new ReactivationRow(
                        row.getString("status"), row.getString("accepted_result_sha256"),
                        row.getString("coverage_status"), row.getBoolean("has_lease")),
                workItemId, item.taskId());
        if (rows.size() != 1 || !"SUPERSEDED".equals(rows.get(0).status())) return;
        ReactivationRow work = rows.get(0);
        List<ReactivationProjectionRow> projections = jdbc.query("""
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
                WHERE point.work_item_id=? AND point.task_id=? AND point.test_point_key=? AND point.function_key=?
                FOR UPDATE
                """, (row, ignored) -> new ReactivationProjectionRow(
                        row.getString("function_name"), row.getString("test_point_type"), row.getString("basis"),
                        row.getString("description"), stringList(row.getString("point_missing")),
                        row.getBoolean("point_formal"), row.getString("generation_outcome"),
                        stringList(row.getString("outcome_missing")), row.getBoolean("outcome_formal"),
                        row.getString("input_sha256"), row.getString("result_sha256"),
                        row.getString("validated_result_replay_json")),
                workItemId, item.taskId(), item.testPointKey(), item.functionKey());
        ReactivationProjectionRow projection = projections.size() == 1 ? projections.get(0) : null;
        // The public V2 result may provide a more specific non-empty explanation than the planner hint. Rebuild and
        // revalidate that persisted result before trusting its hash; arbitrary row/hash edits still fail closed.
        FunctionalTestcaseDesignV2Result replay = null;
        boolean persistedResultValid = false;
        if (projection != null) {
            try {
                replay = new FunctionalTestcaseDesignV2Result(
                        item.functionKey(), item.testPointKey(),
                        FunctionalTestcaseDesignV2Result.GenerationOutcome.UNABLE_TO_GENERATE,
                        projection.outcomeMissing(), List.of());
                FunctionalTestcaseV2Validator validator = new FunctionalTestcaseV2Validator();
                validator.validate(plan.input(), replay);
                // V23 replay is part of the closed projection once present. A coherently edited outcome/hash pair
                // cannot supersede the exact public result that Java originally validated.
                if (projection.validatedResultReplayJson() != null
                        && !projection.validatedResultReplayJson().isBlank()) {
                    FunctionalTestcaseDesignV2Result original = objectMapper.readValue(
                            projection.validatedResultReplayJson(), FunctionalTestcaseDesignV2Result.class);
                    if (original == null || !projection.resultSha256().equals(sha256(original))
                            || !replay.equals(original)) {
                        throw new IllegalArgumentException("Validated fallback replay no longer matches projection");
                    }
                    validator.validate(plan.input(), original);
                }
                persistedResultValid = true;
            } catch (IllegalArgumentException | JsonProcessingException invalidPersistedResult) {
                // A corrupted historical projection is not eligible for automatic reactivation.
            }
        }
        boolean exact = work.acceptedResultSha256() != null && !work.hasLease()
                && "NOT_APPLICABLE".equals(work.coverageStatus())
                && projection != null
                && persistedResultValid
                && plan.input().functionName().equals(projection.functionName())
                && plan.input().testPoint().type().wireValue().equals(projection.testPointType())
                && plan.input().testPoint().basis().wireValue().equals(projection.basis())
                && plan.input().testPoint().description().equals(projection.description())
                && plan.input().testPoint().missingInformation().equals(projection.pointMissing())
                && !projection.pointFormal()
                && "unable_to_generate".equals(projection.generationOutcome())
                && !projection.outcomeMissing().isEmpty() && !projection.outcomeFormal()
                && sha256(plan.input()).equals(projection.inputSha256())
                && sha256(replay).equals(projection.resultSha256())
                && work.acceptedResultSha256().equals(projection.resultSha256())
                && countRows("SELECT COUNT(*) FROM structured_generation_attempt WHERE work_item_id=? AND status='RUNNING'", workItemId) == 0
                && countRows("SELECT COUNT(*) FROM v2_requirement_fact WHERE task_id=? AND function_key=?",
                        item.taskId(), item.functionKey()) == 0
                && countRows("SELECT COUNT(*) FROM v2_requirement_fact WHERE first_work_item_id=?", workItemId) == 0
                && countRows("""
                        SELECT COUNT(*) FROM generation_task
                        WHERE id=? AND structured_processing_status='RUNNING'
                          AND structured_coverage_status='PENDING'
                          AND workflow_version='2.0' AND input_version='2.0' AND artifact_version='2.0'
                          AND artifact_id IS NULL AND artifact_sha256 IS NULL AND artifact_path IS NULL
                        """, item.taskId()) == 1
                && countRows("SELECT COUNT(*) FROM structured_test_point WHERE work_item_id=?", workItemId) == 1
                && countRows("SELECT COUNT(*) FROM v2_generation_outcome WHERE work_item_id=?", workItemId) == 1
                && countRows("SELECT COUNT(*) FROM v2_work_publication WHERE work_item_id=?", workItemId) == 1
                && countRows("SELECT COUNT(*) FROM structured_test_case WHERE work_item_id=?", workItemId) == 0
                && countRows("SELECT COUNT(*) FROM structured_test_case_step WHERE work_item_id=?", workItemId) == 0
                && countRows("SELECT COUNT(*) FROM structured_reference_binding WHERE work_item_id=?", workItemId) == 0
                && !hasUnexpectedMissingFactFallbackRows(workItemId);
        if (!exact) {
            throw new IllegalStateException("Superseded no-fact fallback no longer matches its audited projection");
        }
        if (jdbc.update("""
                UPDATE structured_generation_work_item SET status='COMPLETED'
                WHERE id=? AND task_id=? AND status='SUPERSEDED' AND accepted_result_sha256=?
                """, workItemId, item.taskId(), work.acceptedResultSha256()) != 1) {
            throw new IllegalStateException("Superseded no-fact fallback changed during reactivation");
        }
    }

    private boolean hasUnexpectedMissingFactFallbackRows(String workItemId) {
        // The exact fallback projection consists only of one test point, one V2 outcome and one publication. The work
        // row lock above also makes this closed-world check safe against concurrent child-row insertion.
        for (String table : FALLBACK_FORBIDDEN_WORK_OWNED_TABLES) {
            if (countRows("SELECT COUNT(*) FROM " + table + " WHERE work_item_id=?", workItemId) != 0) return true;
        }
        return false;
    }

    private void validateMaterialWindow(WorkRegistration item) {
        if (item.materialDocumentId() == null) {
            if (!item.contextEvidenceKeys().isEmpty() || item.parentWorkItemId() != null || item.splitDepth() != 0) {
                throw new IllegalArgumentException("Historical material work cannot carry context or split lineage without a document");
            }
            return;
        }
        if (!Set.of("REQUIREMENT_MATERIAL_REVIEW", "FEATURE_SCOPE_EXTRACT",
                "REQUIREMENT_FACT_EXTRACTION_V2").contains(item.operationName())) {
            throw new IllegalArgumentException("Only a material-scoped operation may carry a material document plan");
        }
        if (item.ordinalStart() == null || item.ordinalEnd() == null || item.allowedEvidenceKeys().isEmpty()) {
            throw new IllegalArgumentException("A semantic material window requires a target ordinal range and evidence");
        }
        if (item.allowedEvidenceKeys().size() + item.contextEvidenceKeys().size() > 32) {
            throw new IllegalArgumentException("Target and context evidence must contain at most 32 units");
        }
        if (new HashSet<>(item.allowedEvidenceKeys()).size() != item.allowedEvidenceKeys().size()
                || new HashSet<>(item.contextEvidenceKeys()).size() != item.contextEvidenceKeys().size()
                || item.contextEvidenceKeys().stream().anyMatch(item.allowedEvidenceKeys()::contains)) {
            throw new IllegalArgumentException("Target and context evidence must be unique and non-overlapping");
        }
        List<InventoryUnitKey> inventory = jdbc.query("""
                SELECT unit_id, ordinal FROM material_inventory_unit
                WHERE task_id = ? AND document_id = ? ORDER BY ordinal, unit_id
                """, (row, ignored) -> new InventoryUnitKey(row.getString("unit_id"), row.getInt("ordinal")),
                item.taskId(), item.materialDocumentId());
        if (inventory.isEmpty()) throw new IllegalArgumentException("Material document is not in the frozen inventory");
        int targetStart = indexOfOrdinal(inventory, item.ordinalStart());
        int targetEnd = indexOfOrdinal(inventory, item.ordinalEnd());
        if (targetStart < 0 || targetEnd < targetStart) {
            throw new IllegalArgumentException("Target ordinals are not in the frozen material inventory");
        }
        List<String> frozenTarget = inventory.subList(targetStart, targetEnd + 1).stream()
                .map(InventoryUnitKey::unitId).toList();
        if (!frozenTarget.equals(item.allowedEvidenceKeys())) {
            throw new IllegalArgumentException("Target evidence must exactly match the frozen ordinal window");
        }
        List<Integer> contextIndexes = new ArrayList<>();
        for (String contextKey : item.contextEvidenceKeys()) {
            int index = indexOfUnit(inventory, contextKey);
            if (index < 0) throw new IllegalArgumentException("Context evidence is outside the frozen material document");
            contextIndexes.add(index);
        }
        if (!contextIndexes.equals(contextIndexes.stream().sorted().toList())) {
            throw new IllegalArgumentException("Context evidence must retain frozen ordinal order");
        }
        List<Integer> before = contextIndexes.stream().filter(index -> index < targetStart).toList();
        List<Integer> after = contextIndexes.stream().filter(index -> index > targetEnd).toList();
        if (before.size() + after.size() != contextIndexes.size()
                || !before.equals(java.util.stream.IntStream.range(targetStart - before.size(), targetStart).boxed().toList())
                || !after.equals(java.util.stream.IntStream.range(targetEnd + 1, targetEnd + 1 + after.size()).boxed().toList())
                || (!before.isEmpty() && before.get(0) < 0)
                || (!after.isEmpty() && after.get(after.size() - 1) >= inventory.size())) {
            throw new IllegalArgumentException("Context evidence must be immediately adjacent to the target window");
        }
        if (item.parentWorkItemId() == null) {
            if (item.splitDepth() != 0) throw new IllegalArgumentException("Root material work must have splitDepth zero");
            return;
        }
        if (item.splitDepth() < 1) throw new IllegalArgumentException("Split child depth must be positive");
        List<ParentWindowRow> parents = jdbc.query("""
                SELECT task_id, operation_name, material_key, material_document_id, split_depth
                FROM structured_generation_work_item WHERE id = ? FOR UPDATE
                """, (row, ignored) -> new ParentWindowRow(row.getString("task_id"), row.getString("operation_name"),
                row.getString("material_key"), row.getString("material_document_id"), row.getInt("split_depth")),
                item.parentWorkItemId());
        if (parents.size() != 1 || !parents.get(0).matches(item)) {
            throw new IllegalArgumentException("Split lineage does not match the frozen parent material work");
        }
    }

    private static int indexOfOrdinal(List<InventoryUnitKey> inventory, int ordinal) {
        for (int index = 0; index < inventory.size(); index++) {
            if (inventory.get(index).ordinal() == ordinal) return index;
        }
        return -1;
    }

    private static int indexOfUnit(List<InventoryUnitKey> inventory, String unitId) {
        for (int index = 0; index < inventory.size(); index++) {
            if (inventory.get(index).unitId().equals(unitId)) return index;
        }
        return -1;
    }

    /** Claims at most one queued or failed work item and creates its next running attempt. */
    public Optional<WorkClaim> claimNext(String taskId, String owner) {
        require(taskId, "taskId"); require(owner, "owner");
        return transaction.execute(status -> {
            Instant now = clock.instant();
            List<String> expired = jdbc.query("""
                    SELECT id FROM structured_generation_work_item
                    WHERE task_id = ? AND status = 'RUNNING' AND lease_expires_at <= ?
                    ORDER BY lease_expires_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """, (row, ignored) -> row.getString(1), taskId, now);
            if (!expired.isEmpty()) {
                String expiredId = expired.get(0);
                jdbc.update("UPDATE structured_generation_attempt SET status = 'FAILED', failure_type = 'worker_interrupted', completed_at = ? "
                        + "WHERE work_item_id = ? AND status = 'RUNNING'", now, expiredId);
                jdbc.update("UPDATE structured_generation_work_item SET status = 'FAILED', lease_owner = NULL, lease_expires_at = NULL WHERE id = ?", expiredId);
            }
            List<WorkRow> rows = jdbc.query("""
                    SELECT id, identity_key, skill_name, operation_name, ordinal_start, ordinal_end, material_key,
                           material_document_id, allowed_evidence_keys_json, context_evidence_keys_json,
                           parent_work_item_id, split_depth
                    FROM structured_generation_work_item
                    WHERE task_id = ? AND (status = 'QUEUED' OR (status = 'FAILED'
                      AND (SELECT COUNT(*) FROM structured_generation_attempt a
                           WHERE a.work_item_id = structured_generation_work_item.id
                             AND (a.failure_type IS NULL OR a.failure_type <> 'worker_interrupted')) < 2
                      AND (SELECT a.failure_type FROM structured_generation_attempt a WHERE a.work_item_id = structured_generation_work_item.id
                           ORDER BY a.attempt_number DESC LIMIT 1)
                           IN ('model_unavailable', 'model_execution_failed', 'worker_interrupted')))
                    ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """, (row, ignored) -> new WorkRow(row.getString("id"), row.getString("identity_key"), row.getString("skill_name"),
                    row.getString("operation_name"), asInteger(row.getObject("ordinal_start")), asInteger(row.getObject("ordinal_end")),
                    row.getString("material_key"), row.getString("material_document_id"),
                    stringList(row.getString("allowed_evidence_keys_json")),
                    stringList(row.getString("context_evidence_keys_json")), row.getString("parent_work_item_id"),
                    row.getInt("split_depth")), taskId);
            if (rows.isEmpty()) return Optional.empty();
            WorkRow row = rows.get(0);
            int attemptNumber = jdbc.queryForObject("SELECT COALESCE(MAX(attempt_number), 0) + 1 FROM structured_generation_attempt WHERE work_item_id = ?",
                    Integer.class, row.id());
            String attemptId = UUID.randomUUID().toString();
            Instant expires = clock.instant().plus(LEASE_DURATION);
            if (jdbc.update("""
                    UPDATE structured_generation_work_item SET status = 'RUNNING', lease_owner = ?, lease_expires_at = ?
                    WHERE id = ? AND status IN ('QUEUED', 'FAILED')
                    """, owner, expires, row.id()) != 1) throw new IllegalStateException("Structured work claim was lost");
            jdbc.update("INSERT INTO structured_generation_attempt (id, work_item_id, attempt_number, status) VALUES (?, ?, ?, 'RUNNING')",
                    attemptId, row.id(), attemptNumber);
            return Optional.of(new WorkClaim(row.id(), attemptId, taskId, row.identityKey(), row.skillName(), row.operationName(), attemptNumber,
                    row.ordinalStart(), row.ordinalEnd(), row.materialKey(), row.allowedEvidenceKeys(), owner,
                    row.materialDocumentId(), row.contextEvidenceKeys(), row.parentWorkItemId(), row.splitDepth()));
        });
    }

    /**
     * Claims one exact registered identity for its first attempt or its single allowed transient retry.
     *
     * <p>The coordinator uses this targeted form so retrying one failed Skill call cannot accidentally lease a
     * different queued work item.</p>
     */
    public Optional<WorkClaim> claimRegistered(String taskId, String workItemId, String owner) {
        require(taskId, "taskId"); require(workItemId, "workItemId"); require(owner, "owner");
        return transaction.execute(status -> {
            Instant now = clock.instant();
            List<TargetWorkRow> rows = jdbc.query("""
                    SELECT id, identity_key, skill_name, operation_name, status, ordinal_start, ordinal_end,
                           material_key, material_document_id, allowed_evidence_keys_json, context_evidence_keys_json,
                           parent_work_item_id, split_depth, lease_expires_at
                    FROM structured_generation_work_item
                    WHERE task_id = ? AND id = ? FOR UPDATE
                    """, (row, ignored) -> new TargetWorkRow(row.getString("id"), row.getString("identity_key"),
                    row.getString("skill_name"), row.getString("operation_name"), row.getString("status"),
                    asInteger(row.getObject("ordinal_start")), asInteger(row.getObject("ordinal_end")),
                    row.getString("material_key"), row.getString("material_document_id"),
                    stringList(row.getString("allowed_evidence_keys_json")),
                    stringList(row.getString("context_evidence_keys_json")), row.getString("parent_work_item_id"),
                    row.getInt("split_depth"),
                    row.getTimestamp("lease_expires_at") == null ? null : row.getTimestamp("lease_expires_at").toInstant()),
                    taskId, workItemId);
            if (rows.isEmpty()) return Optional.empty();
            TargetWorkRow row = rows.get(0);
            String currentStatus = row.status();
            if ("RUNNING".equals(currentStatus) && row.leaseExpiresAt() != null
                    && !row.leaseExpiresAt().isAfter(now)) {
                jdbc.update("UPDATE structured_generation_attempt SET status = 'FAILED', failure_type = 'worker_interrupted', completed_at = ? "
                        + "WHERE work_item_id = ? AND status = 'RUNNING'", now, workItemId);
                jdbc.update("UPDATE structured_generation_work_item SET status = 'FAILED', lease_owner = NULL, lease_expires_at = NULL WHERE id = ?",
                        workItemId);
                currentStatus = "FAILED";
            }
            int attempts = count("SELECT COUNT(*) FROM structured_generation_attempt WHERE work_item_id = ?", workItemId);
            int budgetedAttempts = count("""
                    SELECT COUNT(*) FROM structured_generation_attempt
                    WHERE work_item_id = ? AND (failure_type IS NULL OR failure_type <> 'worker_interrupted')
                    """, workItemId);
            if ("FAILED".equals(currentStatus)) {
                List<String> failures = jdbc.queryForList("""
                        SELECT failure_type FROM structured_generation_attempt
                        WHERE work_item_id = ? ORDER BY attempt_number DESC LIMIT 1
                        """, String.class, workItemId);
                if (budgetedAttempts >= MAX_ATTEMPTS || failures.isEmpty()
                        || !("model_unavailable".equals(failures.get(0))
                        || "model_execution_failed".equals(failures.get(0))
                        || "worker_interrupted".equals(failures.get(0)))) return Optional.empty();
            } else if (!"QUEUED".equals(currentStatus)) {
                return Optional.empty();
            }
            int attemptNumber = attempts + 1;
            String attemptId = UUID.randomUUID().toString();
            Instant expires = now.plus(LEASE_DURATION);
            if (jdbc.update("""
                    UPDATE structured_generation_work_item SET status = 'RUNNING', lease_owner = ?, lease_expires_at = ?
                    WHERE id = ? AND status = ?
                    """, owner, expires, workItemId, currentStatus) != 1) {
                throw new IllegalStateException("Structured work claim was lost");
            }
            jdbc.update("INSERT INTO structured_generation_attempt (id, work_item_id, attempt_number, status) VALUES (?, ?, ?, 'RUNNING')",
                    attemptId, workItemId, attemptNumber);
            return Optional.of(new WorkClaim(row.id(), attemptId, taskId, row.identityKey(), row.skillName(),
                    row.operationName(), attemptNumber, row.ordinalStart(), row.ordinalEnd(), row.materialKey(),
                    row.allowedEvidenceKeys(), owner, row.materialDocumentId(), row.contextEvidenceKeys(),
                    row.parentWorkItemId(), row.splitDepth()));
        });
    }

    /**
     * Returns whether a failed model call may consume another model-attempt slot.
     *
     * <p>Physical attempts include host shutdowns and lease recovery. Those interruptions remain in the audit trail,
     * but they must not shorten the bounded model retry budget. The coordinator asks this only after persisting the
     * current failure, so the count includes the call that just ended.</p>
     * [Req-ID]: REQ-TGV2-006, REQ-TGV2-008
     */
    public boolean hasRemainingModelAttemptBudget(String workItemId) {
        require(workItemId, "workItemId");
        return count("""
                SELECT COUNT(*) FROM structured_generation_attempt
                WHERE work_item_id = ? AND (failure_type IS NULL OR failure_type <> 'worker_interrupted')
                """, workItemId) < MAX_ATTEMPTS;
    }

    /**
     * Reads durable material-window coordinates without model output. A coordinator uses these rows to resume only
     * unfinished leaves; nullable V17 fields identify historical registrations that predate semantic context.
     * [Req-ID]: REQ-FTG-013
     */
    public List<MaterialWindowPlan> materialWindowPlans(String taskId, String operationName, String materialKey) {
        require(taskId, "taskId");
        require(operationName, "operationName");
        require(materialKey, "materialKey");
        return jdbc.query("""
                SELECT id, identity_key, status, ordinal_start, ordinal_end, material_document_id,
                       allowed_evidence_keys_json, context_evidence_keys_json, parent_work_item_id, split_depth
                FROM structured_generation_work_item
                WHERE task_id = ? AND operation_name = ? AND material_key = ?
                ORDER BY ordinal_start, split_depth, id
                """, (row, ignored) -> new MaterialWindowPlan(row.getString("id"), row.getString("identity_key"),
                row.getString("status"), asInteger(row.getObject("ordinal_start")), asInteger(row.getObject("ordinal_end")),
                row.getString("material_document_id"), stringList(row.getString("allowed_evidence_keys_json")),
                stringList(row.getString("context_evidence_keys_json")), row.getString("parent_work_item_id"),
                row.getInt("split_depth")), taskId, operationName, materialKey);
    }

    /** Validates and atomically saves a material-review projection. */
    public void acceptReview(WorkClaim claim, RequirementMaterialReviewValidator validator,
            RequirementMaterialReviewValidator.WorkItem workItem, RequirementMaterialReviewValidator.Result result) {
        requireTask(claim, workItem.registry().taskId());
        Objects.requireNonNull(validator, "validator must not be null").validate(workItem, result);
        accept(claim, result, null, "REQUIREMENT_MATERIAL_REVIEW", frozen -> {
            if (!workItem.materialKey().equals(frozen.materialKey())
                    || !workItem.allowedEvidenceKeys().equals(frozen.allowedEvidenceKeys())) {
                throw new IllegalStateException("Review validation closure does not match frozen work");
            }
            for (RequirementMaterialReviewValidator.RequirementFact fact : result.requirementFacts()) {
                insertTaskScoped("requirement fact", () -> jdbc.update("""
                        INSERT INTO structured_requirement_fact (work_item_id, task_id, fact_key, function_name, roles_json,
                        trigger_conditions_json, inputs_json, business_rules_json, outputs_json, permissions_json, state_changes_json,
                        exception_handling_json, external_dependencies_json) VALUES (?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON),
                        CAST(? AS JSON), CAST(? AS JSON), CAST(? AS JSON), CAST(? AS JSON), CAST(? AS JSON), CAST(? AS JSON), CAST(? AS JSON))""",
                        claim.workItemId(), claim.taskId(), fact.factKey(), fact.function(), json(fact.roles()), json(fact.triggerConditions()), json(fact.inputs()),
                        json(fact.businessRules()), json(fact.outputs()), json(fact.permissions()), json(fact.stateChanges()),
                        json(fact.exceptionHandling()), json(fact.externalDependencies())));
                bind(claim.workItemId(), fact.factKey(), "REQUIREMENT_FACT", "EVIDENCE", fact.evidenceKeys());
            }
            for (RequirementMaterialReviewValidator.ReviewFinding finding : result.reviewFindings()) {
                persistReviewFinding(claim, finding);
            }
        });
        for (RequirementMaterialReviewValidator.RequirementFact fact : result.requirementFacts()) publishKey(workItem.registry(), StructuredKeyType.REQUIREMENT_FACT, fact.factKey());
        for (RequirementMaterialReviewValidator.ReviewFinding finding : result.reviewFindings()) publishKey(workItem.registry(), StructuredKeyType.REVIEW_FINDING, finding.findingKey());
    }

    /** Validates and atomically saves a feature-reconciliation projection. */
    public void acceptReconciliation(WorkClaim claim, FeatureReconciliationValidator validator,
            FeatureReconciliationValidator.WorkItem workItem, FeatureReconciliationValidator.Result result) {
        requireTask(claim, workItem.registry().taskId());
        Objects.requireNonNull(validator, "validator must not be null").validate(workItem, result);
        accept(claim, result, null, "FEATURE_SCOPE_RECONCILIATION", frozen -> {
            if (!workItem.allowedEvidenceKeys().equals(frozen.allowedEvidenceKeys())) {
                throw new IllegalStateException("Reconciliation evidence closure does not match frozen work");
            }
            for (FeatureReconciliationValidator.Reconciliation row : result.reconciliations()) {
                insertTaskScoped("feature reconciliation", () -> jdbc.update("""
                        INSERT INTO structured_feature_reconciliation
                        (work_item_id, task_id, reconciliation_key, classification, scope_recommendation, confirmation_status)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, claim.workItemId(), claim.taskId(), row.reconciliationKey(), row.classification().name(),
                        row.scopeRecommendation(), row.confirmationStatus().name()));
                bind(claim.workItemId(), row.reconciliationKey(), "RECONCILIATION", "FUNCTION_LIST_ITEM", row.functionListItemKeys());
                bind(claim.workItemId(), row.reconciliationKey(), "RECONCILIATION", "REQUIREMENT_FACT", row.requirementFactKeys());
                bind(claim.workItemId(), row.reconciliationKey(), "RECONCILIATION", "EVIDENCE", row.evidenceKeys());
            }
        });
        for (FeatureReconciliationValidator.Reconciliation row : result.reconciliations()) publishKey(workItem.registry(), StructuredKeyType.RECONCILIATION, row.reconciliationKey());
    }

    /** Persists Java-keyed function-list items; their registry identities become visible only after commit. */
    public void acceptFunctionListItems(WorkClaim claim, StructuredValidationRegistry registry, List<FunctionListItem> items) {
        requireTask(claim, Objects.requireNonNull(registry, "registry must not be null").taskId());
        List<FunctionListItem> checked = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        if (checked.size() > 200) throw new IllegalArgumentException("function list items must contain 0..200 rows");
        java.util.HashSet<String> keys = new java.util.HashSet<>();
        for (FunctionListItem item : checked) {
            FunctionListItem row = Objects.requireNonNull(item, "function list item must not be null");
            require(row.itemKey(), "itemKey"); require(row.path(), "path"); require(row.description(), "description");
            if (!keys.add(row.itemKey())) throw new IllegalArgumentException("itemKey must be unique");
            List.copyOf(Objects.requireNonNull(row.evidenceKeys(), "evidenceKeys must not be null"));
            if (row.targetQuotes().isEmpty()) throw new IllegalArgumentException("targetQuotes must not be empty");
            row.targetQuotes().forEach(quote -> require(quote, "targetQuote"));
        }
        accept(claim, checked, null, "FEATURE_SCOPE_EXTRACT", frozen -> {
            for (FunctionListItem row : checked) {
                require(frozen.materialKey(), "frozen materialKey");
                java.util.HashSet<String> evidenceKeys = new java.util.HashSet<>();
                for (String evidenceKey : row.evidenceKeys()) {
                    require(evidenceKey, "evidenceKey");
                    if (!evidenceKeys.add(evidenceKey)) throw new IllegalArgumentException("evidenceKeys must be unique");
                    if (!frozen.allowedEvidenceKeys().contains(evidenceKey)) throw new IllegalArgumentException("Evidence is outside the current parsed-unit slice");
                    registry.requireEvidence(evidenceKey, frozen.materialKey());
                }
                String ownerWorkItemId = functionItemOwner(claim, row);
                bindIdempotently(ownerWorkItemId, row.itemKey(), "FUNCTION_LIST_ITEM", "EVIDENCE", row.evidenceKeys());
            }
        });
        for (FunctionListItem row : checked) publishKey(registry, StructuredKeyType.FUNCTION_LIST_ITEM, row.itemKey());
    }

    /**
     * Validates and publishes one V2 fact window, merging the same Java fact identity across windows.
     * The work ledger and all fact/quote/feedback rows commit together; no raw KEE envelope is persisted.
     * [Req-ID]: REQ-TGV2-004, REQ-TGV2-008, REQ-TGV2-012
     */
    public void acceptRequirementFactsV2(WorkClaim claim, RequirementFactV2Validator validator,
            RequirementFactExtractionV2Input input, RequirementFactExtractionV2Result result) {
        RequirementFactV2Validator.AcceptedWindow accepted = Objects.requireNonNull(validator,
                "validator must not be null").validate(input, result);
        String inputSha256 = sha256(input);
        String resultSha256 = sha256(result);
        accept(claim, result, null, "REQUIREMENT_FACT_EXTRACTION_V2", frozen -> {
            if (!input.functionKey().equals(frozen.functionKey())
                    || !input.materialKey().equals(frozen.materialKey())
                    || !input.windowKey().equals(frozen.identityKey())
                    || !input.units().stream().map(RequirementFactExtractionV2Input.MaterialUnit::unitKey).toList()
                            .equals(frozen.allowedEvidenceKeys())
                    || !input.contextUnits().stream().map(RequirementFactExtractionV2Input.MaterialUnit::unitKey).toList()
                            .equals(frozen.contextEvidenceKeys())) {
                throw new IllegalStateException("V2 fact result does not match its frozen work window");
            }
            for (RequirementFactV2Validator.AcceptedFact fact : accepted.facts()) {
                persistV2Fact(claim, input.functionKey(), fact);
            }
            for (RequirementFactV2Validator.AcceptedObservation observation : accepted.observations()) {
                persistV2Feedback(claim, input.functionKey(), input.windowKey(), observation);
            }
            insertV2Publication(claim, "requirement_facts", inputSha256, resultSha256, result);
        });
    }

    /**
     * Reconstructs Java-owned V2 facts from committed rows for restart-safe test-point planning.
     * Exact quotations are ordered deterministically and raw KEE envelopes are never consulted.
     * [Req-ID]: REQ-TGV2-004, REQ-TGV2-008
     */
    public List<V2GenerationPlanner.PersistedFact> acceptedRequirementFactsV2(
            String taskId, String functionKey) {
        require(taskId, "taskId");
        require(functionKey, "functionKey");
        List<V2GenerationPlanner.PersistedFact> facts = new ArrayList<>();
        String afterFactKey = "";
        List<V2GenerationPlanner.PersistedFact> page;
        do {
            page = acceptedRequirementFactsV2Page(taskId, functionKey, afterFactKey, 100);
            facts.addAll(page);
            if (!page.isEmpty()) afterFactKey = page.get(page.size() - 1).factKey();
        } while (page.size() == 100);
        return List.copyOf(facts);
    }

    /**
     * Reads one bounded fact page and its quotes in one query, avoiding an unbounded function-wide materialization
     * and per-fact quote queries in the generation coordinator. [Req-ID]: REQ-TGV2-005, REQ-TGV2-008
     */
    public List<V2GenerationPlanner.PersistedFact> acceptedRequirementFactsV2Page(
            String taskId, String functionKey, String afterFactKey, int limit) {
        require(taskId, "taskId");
        require(functionKey, "functionKey");
        Objects.requireNonNull(afterFactKey, "afterFactKey must not be null");
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("Fact page limit must be 1..200");
        List<PersistedFactQuoteRow> rows = jdbc.query("""
                SELECT page.fact_key, page.fact_type, page.statement_text,
                       quote.evidence_key, quote.quote_text
                FROM (
                    SELECT fact_key, fact_type, statement_text
                    FROM v2_requirement_fact
                    WHERE task_id = ? AND function_key = ? AND fact_key > ?
                    ORDER BY fact_key
                    LIMIT ?
                ) page
                JOIN v2_requirement_fact_quote quote
                  ON quote.task_id = ? AND quote.fact_key = page.fact_key
                ORDER BY page.fact_key, quote.evidence_key, quote.quote_sha256
                """, (row, ignored) -> new PersistedFactQuoteRow(
                row.getString("fact_key"), row.getString("fact_type"), row.getString("statement_text"),
                row.getString("evidence_key"), row.getString("quote_text")),
                taskId, functionKey, afterFactKey, limit, taskId);
        Map<String, PersistedFactAccumulator> facts = new LinkedHashMap<>();
        for (PersistedFactQuoteRow row : rows) {
            PersistedFactAccumulator fact = facts.computeIfAbsent(row.factKey(), ignored ->
                    new PersistedFactAccumulator(row.factType(), row.statement(), new ArrayList<>()));
            fact.quotes().add(new StructuredSourceQuoteV2(row.evidenceKey(), row.quote()));
        }
        return facts.entrySet().stream().map(entry -> new V2GenerationPlanner.PersistedFact(entry.getKey(),
                RequirementFactExtractionV2Result.FactType.fromWire(entry.getValue().factType()),
                entry.getValue().statement(), List.copyOf(entry.getValue().quotes()))).toList();
    }

    private record PersistedFactQuoteRow(
            String factKey, String factType, String statement, String evidenceKey, String quote) {}

    private record PersistedFactAccumulator(
            String factType, String statement, List<StructuredSourceQuoteV2> quotes) {}

    /**
     * Publishes one complete V2 test-point outcome and its cases as one transaction.
     * An unable outcome is still a durable terminal result but creates no formal testcase rows.
     * [Req-ID]: REQ-TGV2-005, REQ-TGV2-007, REQ-TGV2-008, REQ-TGV2-012
     */
    public void acceptTestcasesV2(WorkClaim claim, FunctionalTestcaseV2Validator validator,
            FunctionalTestcaseDesignV2Input input, FunctionalTestcaseDesignV2Result result) {
        FunctionalTestcaseV2Validator.AcceptedDesign accepted = Objects.requireNonNull(validator,
                "validator must not be null").validate(input, result);
        String inputSha256 = sha256(input);
        String resultSha256 = sha256(result);
        String coverageStatus = accepted.formalCoverageSatisfied() ? "SATISFIED" : "NOT_APPLICABLE";
        accept(claim, result, coverageStatus, "FUNCTIONAL_TESTCASE_DESIGN_V2", frozen -> {
            if (!input.functionKey().equals(frozen.functionKey())
                    || !input.testPoint().testPointKey().equals(frozen.testPointKey())) {
                throw new IllegalStateException("V2 testcase result does not match its frozen test point");
            }
            jdbc.update("""
                    INSERT INTO structured_test_point
                    (work_item_id, task_id, test_point_key, function_key, function_name, test_point_type,
                     basis, description, missing_information_json, formal_coverage_satisfied)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), ?)
                    """, claim.workItemId(), claim.taskId(), input.testPoint().testPointKey(), input.functionKey(),
                    input.functionName(), input.testPoint().type().wireValue(), input.testPoint().basis().wireValue(),
                    input.testPoint().description(), json(input.testPoint().missingInformation()),
                    accepted.formalCoverageSatisfied());
            bind(claim.workItemId(), input.testPoint().testPointKey(), "TEST_POINT", "REQUIREMENT_FACT",
                    input.requirementFacts().stream().map(FunctionalTestcaseDesignV2Input.RequirementFact::factKey).toList());
            bind(claim.workItemId(), input.testPoint().testPointKey(), "TEST_POINT", "EVIDENCE",
                    input.requirementFacts().stream().flatMap(fact -> fact.sourceQuotes().stream())
                            .map(StructuredSourceQuoteV2::evidenceKey).distinct().toList());
            jdbc.update("""
                    INSERT INTO v2_generation_outcome
                    (work_item_id, task_id, test_point_key, function_key, generation_outcome,
                     missing_information_json, formal_coverage_satisfied)
                    VALUES (?, ?, ?, ?, ?, CAST(? AS JSON), ?)
                    """, claim.workItemId(), claim.taskId(), input.testPoint().testPointKey(), input.functionKey(),
                    accepted.generationOutcome().wireValue(), json(accepted.missingInformation()),
                    accepted.formalCoverageSatisfied());
            for (FunctionalTestcaseV2Validator.AcceptedTestcase testcase : accepted.testcases()) {
                persistV2Testcase(claim, testcase);
            }
            insertV2Publication(claim, "testcase_design", inputSha256, resultSha256, result);
        });
    }

    /**
     * Persists one independently validated candidate window and its formal projection atomically.
     *
     * <p>The transaction repeats the frozen-window and source-ownership checks because validated records are
     * public immutable values. Only Java's final {@code ACCEPTED} rows may create task-owned formal functions;
     * pending and rejected rows remain audit-only.</p>
     *
     * [Req-ID]: REQ-AFCE-003, REQ-AFCE-005
     */
    public void acceptFunctionCandidates(
            WorkClaim claim, FunctionCandidateExtractionValidator.ValidatedWindow window) {
        requireClaim(claim);
        FunctionCandidateExtractionValidator.ValidatedWindow checked = Objects.requireNonNull(
                window, "window must not be null");
        validateCandidateWindow(checked);
        accept(claim, checked, null, "FEATURE_SCOPE_EXTRACT", frozen -> {
            if (!checked.windowKey().equals(frozen.identityKey())) {
                throw new IllegalStateException("Candidate window identity does not match frozen work");
            }
            if (frozen.ordinalStart() == null || frozen.ordinalEnd() == null
                    || frozen.ordinalEnd() - frozen.ordinalStart() + 1 != checked.sourceOutcomes().size()) {
                throw new IllegalStateException("Candidate source outcomes do not match frozen ordinals");
            }
            List<String> sourceKeys = checked.sourceOutcomes().stream()
                    .map(FunctionCandidateExtractionValidator.ValidatedSourceOutcome::unitKey).toList();
            if (!sourceKeys.equals(frozen.allowedEvidenceKeys())) {
                throw new IllegalStateException("Candidate source outcomes do not match frozen target evidence");
            }

            for (FunctionCandidateExtractionValidator.ValidatedCandidate candidate : checked.candidates()) {
                String functionItemKey = null;
                if (candidate.finalDecision() == FunctionCandidateExtractionValidator.FinalDecision.ACCEPTED) {
                    functionItemKey = FunctionListExtractionValidator.stableItemKey(
                            claim.taskId(), candidate.path(), candidate.description());
                    String ownerWorkItemId = functionItemOwner(claim, new FunctionListItem(
                            functionItemKey, candidate.path(), candidate.description(),
                            candidate.evidenceKeys(), List.of(candidate.targetQuote())));
                    bindIdempotently(ownerWorkItemId, functionItemKey, "FUNCTION_LIST_ITEM", "EVIDENCE",
                            candidate.evidenceKeys());
                }
                jdbc.update("""
                        INSERT INTO structured_function_candidate
                        (work_item_id, task_id, candidate_ref, path_text, description, target_quote,
                         recommended_status, java_final_decision, reason_code, missing_information_json,
                         function_item_key)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), ?)
                        """, claim.workItemId(), claim.taskId(), candidate.candidateRef(), candidate.path(),
                        candidate.description(), candidate.targetQuote(), candidate.recommendedStatus().name(),
                        candidate.finalDecision().name(), candidate.reasonCode(), json(candidate.missingInformation()),
                        functionItemKey);
            }
            for (int index = 0; index < checked.sourceOutcomes().size(); index++) {
                FunctionCandidateExtractionValidator.ValidatedSourceOutcome outcome = checked.sourceOutcomes().get(index);
                jdbc.update("""
                        INSERT INTO structured_function_source_outcome
                        (work_item_id, task_id, unit_key, source_ordinal, kee_disposition,
                         java_final_decision, reason_code)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, claim.workItemId(), claim.taskId(), outcome.unitKey(), frozen.ordinalStart() + index,
                        outcome.disposition().name(), outcome.finalDecision().name(), outcome.reasonCode());
                for (String candidateRef : outcome.candidateRefs()) {
                    jdbc.update("""
                            INSERT INTO structured_function_outcome_candidate
                            (work_item_id, unit_key, candidate_ref) VALUES (?, ?, ?)
                            """, claim.workItemId(), outcome.unitKey(), candidateRef);
                }
            }
        });
    }

    /** Validates and atomically saves a test-point plus its non-fixed testcase set. */
    public void acceptTestcases(WorkClaim claim, FunctionalTestcaseResultValidator validator,
            FunctionalTestcaseResultValidator.WorkItem workItem, FunctionalTestcaseResultValidator.Result result) {
        requireTask(claim, workItem.registry().taskId());
        FunctionalTestcaseResultValidator.ValidationOutcome outcome = Objects.requireNonNull(validator, "validator must not be null").validate(workItem, result);
        String coverageStatus = workItem.basis() == FunctionalTestcaseResultValidator.Basis.FORMAL_REQUIREMENT
                ? "SATISFIED" : "NOT_APPLICABLE";
        accept(claim, result, coverageStatus, "FUNCTIONAL_TESTCASE_DESIGN", frozen -> {
            if (!workItem.functionKey().equals(frozen.functionKey())
                    || !workItem.testPointKey().equals(frozen.testPointKey())
                    || !workItem.evidenceKeys().equals(frozen.allowedEvidenceKeys())) {
                throw new IllegalStateException("Test-point validation closure does not match frozen work");
            }
            insertTaskScoped("test point", () -> jdbc.update("""
                    INSERT INTO structured_test_point
                    (work_item_id, task_id, test_point_key, function_key, function_name, test_point_type, basis, description, missing_information_json, formal_coverage_satisfied)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), ?)
                    """, claim.workItemId(), claim.taskId(), workItem.testPointKey(), workItem.functionKey(), workItem.functionName(),
                    workItem.testPointType().name(), workItem.basis().name(), workItem.description(), json(workItem.missingInformation()), outcome.formalCoverageSatisfied()));
            bind(claim.workItemId(), workItem.testPointKey(), "TEST_POINT", "REQUIREMENT_FACT", workItem.requirementFactKeys());
            bind(claim.workItemId(), workItem.testPointKey(), "TEST_POINT", "EVIDENCE", workItem.evidenceKeys());
            for (FunctionalTestcaseResultValidator.Testcase testcase : result.testcases()) {
                insertTaskScoped("test case", () -> jdbc.update("""
                        INSERT INTO structured_test_case
                        (work_item_id, task_id, case_key, name_text, title, priority, preconditions_json,
                         hardware_configuration_json, software_configuration_json, test_configuration_json, parameter_configuration_json,
                         inputs_json, expected_results_json, evaluation_criteria, result_evaluation_criteria,
                         termination_conditions_json, result_collection, author_name, author_date, case_status, missing_information_json)
                        VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON), CAST(? AS JSON), CAST(? AS JSON), CAST(? AS JSON),
                                CAST(? AS JSON), CAST(? AS JSON), ?, ?, CAST(? AS JSON), ?, ?, ?, ?, CAST(? AS JSON))""",
                        claim.workItemId(), claim.taskId(), testcase.caseKey(), testcase.name(), testcase.title(), testcase.priority().name(),
                        json(testcase.preconditions()), json(testcase.initialization().hardwareConfiguration()),
                        json(testcase.initialization().softwareConfiguration()), json(testcase.initialization().testConfiguration()),
                        json(testcase.initialization().parameterConfiguration()), json(testcase.inputs()), json(testcase.expectedResults()),
                        testcase.evaluationCriteria(), testcase.resultEvaluationCriteria(), json(testcase.terminationConditions()),
                        testcase.resultCollection(), testcase.authoringInformation().author(), testcase.authoringInformation().date(),
                        testcase.caseStatus().name(), json(testcase.missingInformation())));
                for (FunctionalTestcaseResultValidator.Step step : testcase.steps()) jdbc.update("""
                        INSERT INTO structured_test_case_step
                        (work_item_id, case_key, step_no, action_text, expected_text, evaluation_criteria, termination_or_error, result_collection)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, claim.workItemId(), testcase.caseKey(), step.stepNo(), step.action(), step.expected(),
                        step.evaluationCriteria(), step.terminationOrError(), step.resultCollection());
                bind(claim.workItemId(), testcase.caseKey(), "TEST_CASE", "REQUIREMENT_FACT", testcase.requirementFactKeys());
                bind(claim.workItemId(), testcase.caseKey(), "TEST_CASE", "EVIDENCE", testcase.evidenceKeys());
            }
        });
        for (FunctionalTestcaseResultValidator.Testcase testcase : result.testcases()) publishKey(workItem.registry(), StructuredKeyType.TESTCASE, testcase.caseKey());
    }

    /** Marks a running attempt failed without touching other completed work items. */
    public void fail(WorkClaim claim, String failureType) {
        fail(claim, failureType, null);
    }

    /**
     * Renews only the exact active attempt before its current lease expires.
     *
     * <p>The attempt ID is part of the update predicate because the process-level owner label is shared by
     * structured workers. A stale worker therefore cannot revive its claim after recovery creates a new attempt.
     * Cancellation also closes this boundary before another heartbeat can extend the work.</p>
     *
     * [Req-ID]: REQ-SEW-002
     */
    public boolean renewLease(WorkClaim claim) {
        requireClaim(claim);
        return Boolean.TRUE.equals(transaction.execute(status -> {
            Instant now = clock.instant();
            int changed = jdbc.update("""
                    UPDATE structured_generation_work_item w
                    JOIN structured_generation_attempt a ON a.work_item_id = w.id
                    JOIN generation_task t ON t.id = w.task_id
                    SET w.lease_expires_at = ?
                    WHERE w.id = ? AND w.task_id = ? AND w.status = 'RUNNING'
                      AND w.lease_owner = ? AND w.lease_expires_at > ?
                      AND a.id = ? AND a.status = 'RUNNING'
                      AND t.cancellation_requested_at IS NULL
                    """, now.plus(LEASE_DURATION), claim.workItemId(), claim.taskId(), claim.owner(), now,
                    claim.attemptId());
            return changed == 1;
        }));
    }

    /**
     * Marks a running attempt failed and atomically retains an optional enumerated business-validation diagnostic.
     * Arbitrary exception messages are deliberately not accepted by this seam. [Req-ID]: REQ-FSC-007
     */
    public void fail(WorkClaim claim, String failureType, StructuredValidationFailure validationFailure) {
        requireClaim(claim); require(failureType, "failureType");
        if (!FAILURE_TYPES.contains(failureType)) throw new IllegalArgumentException("Unsupported structured failure type");
        if (validationFailure != null && !"business_validation_failed".equals(failureType)) {
            throw new IllegalArgumentException("Validation diagnostics require business_validation_failed");
        }
        transaction.executeWithoutResult(status -> {
            FrozenWork frozen = lockedWork(claim.workItemId());
            verifyClaim(claim, frozen, frozen.operationName());
            verifyRunning(claim, frozen);
            String code = validationFailure == null ? null : validationFailure.code();
            String path = validationFailure == null ? null : validationFailure.path();
            String message = validationFailure == null ? null : validationFailure.storageMessage();
            int attemptChanged = jdbc.update("""
                    UPDATE structured_generation_attempt
                    SET status = 'FAILED', failure_type = ?, completed_at = ?,
                        validation_error_code = ?, validation_error_path = ?, validation_error_message = ?
                    WHERE id = ?
                    """, failureType, clock.instant(), code, path, message, claim.attemptId());
            int workChanged = jdbc.update("""
                    UPDATE structured_generation_work_item
                    SET status = 'FAILED', lease_owner = NULL, lease_expires_at = NULL,
                        validation_error_code = ?, validation_error_path = ?, validation_error_message = ?
                    WHERE id = ?
                    """, code, path, message, claim.workItemId());
            int taskChanged = jdbc.update("""
                    UPDATE generation_task
                    SET validation_error_code = ?, validation_error_path = ?, validation_error_message = ?
                    WHERE id = ?
                    """, code, path, message, claim.taskId());
            if (attemptChanged != 1 || workChanged != 1 || taskChanged != 1) {
                throw new IllegalStateException("Structured failure did not update exactly one attempt, work item, and task");
            }
        });
    }

    /** Updates the application-owned processing and coverage axes together inside one transaction. */
    public StructuredTaskState updateTaskState(String taskId, StructuredTaskState state) {
        require(taskId, "taskId");
        StructuredTaskState next = Objects.requireNonNull(state, "state must not be null");
        return transaction.execute(status -> {
            Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM generation_task WHERE id = ? FOR UPDATE", Integer.class, taskId);
            if (existing == null || existing != 1) throw new IllegalArgumentException("Generation task does not exist");
            jdbc.update("UPDATE generation_task SET structured_processing_status = ?, structured_coverage_status = ? WHERE id = ?",
                    next.processingStatus().name(), next.coverageStatus().name(), taskId);
            return next;
        });
    }

    /** Reads the two structured-generation task axes without exposing model output. */
    public Optional<StructuredTaskState> findTaskState(String taskId) {
        require(taskId, "taskId");
        return jdbc.query("SELECT structured_processing_status, structured_coverage_status FROM generation_task WHERE id = ?",
                (row, ignored) -> {
                    String processing = row.getString(1);
                    String coverage = row.getString(2);
                    return processing == null || coverage == null ? null
                            : new StructuredTaskState(StructuredProcessingStatus.valueOf(processing), StructuredCoverageStatus.valueOf(coverage));
                }, taskId).stream().filter(Objects::nonNull).findFirst();
    }

    /** Rebuilds accepted formal facts and task-unique function items after a worker restart. */
    public AcceptedInputs acceptedInputs(String taskId) {
        require(taskId, "taskId");
        Map<String, String> taskEvidenceTexts = new LinkedHashMap<>();
        jdbc.query("SELECT unit_id, content FROM material_inventory_unit WHERE task_id = ? ORDER BY document_id, ordinal, unit_id",
                row -> {
                    String key = row.getString("unit_id");
                    String content = row.getString("content");
                    String previous = taskEvidenceTexts.putIfAbsent(key, content);
                    if (previous != null && !previous.equals(content)) {
                        throw new IllegalStateException("Task evidence key resolves to conflicting persisted text");
                    }
                }, taskId);
        List<AcceptedFact> facts = jdbc.query("""
                SELECT f.work_item_id, f.fact_key, f.function_name, f.roles_json, f.trigger_conditions_json,
                       f.inputs_json, f.business_rules_json, f.outputs_json, f.permissions_json,
                       f.state_changes_json, f.exception_handling_json, f.external_dependencies_json
                FROM structured_requirement_fact f
                JOIN structured_generation_work_item w ON w.id = f.work_item_id
                WHERE f.task_id = ? AND w.status = 'COMPLETED' AND w.accepted_result_sha256 IS NOT NULL
                ORDER BY w.created_at, f.fact_key
                """, (row, ignored) -> {
                    List<String> evidenceKeys = referenceKeys(row.getString("work_item_id"), row.getString("fact_key"),
                            "REQUIREMENT_FACT", "EVIDENCE");
                    return new AcceptedFact(row.getString("fact_key"), row.getString("function_name"),
                            stringList(row.getString("roles_json")), stringList(row.getString("trigger_conditions_json")),
                            stringList(row.getString("inputs_json")), stringList(row.getString("business_rules_json")),
                            stringList(row.getString("outputs_json")), stringList(row.getString("permissions_json")),
                            stringList(row.getString("state_changes_json")), stringList(row.getString("exception_handling_json")),
                            stringList(row.getString("external_dependencies_json")), evidenceKeys,
                            requiredEvidenceTexts(evidenceKeys, taskEvidenceTexts));
                }, taskId);
        List<AcceptedFunctionItem> items = jdbc.query("""
                SELECT item.work_item_id, item.item_key, item.path_text, item.description, item.target_quotes_json
                FROM structured_function_list_item item
                JOIN structured_generation_work_item w ON w.id = item.work_item_id
                WHERE item.task_id = ? AND w.status = 'COMPLETED' AND w.accepted_result_sha256 IS NOT NULL
                ORDER BY item.path_text, item.item_key
                """, (row, ignored) -> new AcceptedFunctionItem(row.getString("item_key"), row.getString("path_text"),
                row.getString("description"), referenceKeys(row.getString("work_item_id"), row.getString("item_key"),
                        "FUNCTION_LIST_ITEM", "EVIDENCE"), stringList(row.getString("target_quotes_json"))), taskId);
        return new AcceptedInputs(facts, items);
    }

    private static Map<String, String> requiredEvidenceTexts(
            List<String> evidenceKeys, Map<String, String> taskEvidenceTexts) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : evidenceKeys) {
            String content = taskEvidenceTexts.get(key);
            if (content == null || content.isBlank()) {
                throw new IllegalStateException("Accepted requirement fact references unavailable persisted evidence text");
            }
            result.put(key, content);
        }
        return Map.copyOf(result);
    }

    /**
     * Rebuilds each confirmed final-function mapping from committed reconciliation bindings.
     *
     * <p>The caller receives the persisted function-list identities together with their linked formal facts. It must
     * not infer a function merely by grouping unrelated facts that happen to share display text.</p>
     */
    public List<AcceptedConfirmedFunction> acceptedConfirmedFunctions(String taskId) {
        require(taskId, "taskId");
        AcceptedInputs accepted = acceptedInputs(taskId);
        Map<String, AcceptedFunctionItem> itemsByKey = new LinkedHashMap<>();
        accepted.functionItems().forEach(item -> itemsByKey.put(item.itemKey(), item));
        Map<String, AcceptedFact> factsByKey = new LinkedHashMap<>();
        accepted.facts().forEach(fact -> factsByKey.put(fact.factKey(), fact));
        List<AcceptedReconciliation> reconciliations = jdbc.query("""
                SELECT reconciliation.work_item_id, reconciliation.reconciliation_key
                FROM structured_feature_reconciliation reconciliation
                JOIN structured_generation_work_item work ON work.id = reconciliation.work_item_id
                WHERE reconciliation.task_id = ? AND reconciliation.confirmation_status = 'CONFIRMED'
                  AND work.status = 'COMPLETED' AND work.accepted_result_sha256 IS NOT NULL
                ORDER BY work.created_at, reconciliation.reconciliation_key
                """, (row, ignored) -> new AcceptedReconciliation(
                row.getString("work_item_id"), row.getString("reconciliation_key")), taskId);
        return reconciliations.stream().map(reconciliation -> {
            List<AcceptedFunctionItem> items = referenceKeys(reconciliation.workItemId(),
                    reconciliation.reconciliationKey(), "RECONCILIATION", "FUNCTION_LIST_ITEM").stream()
                    .map(key -> requiredAccepted(itemsByKey, key, "function-list item")).toList();
            List<AcceptedFact> facts = referenceKeys(reconciliation.workItemId(),
                    reconciliation.reconciliationKey(), "RECONCILIATION", "REQUIREMENT_FACT").stream()
                    .map(key -> requiredAccepted(factsByKey, key, "requirement fact")).toList();
            if (items.isEmpty() && facts.isEmpty()) {
                throw new IllegalStateException("Confirmed reconciliation has no persisted source identity");
            }
            return new AcceptedConfirmedFunction(reconciliation.reconciliationKey(), items, facts);
        }).toList();
    }

    private static <T> T requiredAccepted(Map<String, T> accepted, String key, String type) {
        T value = accepted.get(key);
        if (value == null) throw new IllegalStateException("Confirmed reconciliation references an unavailable " + type);
        return value;
    }

    /** Reads durable aggregate counts used by the independent processing/coverage completion gate. */
    public AggregateState aggregateState(String taskId) {
        require(taskId, "taskId");
        int totalReview = count("SELECT COUNT(*) FROM structured_generation_work_item WHERE task_id = ? "
                + "AND operation_name = 'REQUIREMENT_MATERIAL_REVIEW' AND status <> 'SPLIT'", taskId);
        int completedReview = count("SELECT COUNT(*) FROM structured_generation_work_item WHERE task_id = ? AND operation_name = 'REQUIREMENT_MATERIAL_REVIEW' AND status = 'COMPLETED'", taskId);
        int formalPoints = count("SELECT COUNT(*) FROM structured_test_point WHERE task_id = ? AND basis = 'FORMAL_REQUIREMENT'", taskId);
        int coveredFormal = count("SELECT COUNT(*) FROM structured_test_point WHERE task_id = ? AND basis = 'FORMAL_REQUIREMENT' AND formal_coverage_satisfied = TRUE", taskId);
        int pendingCases = count("SELECT COUNT(*) FROM structured_test_case WHERE task_id = ? AND case_status = 'PENDING_CONFIRMATION'", taskId);
        int acceptedWork = count("SELECT COUNT(*) FROM structured_generation_work_item WHERE task_id = ? AND status = 'COMPLETED'", taskId);
        int failedWork = count("SELECT COUNT(*) FROM structured_generation_work_item WHERE task_id = ? AND status = 'FAILED'", taskId);
        int nonterminal = count("SELECT COUNT(*) FROM structured_generation_work_item WHERE task_id = ? AND status IN ('QUEUED','RUNNING')", taskId);
        int acceptedCandidates = count("SELECT COUNT(*) FROM structured_function_candidate "
                + "WHERE task_id = ? AND java_final_decision = 'ACCEPTED'", taskId);
        int incompleteFunctionScope = count("SELECT COUNT(*) FROM structured_function_source_outcome "
                + "WHERE task_id = ? AND kee_disposition <> 'NO_FUNCTION' "
                + "AND java_final_decision <> 'ACCEPTED'", taskId);
        int failedCandidateWork = count("""
                SELECT COUNT(*)
                FROM structured_generation_work_item w
                JOIN structured_generation_attempt a ON a.work_item_id = w.id
                WHERE w.task_id = ? AND w.operation_name = 'FEATURE_SCOPE_EXTRACT' AND w.status = 'FAILED'
                  AND a.status = 'FAILED'
                  AND a.attempt_number = (
                      SELECT MAX(latest.attempt_number)
                      FROM structured_generation_attempt latest
                      WHERE latest.work_item_id = w.id
                  )
                  AND a.failure_type IN (
                      'request_too_large', 'response_too_large', 'structured_output_invalid',
                      'model_unavailable', 'model_execution_failed'
                  )
                """, taskId);
        return new AggregateState(totalReview, completedReview, formalPoints, coveredFormal, pendingCases,
                acceptedWork, failedWork, nonterminal == 0, acceptedCandidates,
                incompleteFunctionScope, failedCandidateWork);
    }

    /** Computes V2 processing and coverage counts only from committed work and outcome rows. */
    public V2AggregateState v2AggregateState(String taskId) {
        require(taskId, "taskId");
        int totalWork = count("""
                SELECT COUNT(*) FROM structured_generation_work_item
                WHERE task_id = ? AND operation_name IN
                    ('REQUIREMENT_FACT_EXTRACTION_V2','FUNCTIONAL_TESTCASE_DESIGN_V2')
                  AND status NOT IN ('SPLIT','SUPERSEDED')
                """, taskId);
        int completedWork = count("""
                SELECT COUNT(*) FROM structured_generation_work_item
                WHERE task_id = ? AND operation_name IN
                    ('REQUIREMENT_FACT_EXTRACTION_V2','FUNCTIONAL_TESTCASE_DESIGN_V2')
                  AND status = 'COMPLETED' AND accepted_result_sha256 IS NOT NULL
                """, taskId);
        int failedWork = count("""
                SELECT COUNT(*) FROM structured_generation_work_item
                WHERE task_id = ? AND operation_name IN
                    ('REQUIREMENT_FACT_EXTRACTION_V2','FUNCTIONAL_TESTCASE_DESIGN_V2')
                  AND status = 'FAILED'
                """, taskId);
        int pendingWork = count("""
                SELECT COUNT(*) FROM structured_generation_work_item
                WHERE task_id = ? AND operation_name IN
                    ('REQUIREMENT_FACT_EXTRACTION_V2','FUNCTIONAL_TESTCASE_DESIGN_V2')
                  AND status NOT IN ('COMPLETED','FAILED','SPLIT','SUPERSEDED')
                """, taskId);
        int testPointTotal = count("""
                SELECT COUNT(*) FROM v2_generation_outcome outcome
                JOIN structured_generation_work_item work ON work.id = outcome.work_item_id
                WHERE outcome.task_id = ? AND work.status = 'COMPLETED'
                  AND work.accepted_result_sha256 IS NOT NULL
                """, taskId);
        int formalCovered = count("""
                SELECT COUNT(*) FROM v2_generation_outcome outcome
                JOIN structured_generation_work_item work ON work.id = outcome.work_item_id
                WHERE outcome.task_id = ? AND outcome.formal_coverage_satisfied = TRUE
                  AND work.status = 'COMPLETED' AND work.accepted_result_sha256 IS NOT NULL
                """, taskId);
        int unable = count("""
                SELECT COUNT(*) FROM v2_generation_outcome outcome
                JOIN structured_generation_work_item work ON work.id = outcome.work_item_id
                WHERE outcome.task_id = ? AND outcome.generation_outcome = 'unable_to_generate'
                  AND work.status = 'COMPLETED' AND work.accepted_result_sha256 IS NOT NULL
                """, taskId);
        int formalCases = count("""
                SELECT COUNT(*) FROM structured_test_case c
                JOIN structured_generation_work_item w ON w.id = c.work_item_id
                WHERE c.task_id = ? AND w.operation_name = 'FUNCTIONAL_TESTCASE_DESIGN_V2'
                  AND w.status = 'COMPLETED' AND c.case_status = 'FORMAL'
                """, taskId);
        int pendingCases = count("""
                SELECT COUNT(*) FROM structured_test_case c
                JOIN structured_generation_work_item w ON w.id = c.work_item_id
                WHERE c.task_id = ? AND w.operation_name = 'FUNCTIONAL_TESTCASE_DESIGN_V2'
                  AND w.status = 'COMPLETED' AND c.case_status = 'PENDING_CONFIRMATION'
                """, taskId);
        return new V2AggregateState(totalWork, completedWork, failedWork, pendingWork,
                testPointTotal, formalCovered, formalCases, pendingCases, unable);
    }

    /** Returns whether this task has committed protocol V1 candidate audit rows. [Req-ID]: REQ-AFCE-006 */
    public boolean hasFunctionCandidateAudit(String taskId) {
        require(taskId, "taskId");
        return count("SELECT COUNT(*) FROM structured_function_source_outcome WHERE task_id = ?", taskId) > 0;
    }

    /** Returns whether an exact registered work identity already has an accepted hash. */
    public boolean isCompleted(String workItemId) {
        require(workItemId, "workItemId");
        return count("SELECT COUNT(*) FROM structured_generation_work_item WHERE id = ? AND status = 'COMPLETED' AND accepted_result_sha256 IS NOT NULL",
                workItemId) == 1;
    }

    /** Returns whether an oversized material-slice work was durably replaced by deterministic child works. [Req-ID]: REQ-FTG-010, REQ-FTG-012 */
    public boolean isSplit(String workItemId) {
        require(workItemId, "workItemId");
        return count("SELECT COUNT(*) FROM structured_generation_work_item WHERE id = ? AND status = 'SPLIT'",
                workItemId) == 1;
    }

    /**
     * Atomically closes one oversized review attempt and publishes its exact two child windows.
     *
     * <p>The parent status is a durable recovery marker, not an accepted business result. Both child identities are
     * registered in the same transaction so a crash can never expose only half of the frozen evidence partition.</p>
     *
     * [Req-ID]: REQ-FTG-010
     */
    public void splitReviewWork(WorkClaim claim, WorkRegistration left, WorkRegistration right) {
        splitMaterialSliceWork(claim, left, right,
                "requirement-material-quality-review", "REQUIREMENT_MATERIAL_REVIEW", "review");
    }

    /** Atomically replaces one oversized V2 fact window with two deterministic child windows. */
    public void splitRequirementFactWork(WorkClaim claim, WorkRegistration left, WorkRegistration right) {
        splitMaterialSliceWork(claim, left, right,
                "requirement-fact-extraction", "REQUIREMENT_FACT_EXTRACTION_V2", "requirement fact extraction");
    }

    /**
     * Atomically replaces one oversized function-list extraction with its exact two frozen child windows.
     *
     * <p>This is deliberately a separate public entry point from review splitting: callers cannot use a generic
     * split operation to widen another Skill or operation. The shared private transaction only removes duplicate
     * locking code; it still checks the exact expected Skill and operation under the row lock.</p>
     *
     * [Req-ID]: REQ-FTG-012
     */
    public void splitFunctionListExtractionWork(
            WorkClaim claim, WorkRegistration left, WorkRegistration right) {
        splitMaterialSliceWork(claim, left, right,
                "feature-scope-reconciliation", "FEATURE_SCOPE_EXTRACT", "function-list extraction");
    }

    /**
     * Migrates one queued pre-V17 extraction window to two stable historical children before a remote call.
     *
     * <p>This is not an error retry: it does not create or rewrite an attempt. The locked transaction only accepts
     * the legacy no-context shape, verifies the parent against one complete frozen inventory document, and publishes
     * both child identities with the parent {@code SPLIT} marker. A concurrent caller that observes the committed
     * marker returns {@code false} without publishing another pair.</p>
     *
     * [Req-ID]: REQ-FTG-016
     */
    public boolean splitQueuedHistoricalFunctionListExtractionWork(
            String workItemId, WorkRegistration left, WorkRegistration right) {
        require(workItemId, "workItemId");
        WorkRegistration checkedLeft = Objects.requireNonNull(left, "left must not be null");
        WorkRegistration checkedRight = Objects.requireNonNull(right, "right must not be null");
        Boolean changed = transaction.execute(status -> {
            FrozenWork parent = lockedWork(workItemId);
            if (!"feature-scope-reconciliation".equals(parent.skillName())
                    || !"FEATURE_SCOPE_EXTRACT".equals(parent.operationName())
                    || parent.materialDocumentId() != null) {
                throw new IllegalStateException("Only a historical function-list extraction can be pre-split");
            }
            if ("SPLIT".equals(parent.status())) return false;
            if (!"QUEUED".equals(parent.status())) {
                throw new IllegalStateException("Historical function-list extraction must be queued before pre-split");
            }
            if (parent.ordinalStart() == null || parent.ordinalEnd() == null
                    || parent.allowedEvidenceKeys().size() <= 16) {
                throw new IllegalStateException("Historical function-list extraction is not an oversized target window");
            }
            if (parent.acceptedResultSha256() != null || workOwnedBusinessRowCount(parent.id()) != 0) {
                throw new IllegalStateException("An accepted or partially written historical extraction cannot be pre-split");
            }
            if (count("SELECT COUNT(*) FROM structured_generation_attempt WHERE work_item_id = ? AND status = 'RUNNING'",
                    parent.id()) != 0) {
                throw new IllegalStateException("A running historical extraction attempt cannot be pre-split");
            }
            validateHistoricalMaterialInventory(parent);
            validateMaterialSliceSplit(parent, checkedLeft, checkedRight,
                    "feature-scope-reconciliation", "FEATURE_SCOPE_EXTRACT", "historical function-list extraction");
            int leftSize = checkedLeft.allowedEvidenceKeys().size();
            int rightSize = checkedRight.allowedEvidenceKeys().size();
            if (leftSize != parent.allowedEvidenceKeys().size() / 2
                    || leftSize > 16 || rightSize > 16) {
                throw new IllegalArgumentException(
                        "Historical function-list extraction children must use the deterministic midpoint and fit the target limit");
            }
            registerLocked(checkedLeft);
            registerLocked(checkedRight);
            if (jdbc.update("""
                    UPDATE structured_generation_work_item
                    SET status = 'SPLIT', lease_owner = NULL, lease_expires_at = NULL,
                        validation_error_code = NULL, validation_error_path = NULL, validation_error_message = NULL
                    WHERE id = ? AND status = 'QUEUED' AND accepted_result_sha256 IS NULL
                    """, parent.id()) != 1) {
                throw new IllegalStateException("Historical function-list extraction changed during pre-split");
            }
            return true;
        });
        return Boolean.TRUE.equals(changed);
    }

    private void splitMaterialSliceWork(WorkClaim claim, WorkRegistration left, WorkRegistration right,
            String expectedSkill, String expectedOperation, String sliceKind) {
        requireClaim(claim);
        WorkRegistration checkedLeft = Objects.requireNonNull(left, "left must not be null");
        WorkRegistration checkedRight = Objects.requireNonNull(right, "right must not be null");
        transaction.executeWithoutResult(status -> {
            FrozenWork parent = lockedWork(claim.workItemId());
            verifyClaim(claim, parent, expectedOperation);
            verifyRunning(claim, parent);
            validateMaterialSliceSplit(parent, checkedLeft, checkedRight, expectedSkill, expectedOperation, sliceKind);
            if (parent.acceptedResultSha256() != null || workOwnedBusinessRowCount(parent.id()) != 0) {
                throw new IllegalStateException("An accepted or partially written " + sliceKind + " work cannot be split");
            }
            registerLocked(checkedLeft);
            registerLocked(checkedRight);
            Instant completedAt = clock.instant();
            if (jdbc.update("""
                    UPDATE structured_generation_attempt
                    SET status = 'FAILED', failure_type = 'response_too_large', completed_at = ?
                    WHERE id = ? AND work_item_id = ? AND status = 'RUNNING'
                    """, completedAt, claim.attemptId(), parent.id()) != 1) {
                throw new IllegalStateException("Oversized " + sliceKind + " attempt is no longer running");
            }
            if (jdbc.update("""
                    UPDATE structured_generation_work_item
                    SET status = 'SPLIT', lease_owner = NULL, lease_expires_at = NULL,
                        validation_error_code = NULL, validation_error_path = NULL, validation_error_message = NULL
                    WHERE id = ? AND status = 'RUNNING'
                    """, parent.id()) != 1) {
                throw new IllegalStateException("Oversized " + sliceKind + " work is no longer running");
            }
        });
    }

    private static void validateMaterialSliceSplit(FrozenWork parent, WorkRegistration left, WorkRegistration right,
            String expectedSkill, String expectedOperation, String sliceKind) {
        if (!expectedSkill.equals(parent.skillName()) || !expectedOperation.equals(parent.operationName())) {
            throw new IllegalStateException("Only a " + sliceKind + " work can use this split operation");
        }
        if (parent.ordinalStart() == null || parent.ordinalEnd() == null
                || parent.ordinalEnd() - parent.ordinalStart() + 1 != parent.allowedEvidenceKeys().size()) {
            throw new IllegalStateException("Frozen " + sliceKind + " evidence does not match its continuous ordinal window");
        }
        for (WorkRegistration child : List.of(left, right)) {
            if (!parent.taskId().equals(child.taskId()) || !parent.skillName().equals(child.skillName())
                    || !parent.operationName().equals(child.operationName())
                    || !Objects.equals(parent.materialKey(), child.materialKey())
                    || !Objects.equals(parent.materialDocumentId(), child.materialDocumentId())
                    || !Objects.equals(parent.sourceLabel(), child.sourceLabel())) {
                throw new IllegalArgumentException("Split child must retain the frozen " + sliceKind + " coordinates");
            }
            if (parent.materialDocumentId() != null
                    && (!parent.id().equals(child.parentWorkItemId()) || child.splitDepth() != parent.splitDepth() + 1)) {
                throw new IllegalArgumentException("Semantic split child must retain durable parent lineage");
            }
            if (child.allowedEvidenceKeys().isEmpty()
                    || child.ordinalEnd() - child.ordinalStart() + 1 != child.allowedEvidenceKeys().size()) {
                throw new IllegalArgumentException("Split child evidence must match its continuous ordinal window");
            }
        }
        if (!Objects.equals(parent.ordinalStart(), left.ordinalStart())
                || left.ordinalEnd() + 1 != right.ordinalStart()
                || !Objects.equals(parent.ordinalEnd(), right.ordinalEnd())) {
            throw new IllegalArgumentException("Split children must exactly partition the parent ordinal window");
        }
        List<String> evidence = new ArrayList<>(left.allowedEvidenceKeys());
        evidence.addAll(right.allowedEvidenceKeys());
        if (!evidence.equals(parent.allowedEvidenceKeys())) {
            throw new IllegalArgumentException("Split children must preserve the exact parent evidence order");
        }
    }

    private void validateHistoricalMaterialInventory(FrozenWork parent) {
        Map<String, List<String>> evidenceByDocument = new LinkedHashMap<>();
        List<List<String>> inventoryRows = jdbc.query("""
                SELECT document.document_id, unit.unit_id
                FROM material_inventory_document document
                JOIN material_inventory_unit unit
                  ON unit.task_id = document.task_id AND unit.document_id = document.document_id
                WHERE document.task_id = ? AND document.complete = TRUE
                  AND unit.ordinal BETWEEN ? AND ?
                ORDER BY document.document_id, unit.ordinal, unit.unit_id
                """, (row, ignored) -> List.of(row.getString("document_id"), row.getString("unit_id")),
                parent.taskId(), parent.ordinalStart(), parent.ordinalEnd());
        inventoryRows.forEach(row -> evidenceByDocument
                .computeIfAbsent(row.get(0), ignored -> new ArrayList<>())
                .add(row.get(1)));
        long matches = evidenceByDocument.values().stream()
                .filter(parent.allowedEvidenceKeys()::equals)
                .count();
        if (matches != 1) {
            throw new IllegalStateException("Historical extraction target must match exactly one complete frozen document");
        }
    }

    private int workOwnedBusinessRowCount(String workItemId) {
        return count("SELECT COUNT(*) FROM structured_function_source_outcome WHERE work_item_id = ?", workItemId)
                + count("SELECT COUNT(*) FROM structured_function_candidate WHERE work_item_id = ?", workItemId)
                + count("SELECT COUNT(*) FROM structured_function_outcome_candidate WHERE work_item_id = ?", workItemId)
                + count("SELECT COUNT(*) FROM structured_requirement_fact WHERE work_item_id = ?", workItemId)
                + count("SELECT COUNT(*) FROM structured_review_finding WHERE work_item_id = ?", workItemId)
                + count("SELECT COUNT(*) FROM structured_function_list_item WHERE work_item_id = ?", workItemId)
                + count("SELECT COUNT(*) FROM structured_feature_reconciliation WHERE work_item_id = ?", workItemId)
                + count("SELECT COUNT(*) FROM structured_test_point WHERE work_item_id = ?", workItemId)
                + count("SELECT COUNT(*) FROM structured_test_case WHERE work_item_id = ?", workItemId)
                + count("SELECT COUNT(*) FROM structured_test_case_step WHERE work_item_id = ?", workItemId)
                + count("SELECT COUNT(*) FROM structured_reference_binding WHERE work_item_id = ?", workItemId);
    }

    /**
     * Reports whether this task already has an accepted V1 or V2 reconciliation work.
     * A restarted task must continue from its persisted mappings instead of calling a newer wire
     * protocol and duplicating a business stage that already completed successfully.
     *
     * [Req-ID]: REQ-FSC-008
     */
    public boolean hasCompletedReconciliationWork(String taskId) {
        require(taskId, "taskId");
        return count("""
                SELECT COUNT(*) FROM structured_generation_work_item
                WHERE task_id = ?
                  AND operation_name IN ('FEATURE_SCOPE_RECONCILIATION','FEATURE_SCOPE_RECONCILIATION_V2')
                  AND status = 'COMPLETED' AND accepted_result_sha256 IS NOT NULL
                """, taskId) > 0;
    }

    /**
     * Returns true only when every registered material review/extraction work is durably accepted
     * and the persisted inventory remains complete. Recovery may then skip remote parsed-unit
     * traversal and continue exclusively from the application-owned snapshot.
     *
     * [Req-ID]: REQ-FSC-008
     */
    public boolean hasCompletedMaterialStages(String taskId) {
        require(taskId, "taskId");
        int materialWorks = count("""
                SELECT COUNT(*) FROM structured_generation_work_item
                WHERE task_id = ? AND operation_name IN ('REQUIREMENT_MATERIAL_REVIEW','FEATURE_SCOPE_EXTRACT')
                  AND status <> 'SPLIT'
                """, taskId);
        if (materialWorks == 0 || count("""
                SELECT COUNT(*) FROM structured_generation_work_item
                WHERE task_id = ? AND operation_name IN ('REQUIREMENT_MATERIAL_REVIEW','FEATURE_SCOPE_EXTRACT')
                  AND status <> 'SPLIT'
                  AND (status <> 'COMPLETED' OR accepted_result_sha256 IS NULL)
                """, taskId) != 0) return false;
        int documents = count("SELECT COUNT(*) FROM material_inventory_document WHERE task_id = ?", taskId);
        return documents > 0 && count(
                "SELECT COUNT(*) FROM material_inventory_document WHERE task_id = ? AND complete = FALSE", taskId) == 0;
    }

    /**
     * Returns true when every material-review leaf is durably accepted, independently of function-list extraction.
     *
     * <p>Recovery needs this narrower boundary because an unfinished extraction must not make Java reconstruct
     * historical review split trees that already own accepted facts and findings. Split parents are lineage markers;
     * only non-split leaves carry acceptance. A complete task-owned inventory remains mandatory so the rebuilt
     * evidence registry cannot silently omit or replace a source unit.</p>
     *
     * [Req-ID]: REQ-ESR-008
     */
    public boolean hasCompletedReviewStage(String taskId) {
        require(taskId, "taskId");
        int reviewLeaves = count("""
                SELECT COUNT(*) FROM structured_generation_work_item
                WHERE task_id = ? AND operation_name = 'REQUIREMENT_MATERIAL_REVIEW'
                  AND status <> 'SPLIT'
                """, taskId);
        if (reviewLeaves == 0 || count("""
                SELECT COUNT(*) FROM structured_generation_work_item
                WHERE task_id = ? AND operation_name = 'REQUIREMENT_MATERIAL_REVIEW'
                  AND status <> 'SPLIT'
                  AND (status <> 'COMPLETED' OR accepted_result_sha256 IS NULL)
                """, taskId) != 0) return false;
        int documents = count("SELECT COUNT(*) FROM material_inventory_document WHERE task_id = ?", taskId);
        if (documents == 0 || count(
                "SELECT COUNT(*) FROM material_inventory_document WHERE task_id = ? AND complete = FALSE", taskId) != 0) {
            return false;
        }
        int reviewUnits = count("""
                SELECT COUNT(*)
                FROM material_inventory_unit u
                JOIN material_inventory_document d ON d.task_id = u.task_id AND d.document_id = u.document_id
                WHERE u.task_id = ? AND d.document_role <> 'FUNCTION_LIST'
                """, taskId);
        int coveredReferences = count("""
                SELECT COUNT(*)
                FROM structured_generation_work_item w
                JOIN JSON_TABLE(w.allowed_evidence_keys_json, '$[*]'
                    COLUMNS (evidence_key VARCHAR(255) PATH '$')) AS evidence
                WHERE w.task_id = ? AND w.operation_name = 'REQUIREMENT_MATERIAL_REVIEW'
                  AND w.status = 'COMPLETED' AND w.accepted_result_sha256 IS NOT NULL
                """, taskId);
        int distinctCoveredReferences = count("""
                SELECT COUNT(DISTINCT evidence.evidence_key)
                FROM structured_generation_work_item w
                JOIN JSON_TABLE(w.allowed_evidence_keys_json, '$[*]'
                    COLUMNS (evidence_key VARCHAR(255) PATH '$')) AS evidence
                WHERE w.task_id = ? AND w.operation_name = 'REQUIREMENT_MATERIAL_REVIEW'
                  AND w.status = 'COMPLETED' AND w.accepted_result_sha256 IS NOT NULL
                """, taskId);
        int invalidReferences = count("""
                SELECT COUNT(*)
                FROM structured_generation_work_item w
                JOIN JSON_TABLE(w.allowed_evidence_keys_json, '$[*]'
                    COLUMNS (evidence_key VARCHAR(255) PATH '$')) AS evidence
                LEFT JOIN material_inventory_unit u ON u.task_id = w.task_id AND u.unit_id = evidence.evidence_key
                LEFT JOIN material_inventory_document d ON d.task_id = u.task_id AND d.document_id = u.document_id
                WHERE w.task_id = ? AND w.operation_name = 'REQUIREMENT_MATERIAL_REVIEW'
                  AND w.status = 'COMPLETED' AND w.accepted_result_sha256 IS NOT NULL
                  AND (u.unit_id IS NULL OR d.document_role = 'FUNCTION_LIST')
                """, taskId);
        return reviewUnits > 0 && coveredReferences == reviewUnits
                && distinctCoveredReferences == reviewUnits && invalidReferences == 0;
    }

    /**
     * Rebuilds the task-local key/evidence registry from the complete persisted inventory and
     * accepted rows. It never invents ownership coordinates and fails closed on duplicate unit IDs.
     *
     * [Req-ID]: REQ-FSC-008
     */
    public StructuredValidationRegistry persistedValidationRegistry(String taskId) {
        require(taskId, "taskId");
        if (!hasCompletedReviewStage(taskId)) {
            throw new IllegalStateException("Persisted material review stage is not complete");
        }
        StructuredValidationRegistry registry = StructuredValidationRegistry.forTask(taskId);
        jdbc.query("""
                SELECT document_id FROM material_inventory_document
                WHERE task_id = ? ORDER BY document_id
                """, (org.springframework.jdbc.core.RowCallbackHandler) row ->
                        registry.register(StructuredKeyType.MATERIAL, row.getString("document_id")), taskId);
        jdbc.query("""
                SELECT unit_id, document_id FROM material_inventory_unit
                WHERE task_id = ? ORDER BY document_id, ordinal, unit_id
                """, (org.springframework.jdbc.core.RowCallbackHandler) row ->
                        registry.registerEvidence(new com.testcaseagent.validation.StructuredEvidence(
                        row.getString("unit_id"), taskId, row.getString("document_id"), false, false, true)), taskId);
        AcceptedInputs accepted = acceptedInputs(taskId);
        accepted.facts().forEach(fact -> registry.requireOrRegister(StructuredKeyType.REQUIREMENT_FACT, fact.factKey()));
        accepted.functionItems().forEach(item ->
                registry.requireOrRegister(StructuredKeyType.FUNCTION_LIST_ITEM, item.itemKey()));
        return registry;
    }

    /**
     * Freezes one V2 reconciliation run and its initial owner windows before any model page is accepted.
     *
     * <p>The run row is the durable comparison identity. Re-registering the same plan is idempotent, while a
     * different catalog or initial partition under the same work identity fails closed. Page rows are staging
     * metadata only and do not make a reconciliation visible to task detail or export readers.</p>
     *
     * [Req-ID]: REQ-FSC-008
     */
    public void initializeReconciliationRun(WorkClaim claim, ReconciliationRunPlan plan) {
        requireClaim(claim);
        ReconciliationRunPlan checked = Objects.requireNonNull(plan, "plan must not be null");
        List<ReconciliationSourceRef> catalogSources = validateRunPlan(checked);
        transaction.executeWithoutResult(status -> {
            FrozenWork frozen = lockedWork(claim.workItemId());
            verifyReconciliationClaim(claim, frozen);
            verifyRunning(claim, frozen);
            jdbc.update("""
                    INSERT INTO structured_reconciliation_run
                    (work_item_id, task_id, run_key, catalog_sha256, function_item_count, requirement_fact_count,
                     catalog_source_refs_json, initial_page_keys_json, status)
                    VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON), 'STAGING')
                    ON DUPLICATE KEY UPDATE work_item_id = work_item_id
                    """, claim.workItemId(), claim.taskId(), checked.run().runKey(), checked.run().catalogSha256(),
                    checked.run().functionItemCount(), checked.run().requirementFactCount(), jsonSourceRefs(catalogSources),
                    json(checked.initialOwnerWindows().stream().map(ReconciliationOwnerWindow::pageKey).toList()));
            ReconciliationRunRow stored = lockedReconciliationRun(claim.workItemId());
            if (!stored.matches(checked.run(), catalogSources,
                    checked.initialOwnerWindows().stream().map(ReconciliationOwnerWindow::pageKey).toList())) {
                throw new IllegalStateException("Reconciliation run conflicts with frozen catalog or initial pages");
            }
            for (ReconciliationOwnerWindow window : checked.initialOwnerWindows()) {
                insertPlannedPage(claim.workItemId(), checked.run(), null, window);
            }
        });
    }

    /** Returns only unfinished V2 leaf pages; split parents and completed children are never reissued after restart. */
    public List<ReconciliationOwnerWindow> pendingReconciliationPages(
            String workItemId, String runKey, String catalogSha256) {
        require(workItemId, "workItemId"); require(runKey, "runKey"); requireHash(catalogSha256);
        requireStoredRun(workItemId, runKey, catalogSha256);
        return jdbc.query("""
                SELECT page_key, owner_source_refs_json
                FROM structured_reconciliation_page_stage
                WHERE work_item_id = ? AND run_key = ? AND catalog_sha256 = ? AND status = 'PLANNED'
                ORDER BY CASE first_source_type WHEN 'function_list_item' THEN 0 ELSE 1 END,
                         BINARY first_source_key, page_key
                """, (row, ignored) -> new ReconciliationOwnerWindow(row.getString("page_key"),
                sourceRefs(row.getString("owner_source_refs_json"))), workItemId, runKey, catalogSha256);
    }

    /** Returns the current deterministic leaf partition, including both planned and completed children. */
    public List<ReconciliationPageProgress> reconciliationLeafPages(
            String workItemId, String runKey, String catalogSha256) {
        require(workItemId, "workItemId"); require(runKey, "runKey"); requireHash(catalogSha256);
        requireStoredRun(workItemId, runKey, catalogSha256);
        return jdbc.query("""
                SELECT page_key, owner_source_refs_json, status
                FROM structured_reconciliation_page_stage
                WHERE work_item_id = ? AND run_key = ? AND catalog_sha256 = ? AND status <> 'SPLIT'
                ORDER BY CASE first_source_type WHEN 'function_list_item' THEN 0 ELSE 1 END,
                         BINARY first_source_key, page_key
                """, (row, ignored) -> new ReconciliationPageProgress(
                new ReconciliationOwnerWindow(row.getString("page_key"),
                        sourceRefs(row.getString("owner_source_refs_json"))), row.getString("status")),
                workItemId, runKey, catalogSha256);
    }

    /** Returns deterministic completed page identities for coordinator recovery without exposing staged business text. */
    public List<String> completedReconciliationPageKeys(String workItemId, String runKey, String catalogSha256) {
        require(workItemId, "workItemId"); require(runKey, "runKey"); requireHash(catalogSha256);
        requireStoredRun(workItemId, runKey, catalogSha256);
        return jdbc.queryForList("""
                SELECT page_key FROM structured_reconciliation_page_stage
                WHERE work_item_id = ? AND run_key = ? AND catalog_sha256 = ? AND status = 'COMPLETED'
                ORDER BY CASE first_source_type WHEN 'function_list_item' THEN 0 ELSE 1 END,
                         BINARY first_source_key, page_key
                """, String.class, workItemId, runKey, catalogSha256);
    }

    /** Rebuilds validated completed page projections so a restarted coordinator can rerun global closure checks. */
    public List<ReconciliationPageStage> stagedReconciliationPages(
            String workItemId, String runKey, String catalogSha256) {
        require(workItemId, "workItemId"); require(runKey, "runKey"); requireHash(catalogSha256);
        ReconciliationRunRow run = requireStoredRun(workItemId, runKey, catalogSha256);
        ReconciliationRunIdentity identity = run.identity();
        return jdbc.query("""
                SELECT page_key, owner_source_refs_json, completed_owner_source_refs_json, result_sha256
                FROM structured_reconciliation_page_stage
                WHERE work_item_id = ? AND run_key = ? AND catalog_sha256 = ? AND status = 'COMPLETED'
                ORDER BY CASE first_source_type WHEN 'function_list_item' THEN 0 ELSE 1 END,
                         BINARY first_source_key, page_key
                """, (row, ignored) -> {
                    ReconciliationOwnerWindow window = new ReconciliationOwnerWindow(row.getString("page_key"),
                            sourceRefs(row.getString("owner_source_refs_json")));
                    return new ReconciliationPageStage(identity, window,
                            sourceRefs(row.getString("completed_owner_source_refs_json")),
                            stagedRelations(workItemId, row.getString("page_key")), row.getString("result_sha256"));
                }, workItemId, runKey, catalogSha256);
    }

    /**
     * Atomically replaces one oversized planned page with its two deterministic children.
     *
     * <p>The exact ordered child concatenation must equal the parent window. Persisting the SPLIT state and both
     * children in one transaction prevents a restart from calling the parent again after one child succeeds.</p>
     *
     * [Req-ID]: REQ-FSC-008
     */
    public void splitReconciliationPage(WorkClaim claim, ReconciliationRunIdentity run, String parentPageKey,
            ReconciliationOwnerWindow left, ReconciliationOwnerWindow right) {
        requireClaim(claim); Objects.requireNonNull(run, "run must not be null"); requireHash(parentPageKey);
        ReconciliationOwnerWindow checkedLeft = Objects.requireNonNull(left, "left must not be null");
        ReconciliationOwnerWindow checkedRight = Objects.requireNonNull(right, "right must not be null");
        if (checkedLeft.pageKey().equals(checkedRight.pageKey()) || parentPageKey.equals(checkedLeft.pageKey())
                || parentPageKey.equals(checkedRight.pageKey())) {
            throw new IllegalArgumentException("Split page identities must be distinct");
        }
        transaction.executeWithoutResult(status -> {
            FrozenWork frozen = lockedWork(claim.workItemId());
            verifyReconciliationClaim(claim, frozen); verifyRunning(claim, frozen);
            ReconciliationRunRow storedRun = lockedReconciliationRun(claim.workItemId());
            requireSameRun(storedRun, run);
            ReconciliationPageRow parent = lockedPage(claim.workItemId(), parentPageKey);
            List<ReconciliationSourceRef> children = new ArrayList<>(checkedLeft.ownerSourceRefs());
            children.addAll(checkedRight.ownerSourceRefs());
            if (!children.equals(parent.ownerSourceRefs())) {
                throw new IllegalArgumentException("Split children must exactly partition the parent owner window");
            }
            if ("SPLIT".equals(parent.status())) {
                assertPlannedPage(claim.workItemId(), run, parentPageKey, checkedLeft);
                assertPlannedPage(claim.workItemId(), run, parentPageKey, checkedRight);
                return;
            }
            if (!"PLANNED".equals(parent.status())) {
                throw new IllegalStateException("Only a planned reconciliation page can be split");
            }
            if (jdbc.update("""
                    UPDATE structured_reconciliation_page_stage SET status = 'SPLIT'
                    WHERE work_item_id = ? AND page_key = ? AND status = 'PLANNED'
                    """, claim.workItemId(), parentPageKey) != 1) {
                throw new IllegalStateException("Reconciliation page split lost its planned state");
            }
            insertPlannedPage(claim.workItemId(), run, parentPageKey, checkedLeft);
            insertPlannedPage(claim.workItemId(), run, parentPageKey, checkedRight);
        });
    }

    /**
     * Persists one already-validated V2 page as non-business staging data.
     * Concurrent identical delivery is idempotent; any changed hash, relation, or owner closure under the same
     * page key is rejected while the work remains running.
     *
     * [Req-ID]: REQ-FSC-008
     */
    public void stageReconciliationPage(WorkClaim claim, ReconciliationPageStage page) {
        requireClaim(claim);
        ReconciliationPageStage checked = Objects.requireNonNull(page, "page must not be null");
        validatePageStage(checked);
        transaction.executeWithoutResult(status -> {
            FrozenWork frozen = lockedWork(claim.workItemId());
            verifyReconciliationClaim(claim, frozen); verifyRunning(claim, frozen);
            ReconciliationRunRow storedRun = lockedReconciliationRun(claim.workItemId());
            requireSameRun(storedRun, checked.run());
            ReconciliationPageRow storedPage = lockedPage(claim.workItemId(), checked.ownerWindow().pageKey());
            if (!storedPage.matches(checked.run(), checked.ownerWindow())) {
                throw new IllegalStateException("Reconciliation page conflicts with its frozen owner window");
            }
            if ("COMPLETED".equals(storedPage.status())) {
                ReconciliationPageStage persisted = new ReconciliationPageStage(checked.run(), checked.ownerWindow(),
                        storedPage.completedOwnerSourceRefs(), stagedRelations(claim.workItemId(), storedPage.pageKey()),
                        storedPage.resultSha256());
                if (!persisted.equals(checked)) {
                    throw new IllegalStateException("Completed reconciliation page conflicts with staged content");
                }
                return;
            }
            if (!"PLANNED".equals(storedPage.status())) {
                throw new IllegalStateException("Only a planned reconciliation page can be completed");
            }
            for (ReconciliationRelation relation : checked.relations()) {
                insertStagedRelation(claim.workItemId(), storedPage.pageKey(), checked.run().runKey(),
                        checked.ownerWindow(), relation);
            }
            if (jdbc.update("""
                    UPDATE structured_reconciliation_page_stage
                    SET status = 'COMPLETED', completed_owner_source_refs_json = CAST(? AS JSON),
                        result_sha256 = ?, completed_at = ?
                    WHERE work_item_id = ? AND page_key = ? AND status = 'PLANNED'
                    """, jsonSourceRefs(checked.completedOwnerSourceRefs()), checked.resultSha256(), clock.instant(),
                    claim.workItemId(), storedPage.pageKey()) != 1) {
                throw new IllegalStateException("Reconciliation page completion lost its planned state");
            }
        });
    }

    /**
     * Publishes a completely validated V2 run in one transaction.
     *
     * <p>Relations, overlapping source/evidence bindings, and one terminal ledger row per catalog source become
     * visible together. A missing page, mixed run, duplicate task identity, or database conflict rolls the whole
     * transaction back, including attempt/work completion.</p>
     *
     * [Req-ID]: REQ-FSC-008
     */
    public void publishReconciliationRun(WorkClaim claim, ReconciliationRunPublication publication) {
        requireClaim(claim);
        ReconciliationRunPublication checked = Objects.requireNonNull(publication, "publication must not be null");
        transaction.executeWithoutResult(status -> {
            FrozenWork frozen = lockedWork(claim.workItemId());
            verifyReconciliationClaim(claim, frozen);
            if ("COMPLETED".equals(frozen.status())) {
                if (checked.acceptedResultSha256().equals(frozen.acceptedResultSha256())
                        && "PUBLISHED".equals(lockedReconciliationRun(claim.workItemId()).status())) return;
                throw new IllegalStateException("Completed V2 reconciliation work has a different accepted result");
            }
            verifyRunning(claim, frozen);
            ReconciliationRunRow run = lockedReconciliationRun(claim.workItemId());
            requireSameRun(run, checked.run());
            if (!run.catalogSources().equals(checked.catalogSources())) {
                throw new IllegalStateException("Published catalog sources conflict with the frozen run");
            }
            List<ReconciliationPageRow> leaves = leafPagesForUpdate(claim.workItemId());
            if (leaves.stream().anyMatch(page -> !"COMPLETED".equals(page.status()))) {
                throw new IllegalStateException("All reconciliation owner pages must complete before publication");
            }
            List<String> completedPageKeys = leaves.stream().map(ReconciliationPageRow::pageKey).toList();
            if (!completedPageKeys.equals(checked.expectedPageKeys())) {
                throw new IllegalStateException("Published page closure conflicts with durable leaf pages");
            }
            List<ReconciliationSourceRef> completedOwners = leaves.stream()
                    .flatMap(page -> page.completedOwnerSourceRefs().stream()).toList();
            if (!completedOwners.equals(run.catalogSources())) {
                throw new IllegalStateException("Completed owner windows do not exactly cover the frozen catalog");
            }
            List<ReconciliationRelation> staged = stagedRelations(claim.workItemId());
            if (!staged.equals(checked.relations())) {
                throw new IllegalStateException("Published relations conflict with completed page staging");
            }
            Set<ReconciliationSourceRef> referenced = new HashSet<>();
            staged.forEach(relation -> referenced.addAll(relationSourceRefs(relation)));
            if (!referenced.equals(Set.copyOf(run.catalogSources()))) {
                throw new IllegalStateException("Published relations do not cover every frozen catalog source");
            }
            if (count("SELECT COUNT(*) FROM structured_feature_reconciliation WHERE work_item_id = ?", claim.workItemId()) != 0
                    || count("SELECT COUNT(*) FROM structured_reconciliation_source_terminal WHERE work_item_id = ?", claim.workItemId()) != 0) {
                throw new IllegalStateException("V2 reconciliation work already has partial business rows");
            }
            for (ReconciliationRelation relation : staged) {
                insertTaskScoped("V2 feature reconciliation", () -> jdbc.update("""
                        INSERT INTO structured_feature_reconciliation
                        (work_item_id, task_id, reconciliation_key, protocol_version, run_key,
                         owner_source_type, owner_source_key, classification, scope_recommendation, confirmation_status)
                        VALUES (?, ?, ?, '2', ?, ?, ?, ?, ?, ?)
                        """, claim.workItemId(), claim.taskId(), relation.reconciliationKey(), run.runKey(),
                        relation.ownerSourceRef().sourceType(), relation.ownerSourceRef().sourceKey(),
                        relation.classification().toUpperCase(java.util.Locale.ROOT), relation.scopeRecommendation(),
                        relation.confirmationStatus().toUpperCase(java.util.Locale.ROOT)));
                bind(claim.workItemId(), relation.reconciliationKey(), "RECONCILIATION", "FUNCTION_LIST_ITEM",
                        relation.functionListItemKeys());
                bind(claim.workItemId(), relation.reconciliationKey(), "RECONCILIATION", "REQUIREMENT_FACT",
                        relation.requirementFactKeys());
                bind(claim.workItemId(), relation.reconciliationKey(), "RECONCILIATION", "EVIDENCE", relation.evidenceKeys());
            }
            for (ReconciliationSourceRef source : run.catalogSources()) {
                insertTaskScoped("V2 reconciliation source terminal", () -> jdbc.update("""
                        INSERT INTO structured_reconciliation_source_terminal
                        (task_id, source_type, source_key, work_item_id, run_key) VALUES (?, ?, ?, ?, ?)
                        """, claim.taskId(), source.sourceType(), source.sourceKey(), claim.workItemId(), run.runKey()));
            }
            if (jdbc.update("""
                    UPDATE structured_reconciliation_run
                    SET status = 'PUBLISHED', accepted_result_sha256 = ?
                    WHERE work_item_id = ? AND status = 'STAGING'
                    """, checked.acceptedResultSha256(), claim.workItemId()) != 1) {
                throw new IllegalStateException("Reconciliation run is no longer publishable");
            }
            if (jdbc.update("UPDATE structured_generation_attempt SET status = 'COMPLETED', completed_at = ? WHERE id = ? AND status = 'RUNNING'",
                    clock.instant(), claim.attemptId()) != 1) {
                throw new IllegalStateException("Structured attempt is no longer running");
            }
            if (jdbc.update("""
                    UPDATE structured_generation_work_item
                    SET status = 'COMPLETED', accepted_result_sha256 = ?, lease_owner = NULL, lease_expires_at = NULL
                    WHERE id = ? AND status = 'RUNNING'
                    """, checked.acceptedResultSha256(), claim.workItemId()) != 1) {
                throw new IllegalStateException("Structured work item is no longer running");
            }
        });
    }

    private List<ReconciliationSourceRef> validateRunPlan(ReconciliationRunPlan plan) {
        List<ReconciliationSourceRef> sources = plan.initialOwnerWindows().stream()
                .flatMap(window -> window.ownerSourceRefs().stream()).toList();
        requireCanonicalSourceRefs(sources, "initial owner windows");
        long functionItems = sources.stream().filter(ref -> "function_list_item".equals(ref.sourceType())).count();
        long requirementFacts = sources.size() - functionItems;
        if (functionItems != plan.run().functionItemCount() || requirementFacts != plan.run().requirementFactCount()) {
            throw new IllegalArgumentException("Initial owner windows must exactly cover the declared catalog counts");
        }
        for (ReconciliationOwnerWindow window : plan.initialOwnerWindows()) {
            requirePageKey(plan.run().runKey(), window);
        }
        return sources;
    }

    private void validatePageStage(ReconciliationPageStage page) {
        requirePageKey(page.run().runKey(), page.ownerWindow());
        if (!page.completedOwnerSourceRefs().equals(page.ownerWindow().ownerSourceRefs())) {
            throw new IllegalArgumentException("Completed owner echo must equal the frozen owner window");
        }
        for (ReconciliationRelation relation : page.relations()) {
            List<ReconciliationSourceRef> relationSources = relationSourceRefs(relation);
            ReconciliationSourceRef owner = relationSources.get(0);
            if (!owner.equals(relation.ownerSourceRef())
                    || !page.ownerWindow().ownerSourceRefs().contains(owner)) {
                throw new IllegalArgumentException("Relation owner must be canonical and belong to the current owner window");
            }
            String expectedKey = expectedRelationKey(page.run().runKey(), relation, relationSources);
            if (!expectedKey.equals(relation.reconciliationKey())) {
                throw new IllegalArgumentException("Relation key does not match the frozen V2 derivation");
            }
        }
    }

    private void verifyReconciliationClaim(WorkClaim claim, FrozenWork frozen) {
        verifyClaim(claim, frozen, frozen.operationName());
        if (!"feature-scope-reconciliation".equals(frozen.skillName())
                || !RECONCILIATION_V2_OPERATION.equals(frozen.operationName())) {
            throw new IllegalStateException("Structured work is not a V2 feature reconciliation");
        }
    }

    private ReconciliationRunRow requireStoredRun(String workItemId, String runKey, String catalogSha256) {
        List<ReconciliationRunRow> rows = reconciliationRunRows("""
                SELECT work_item_id, task_id, run_key, catalog_sha256, function_item_count, requirement_fact_count,
                       catalog_source_refs_json, initial_page_keys_json, status, accepted_result_sha256
                FROM structured_reconciliation_run WHERE work_item_id = ?
                """, workItemId);
        if (rows.isEmpty()) throw new IllegalStateException("Reconciliation run does not exist");
        ReconciliationRunRow row = rows.get(0);
        if (!row.runKey().equals(runKey) || !row.catalogSha256().equals(catalogSha256)) {
            throw new IllegalStateException("Reconciliation run identity conflicts with stored state");
        }
        return row;
    }

    private ReconciliationRunRow lockedReconciliationRun(String workItemId) {
        List<ReconciliationRunRow> rows = reconciliationRunRows("""
                SELECT work_item_id, task_id, run_key, catalog_sha256, function_item_count, requirement_fact_count,
                       catalog_source_refs_json, initial_page_keys_json, status, accepted_result_sha256
                FROM structured_reconciliation_run WHERE work_item_id = ? FOR UPDATE
                """, workItemId);
        if (rows.isEmpty()) throw new IllegalStateException("Reconciliation run does not exist");
        return rows.get(0);
    }

    private List<ReconciliationRunRow> reconciliationRunRows(String sql, String workItemId) {
        return jdbc.query(sql, (row, ignored) -> new ReconciliationRunRow(
                row.getString("work_item_id"), row.getString("task_id"), row.getString("run_key"),
                row.getString("catalog_sha256"), row.getInt("function_item_count"), row.getInt("requirement_fact_count"),
                sourceRefs(row.getString("catalog_source_refs_json")), stringList(row.getString("initial_page_keys_json")),
                row.getString("status"), row.getString("accepted_result_sha256")), workItemId);
    }

    private void requireSameRun(ReconciliationRunRow stored, ReconciliationRunIdentity supplied) {
        if (!stored.runKey().equals(supplied.runKey()) || !stored.catalogSha256().equals(supplied.catalogSha256())
                || stored.functionItemCount() != supplied.functionItemCount()
                || stored.requirementFactCount() != supplied.requirementFactCount()) {
            throw new IllegalStateException("Reconciliation page uses a different frozen run");
        }
    }

    private void insertPlannedPage(String workItemId, ReconciliationRunIdentity run, String parentPageKey,
            ReconciliationOwnerWindow window) {
        requirePageKey(run.runKey(), window);
        ReconciliationSourceRef first = window.ownerSourceRefs().get(0);
        jdbc.update("""
                INSERT INTO structured_reconciliation_page_stage
                (work_item_id, page_key, run_key, catalog_sha256, parent_page_key, status,
                 first_source_type, first_source_key, owner_source_refs_json)
                VALUES (?, ?, ?, ?, ?, 'PLANNED', ?, ?, CAST(? AS JSON))
                ON DUPLICATE KEY UPDATE page_key = page_key
                """, workItemId, window.pageKey(), run.runKey(), run.catalogSha256(), parentPageKey,
                first.sourceType(), first.sourceKey(), jsonSourceRefs(window.ownerSourceRefs()));
        assertPlannedPage(workItemId, run, parentPageKey, window);
    }

    private void assertPlannedPage(String workItemId, ReconciliationRunIdentity run, String parentPageKey,
            ReconciliationOwnerWindow window) {
        ReconciliationPageRow stored = lockedPage(workItemId, window.pageKey());
        if (!stored.matches(run, window) || !Objects.equals(parentPageKey, stored.parentPageKey())) {
            throw new IllegalStateException("Reconciliation page identity conflicts with durable planning state");
        }
    }

    private ReconciliationPageRow lockedPage(String workItemId, String pageKey) {
        List<ReconciliationPageRow> rows = jdbc.query("""
                SELECT page_key, run_key, catalog_sha256, parent_page_key, status, owner_source_refs_json,
                       completed_owner_source_refs_json, result_sha256
                FROM structured_reconciliation_page_stage
                WHERE work_item_id = ? AND page_key = ? FOR UPDATE
                """, (row, ignored) -> pageRow(row.getString("page_key"), row.getString("run_key"),
                row.getString("catalog_sha256"), row.getString("parent_page_key"), row.getString("status"),
                row.getString("owner_source_refs_json"), row.getString("completed_owner_source_refs_json"),
                row.getString("result_sha256")), workItemId, pageKey);
        if (rows.isEmpty()) throw new IllegalStateException("Reconciliation page is not part of the frozen run");
        return rows.get(0);
    }

    private List<ReconciliationPageRow> leafPagesForUpdate(String workItemId) {
        return jdbc.query("""
                SELECT page_key, run_key, catalog_sha256, parent_page_key, status, owner_source_refs_json,
                       completed_owner_source_refs_json, result_sha256
                FROM structured_reconciliation_page_stage
                WHERE work_item_id = ? AND status <> 'SPLIT'
                ORDER BY CASE first_source_type WHEN 'function_list_item' THEN 0 ELSE 1 END,
                         BINARY first_source_key, page_key
                FOR UPDATE
                """, (row, ignored) -> pageRow(row.getString("page_key"), row.getString("run_key"),
                row.getString("catalog_sha256"), row.getString("parent_page_key"), row.getString("status"),
                row.getString("owner_source_refs_json"), row.getString("completed_owner_source_refs_json"),
                row.getString("result_sha256")), workItemId);
    }

    private ReconciliationPageRow pageRow(String pageKey, String runKey, String catalogSha256, String parentPageKey,
            String status, String ownerJson, String completedJson, String resultSha256) {
        return new ReconciliationPageRow(pageKey, runKey, catalogSha256, parentPageKey, status,
                sourceRefs(ownerJson), completedJson == null ? List.of() : sourceRefs(completedJson), resultSha256);
    }

    private void insertStagedRelation(String workItemId, String pageKey, String runKey,
            ReconciliationOwnerWindow window, ReconciliationRelation relation) {
        List<ReconciliationSourceRef> relationSources = relationSourceRefs(relation);
        if (!relation.ownerSourceRef().equals(relationSources.get(0))
                || !window.ownerSourceRefs().contains(relation.ownerSourceRef())
                || !expectedRelationKey(runKey, relation, relationSources).equals(relation.reconciliationKey())) {
            throw new IllegalArgumentException("Staged relation has an invalid canonical identity");
        }
        try {
            jdbc.update("""
                    INSERT INTO structured_reconciliation_relation_stage
                    (work_item_id, page_key, reconciliation_key, owner_source_type, owner_source_key,
                     classification, scope_recommendation, confirmation_status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, workItemId, pageKey, relation.reconciliationKey(), relation.ownerSourceRef().sourceType(),
                    relation.ownerSourceRef().sourceKey(), relation.classification(), relation.scopeRecommendation(),
                    relation.confirmationStatus());
        } catch (DuplicateKeyException conflict) {
            throw new IllegalStateException("Reconciliation relation identity conflicts across staged pages", conflict);
        }
        stageBindings(workItemId, relation.reconciliationKey(), "FUNCTION_LIST_ITEM", relation.functionListItemKeys());
        stageBindings(workItemId, relation.reconciliationKey(), "REQUIREMENT_FACT", relation.requirementFactKeys());
        stageBindings(workItemId, relation.reconciliationKey(), "EVIDENCE", relation.evidenceKeys());
    }

    private void stageBindings(String workItemId, String reconciliationKey, String referenceType, List<String> references) {
        for (String reference : references) {
            jdbc.update("""
                    INSERT INTO structured_reconciliation_relation_stage_binding
                    (work_item_id, reconciliation_key, reference_type, reference_key) VALUES (?, ?, ?, ?)
                    """, workItemId, reconciliationKey, referenceType, reference);
        }
    }

    private List<ReconciliationRelation> stagedRelations(String workItemId) {
        return stagedRelationRows(workItemId, null).stream().map(row -> stagedRelation(workItemId, row)).toList();
    }

    private List<ReconciliationRelation> stagedRelations(String workItemId, String pageKey) {
        return stagedRelationRows(workItemId, pageKey).stream().map(row -> stagedRelation(workItemId, row)).toList();
    }

    private List<StagedRelationRow> stagedRelationRows(String workItemId, String pageKey) {
        String sql = """
                SELECT page_key, reconciliation_key, owner_source_type, owner_source_key,
                       classification, scope_recommendation, confirmation_status
                FROM structured_reconciliation_relation_stage
                WHERE work_item_id = ?
                """ + (pageKey == null ? "" : " AND page_key = ?") + " ORDER BY reconciliation_key";
        Object[] arguments = pageKey == null ? new Object[] {workItemId} : new Object[] {workItemId, pageKey};
        return jdbc.query(sql, (row, ignored) -> new StagedRelationRow(row.getString("page_key"),
                row.getString("reconciliation_key"), row.getString("owner_source_type"), row.getString("owner_source_key"),
                row.getString("classification"), row.getString("scope_recommendation"), row.getString("confirmation_status")), arguments);
    }

    private ReconciliationRelation stagedRelation(String workItemId, StagedRelationRow row) {
        return new ReconciliationRelation(row.reconciliationKey(),
                new ReconciliationSourceRef(row.ownerSourceType(), row.ownerSourceKey()),
                stagedReferenceKeys(workItemId, row.reconciliationKey(), "FUNCTION_LIST_ITEM"),
                stagedReferenceKeys(workItemId, row.reconciliationKey(), "REQUIREMENT_FACT"),
                row.classification(), stagedReferenceKeys(workItemId, row.reconciliationKey(), "EVIDENCE"),
                row.scopeRecommendation(), row.confirmationStatus());
    }

    private List<String> stagedReferenceKeys(String workItemId, String reconciliationKey, String referenceType) {
        return jdbc.queryForList("""
                SELECT reference_key FROM structured_reconciliation_relation_stage_binding
                WHERE work_item_id = ? AND reconciliation_key = ? AND reference_type = ?
                """, String.class, workItemId, reconciliationKey, referenceType).stream()
                .sorted(StructuredGenerationAcceptanceStore::compareUtf8).toList();
    }

    private List<ReconciliationSourceRef> relationSourceRefs(ReconciliationRelation relation) {
        List<ReconciliationSourceRef> refs = new ArrayList<>();
        relation.functionListItemKeys().forEach(key -> refs.add(new ReconciliationSourceRef("function_list_item", key)));
        relation.requirementFactKeys().forEach(key -> refs.add(new ReconciliationSourceRef("requirement_fact", key)));
        requireCanonicalSourceRefs(refs, "relation source references");
        return List.copyOf(refs);
    }

    private void requirePageKey(String runKey, ReconciliationOwnerWindow window) {
        // Identity bytes must stay independent from ObjectMapper settings and match Go encoding/json.
        String expected = FeatureScopeReconciliationV2Canonicalizer.pageKey(
                runKey, wireSourceRefs(window.ownerSourceRefs()));
        if (!expected.equals(window.pageKey())) {
            throw new IllegalArgumentException("Page key does not match the frozen V2 derivation");
        }
    }

    private String expectedRelationKey(String runKey, ReconciliationRelation relation,
            List<ReconciliationSourceRef> sources) {
        return FeatureScopeReconciliationV2Canonicalizer.reconciliationKey(
                runKey,
                FeatureScopeReconciliationResult.Classification.fromWire(relation.classification()),
                FeatureScopeReconciliationResult.ConfirmationStatus.fromWire(relation.confirmationStatus()),
                wireSourceRefs(sources));
    }

    private static List<FeatureScopeReconciliationPageInput.SourceRef> wireSourceRefs(
            List<ReconciliationSourceRef> refs) {
        return refs.stream().map(ref -> new FeatureScopeReconciliationPageInput.SourceRef(
                FeatureScopeReconciliationPageInput.SourceType.fromWire(ref.sourceType()), ref.sourceKey())).toList();
    }

    private String sha256Text(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private String jsonSourceRefs(List<ReconciliationSourceRef> refs) {
        List<Map<String, String>> values = refs.stream().map(ref -> {
            Map<String, String> value = new LinkedHashMap<>();
            value.put("source_type", ref.sourceType());
            value.put("source_key", ref.sourceKey());
            return value;
        }).toList();
        return json(values);
    }

    private List<ReconciliationSourceRef> sourceRefs(String value) {
        try {
            JsonNode root = objectMapper.readTree(value);
            if (root == null || !root.isArray()) throw new IllegalStateException("Stored reconciliation source closure is not an array");
            List<ReconciliationSourceRef> refs = new ArrayList<>();
            for (JsonNode node : root) {
                refs.add(new ReconciliationSourceRef(node.path("source_type").asText(), node.path("source_key").asText()));
            }
            requireCanonicalSourceRefs(refs, "stored reconciliation source closure");
            return List.copyOf(refs);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored reconciliation source closure cannot be read", exception);
        }
    }

    private static void requireCanonicalSourceRefs(List<ReconciliationSourceRef> refs, String name) {
        List<ReconciliationSourceRef> checked = List.copyOf(Objects.requireNonNull(refs, name + " must not be null"));
        if (checked.isEmpty()) throw new IllegalArgumentException(name + " must not be empty");
        List<ReconciliationSourceRef> sorted = checked.stream().sorted(SOURCE_REF_ORDER).toList();
        if (!checked.equals(sorted) || new HashSet<>(checked).size() != checked.size()) {
            throw new IllegalArgumentException(name + " must be unique and canonically ordered");
        }
    }

    private static List<String> checkedSortedKeys(List<String> values, String name, boolean allowEmpty) {
        List<String> checked = List.copyOf(Objects.requireNonNull(values, name + " must not be null"));
        for (String value : checked) require(value, name + " member");
        if (!allowEmpty && checked.isEmpty()) throw new IllegalArgumentException(name + " must not be empty");
        if (!checked.equals(checked.stream().sorted(StructuredGenerationAcceptanceStore::compareUtf8).toList())
                || new HashSet<>(checked).size() != checked.size()) {
            throw new IllegalArgumentException(name + " must be unique and sorted");
        }
        return checked;
    }

    private static List<ReconciliationRelation> canonicalRelations(List<ReconciliationRelation> relations) {
        List<ReconciliationRelation> checked = List.copyOf(Objects.requireNonNull(relations, "relations must not be null"));
        List<ReconciliationRelation> sorted = checked.stream()
                .peek(relation -> Objects.requireNonNull(relation, "relation must not be null"))
                .sorted(Comparator.comparing(ReconciliationRelation::reconciliationKey)).toList();
        if (new HashSet<>(sorted.stream().map(ReconciliationRelation::reconciliationKey).toList()).size() != sorted.size()) {
            throw new IllegalArgumentException("relation keys must be unique");
        }
        return sorted;
    }

    private static int compareUtf8(String left, String right) {
        byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
        byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
        int common = Math.min(leftBytes.length, rightBytes.length);
        for (int index = 0; index < common; index++) {
            int compared = Integer.compare(Byte.toUnsignedInt(leftBytes[index]), Byte.toUnsignedInt(rightBytes[index]));
            if (compared != 0) return compared;
        }
        return Integer.compare(leftBytes.length, rightBytes.length);
    }

    private static void validateCandidateWindow(FunctionCandidateExtractionValidator.ValidatedWindow window) {
        requireHash(window.windowKey());
        if (window.sourceOutcomes().isEmpty()) {
            throw new IllegalArgumentException("Candidate window must contain source outcomes");
        }
        Map<String, FunctionCandidateExtractionValidator.ValidatedCandidate> candidates = new LinkedHashMap<>();
        for (FunctionCandidateExtractionValidator.ValidatedCandidate candidate : window.candidates()) {
            Objects.requireNonNull(candidate, "candidate must not be null");
            requireHash(candidate.candidateRef());
            require(candidate.path(), "candidate path");
            require(candidate.description(), "candidate description");
            require(candidate.targetQuote(), "candidate targetQuote");
            require(candidate.reasonCode(), "candidate reasonCode");
            Objects.requireNonNull(candidate.recommendedStatus(), "candidate recommendedStatus must not be null");
            Objects.requireNonNull(candidate.finalDecision(), "candidate finalDecision must not be null");
            if (candidate.recommendedStatus()
                    == com.testcaseagent.knowledgeagent.FunctionCandidateExtractionResult.RecommendedStatus.PENDING_CONFIRMATION
                    && candidate.finalDecision() == FunctionCandidateExtractionValidator.FinalDecision.ACCEPTED) {
                throw new IllegalArgumentException("Java candidate decision must not upgrade KEE evidence");
            }
            if (candidate.evidenceKeys().isEmpty()
                    || new HashSet<>(candidate.evidenceKeys()).size() != candidate.evidenceKeys().size()) {
                throw new IllegalArgumentException("Candidate evidence keys must be nonempty and unique");
            }
            if (candidates.putIfAbsent(candidate.candidateRef(), candidate) != null) {
                throw new IllegalArgumentException("candidateRef must be unique");
            }
        }

        Set<String> sourceKeys = new HashSet<>();
        for (FunctionCandidateExtractionValidator.ValidatedSourceOutcome outcome : window.sourceOutcomes()) {
            Objects.requireNonNull(outcome, "source outcome must not be null");
            require(outcome.unitKey(), "source unitKey");
            require(outcome.reasonCode(), "source reasonCode");
            Objects.requireNonNull(outcome.disposition(), "source disposition must not be null");
            if (!sourceKeys.add(outcome.unitKey())) {
                throw new IllegalArgumentException("Source outcome unit keys must be unique");
            }
            List<String> expectedRefs = candidates.values().stream()
                    .filter(candidate -> candidate.evidenceKeys().contains(outcome.unitKey()))
                    .map(FunctionCandidateExtractionValidator.ValidatedCandidate::candidateRef).toList();
            if (!expectedRefs.equals(outcome.candidateRefs())) {
                throw new IllegalArgumentException("Source candidate references do not match evidence ownership");
            }
            FunctionCandidateExtractionValidator.FinalDecision expectedDecision = sourceDecision(expectedRefs, candidates);
            if (outcome.finalDecision() != expectedDecision) {
                throw new IllegalArgumentException("Source final decision does not match candidate decisions");
            }
        }
        for (FunctionCandidateExtractionValidator.ValidatedCandidate candidate : candidates.values()) {
            if (!sourceKeys.containsAll(candidate.evidenceKeys())) {
                throw new IllegalArgumentException("Candidate evidence is outside the source outcomes");
            }
        }
    }

    private static FunctionCandidateExtractionValidator.FinalDecision sourceDecision(
            List<String> candidateRefs,
            Map<String, FunctionCandidateExtractionValidator.ValidatedCandidate> candidates) {
        if (candidateRefs.stream().map(candidates::get)
                .anyMatch(candidate -> candidate.finalDecision()
                        == FunctionCandidateExtractionValidator.FinalDecision.ACCEPTED)) {
            return FunctionCandidateExtractionValidator.FinalDecision.ACCEPTED;
        }
        if (candidateRefs.stream().map(candidates::get)
                .anyMatch(candidate -> candidate.finalDecision()
                        == FunctionCandidateExtractionValidator.FinalDecision.PENDING_CONFIRMATION)) {
            return FunctionCandidateExtractionValidator.FinalDecision.PENDING_CONFIRMATION;
        }
        return FunctionCandidateExtractionValidator.FinalDecision.REJECTED;
    }

    private void persistV2Fact(WorkClaim claim, String functionKey,
            RequirementFactV2Validator.AcceptedFact fact) {
        String identityStatement = normalizedFactText(fact.statement());
        String displayStatement = displayFactText(fact.statement());
        jdbc.update("""
                INSERT INTO v2_requirement_fact
                (task_id, fact_key, first_work_item_id, function_key, fact_type, statement_text)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE fact_key = VALUES(fact_key)
                """, claim.taskId(), fact.factKey(), claim.workItemId(), functionKey,
                fact.factType().wireValue(), displayStatement);
        Map<String, Object> stored = jdbc.queryForMap("""
                SELECT function_key, fact_type, statement_text
                FROM v2_requirement_fact WHERE task_id = ? AND fact_key = ? FOR UPDATE
                """, claim.taskId(), fact.factKey());
        if (!functionKey.equals(stored.get("function_key"))
                || !fact.factType().wireValue().equals(stored.get("fact_type"))) {
            throw new IllegalStateException("Stable V2 fact identity conflicts with its stored function or type");
        }
        String storedStatement = String.valueOf(stored.get("statement_text"));
        if (!normalizedFactText(storedStatement).equals(identityStatement)) {
            throw new IllegalStateException("Stable V2 fact identity conflicts with its normalized statement");
        }
        if (compareUtf8(displayStatement, storedStatement) < 0) {
            // Duplicate windows share one case-insensitive fact identity, while the reader-facing wording remains
            // case-preserving and deterministic regardless of completion order.
            jdbc.update("UPDATE v2_requirement_fact SET statement_text = ? WHERE task_id = ? AND fact_key = ?",
                    displayStatement, claim.taskId(), fact.factKey());
        }
        for (StructuredSourceQuoteV2 quote : fact.sourceQuotes()) {
            String quoteHash = sha256Text(quote.quote());
            jdbc.update("""
                    INSERT INTO v2_requirement_fact_quote
                    (task_id, fact_key, evidence_key, quote_sha256, quote_text)
                    VALUES (?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE quote_text = VALUES(quote_text)
                    """, claim.taskId(), fact.factKey(), quote.evidenceKey(), quoteHash, quote.quote());
        }
    }

    private static String normalizedFactText(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ").strip();
    }

    private static String displayFactText(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("\\s+", " ").strip();
    }

    private void persistV2Feedback(WorkClaim claim, String functionKey, String windowKey,
            RequirementFactV2Validator.AcceptedObservation observation) {
        String feedbackKey = "feedback-" + sha256Text("testability-feedback-v2\n" + functionKey + "\n"
                + windowKey + "\n" + observation.observationType().wireValue() + "\n"
                + observation.description());
        jdbc.update("""
                INSERT INTO v2_testability_feedback
                (task_id, feedback_key, work_item_id, function_key, window_key, observation_type,
                 description_text, affected_fact_types_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON))
                """, claim.taskId(), feedbackKey, claim.workItemId(), functionKey, windowKey,
                observation.observationType().wireValue(), observation.description(),
                json(observation.affectedFactTypes().stream().map(
                        RequirementFactExtractionV2Result.FactType::wireValue).toList()));
        for (StructuredSourceQuoteV2 quote : observation.sourceQuotes()) {
            String quoteHash = sha256Text(quote.quote());
            jdbc.update("""
                    INSERT INTO v2_testability_feedback_quote
                    (task_id, feedback_key, evidence_key, quote_sha256, quote_text)
                    VALUES (?, ?, ?, ?, ?)
                    """, claim.taskId(), feedbackKey, quote.evidenceKey(), quoteHash, quote.quote());
        }
    }

    private void persistV2Testcase(WorkClaim claim,
            FunctionalTestcaseV2Validator.AcceptedTestcase accepted) {
        FunctionalTestcaseDesignV2Result.Testcase testcase = accepted.testcase();
        jdbc.update("""
                INSERT INTO structured_test_case
                (work_item_id, task_id, case_key, name_text, title, priority, preconditions_json,
                 hardware_configuration_json, software_configuration_json, test_configuration_json,
                 parameter_configuration_json, inputs_json, expected_results_json, evaluation_criteria,
                 result_evaluation_criteria, termination_conditions_json, result_collection,
                 author_name, author_date, case_status, missing_information_json)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON), CAST(? AS JSON), CAST(? AS JSON),
                        CAST(? AS JSON), CAST(? AS JSON), CAST(? AS JSON), ?, ?, CAST(? AS JSON), ?, NULL, NULL,
                        ?, CAST(? AS JSON))
                """, claim.workItemId(), claim.taskId(), accepted.caseKey(), testcase.name(), testcase.title(),
                testcase.priority().name(), json(testcase.preconditions()),
                json(testcase.initialization().hardwareConfiguration()),
                json(testcase.initialization().softwareConfiguration()),
                json(testcase.initialization().testConfiguration()),
                json(testcase.initialization().parameterConfiguration()), json(testcase.inputs()),
                json(testcase.expectedResults()), testcase.evaluationCriteria(),
                testcase.resultEvaluationCriteria(), json(testcase.terminationConditions()),
                testcase.resultCollection(), testcase.caseStatus().name(), json(testcase.missingInformation()));
        for (FunctionalTestcaseDesignV2Result.Step step : testcase.steps()) {
            jdbc.update("""
                    INSERT INTO structured_test_case_step
                    (work_item_id, case_key, step_no, action_text, expected_text, evaluation_criteria,
                     termination_or_error, result_collection)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, claim.workItemId(), accepted.caseKey(), step.stepNo(), step.action(), step.expected(),
                    step.evaluationCriteria(), step.terminationOrError(), step.resultCollection());
        }
        bind(claim.workItemId(), accepted.caseKey(), "TEST_CASE", "REQUIREMENT_FACT",
                testcase.requirementFactKeys());
        bind(claim.workItemId(), accepted.caseKey(), "TEST_CASE", "EVIDENCE", testcase.evidenceKeys());
    }

    private void insertV2Publication(WorkClaim claim, String type, String inputSha256, String resultSha256,
            Object validatedResult) {
        jdbc.update("""
                INSERT INTO v2_work_publication
                (work_item_id, task_id, publication_type, input_sha256, result_sha256,
                  validated_result_replay_json, published_at)
                VALUES (?, ?, ?, ?, ?, CAST(? AS JSON), ?)
                """, claim.workItemId(), claim.taskId(), type, inputSha256, resultSha256,
                json(validatedResult), clock.instant());
    }

    private void accept(WorkClaim claim, Object result, String coverageStatus, String operationName, Consumer<FrozenWork> rows) {
        requireClaim(claim); String resultSha256 = sha256(result);
        transaction.executeWithoutResult(status -> {
            FrozenWork frozen = lockedWork(claim.workItemId());
            verifyClaim(claim, frozen, operationName);
            if ("COMPLETED".equals(frozen.status())) {
                if (resultSha256.equals(frozen.acceptedResultSha256())) return;
                throw new IllegalStateException("Completed work item has a different accepted result");
            }
            verifyRunning(claim, frozen);
            rows.accept(frozen);
            if (jdbc.update("UPDATE structured_generation_attempt SET status = 'COMPLETED', completed_at = ? WHERE id = ? AND status = 'RUNNING'",
                    clock.instant(), claim.attemptId()) != 1) throw new IllegalStateException("Structured attempt is no longer running");
            if (jdbc.update("""
                    UPDATE structured_generation_work_item
                    SET status = 'COMPLETED', accepted_result_sha256 = ?, coverage_status = ?, lease_owner = NULL, lease_expires_at = NULL
                    WHERE id = ? AND status = 'RUNNING'
                    """, resultSha256, coverageStatus, claim.workItemId()) != 1) {
                throw new IllegalStateException("Structured work item is no longer running");
            }
        });
    }


    private void bind(String workItemId, String subjectKey, String subjectType, String referenceType, List<String> references) {
        for (String reference : references) jdbc.update("""
                INSERT INTO structured_reference_binding (work_item_id, subject_key, subject_type, reference_type, reference_key)
                VALUES (?, ?, ?, ?, ?)
                """, workItemId, subjectKey, subjectType, referenceType, reference);
    }

    private List<String> referenceKeys(String workItemId, String subjectKey, String subjectType, String referenceType) {
        return jdbc.queryForList("""
                SELECT reference_key FROM structured_reference_binding
                WHERE work_item_id = ? AND subject_key = ? AND subject_type = ? AND reference_type = ?
                ORDER BY reference_key
                """, String.class, workItemId, subjectKey, subjectType, referenceType);
    }

    /**
     * Persists a review finding without letting a later bounded call create a second identity for one root cause.
     *
     * <p>The unique task/root-cause index is the only aggregation identity. The no-op upsert materializes (or locks)
     * that identity before the explicit locking read, so a concurrent first write cannot be protected by a gap lock.
     * A merged row retains its first verified bad example and pending proposal, while exact scope and evidence members
     * are unioned without attempting to infer whether different descriptions mean the same business issue.</p>
     */
    private void persistReviewFinding(WorkClaim claim, RequirementMaterialReviewValidator.ReviewFinding finding) {
        if (finding.rootCauseKind() == null) {
            insertTaskScoped("review finding", () -> jdbc.update("""
                    INSERT INTO structured_review_finding
                    (work_item_id, task_id, finding_key, issue_type, description, test_design_impact, current_project_recommendation,
                    design_center_guideline_recommendation, handling_level) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, claim.workItemId(), claim.taskId(), finding.findingKey(), finding.issueType(), finding.description(),
                    finding.testDesignImpact(), finding.currentProjectRecommendation(),
                    finding.designCenterGuidelineRecommendation(), finding.handlingLevel().name()));
            bind(claim.workItemId(), finding.findingKey(), "REVIEW_FINDING", "EVIDENCE", finding.evidenceKeys());
            return;
        }
        jdbc.update("""
                INSERT INTO structured_review_finding
                (work_item_id, task_id, finding_key, root_cause_kind, issue_type, description, test_design_impact,
                 current_project_recommendation, design_center_guideline_recommendation, handling_level,
                 affected_unit_keys_json, affected_scope_summary, bad_source_evidence_key, bad_source_quote,
                 proposed_good_status, proposed_good_text)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE root_cause_kind = root_cause_kind
                """, claim.workItemId(), claim.taskId(), finding.findingKey(), finding.rootCauseKind().name(),
                finding.issueType(), finding.description(), finding.testDesignImpact(), finding.currentProjectRecommendation(),
                finding.designCenterGuidelineRecommendation(), finding.handlingLevel().name(), json(finding.affectedScope().unitKeys()),
                finding.affectedScope().summary(), finding.badSourceExample().evidenceKey(), finding.badSourceExample().quote(),
                finding.proposedGoodExample().status().name(), finding.proposedGoodExample().text());
        List<StoredReviewFinding> rows = jdbc.query("""
                SELECT work_item_id, finding_key, affected_unit_keys_json, affected_scope_summary
                FROM structured_review_finding
                WHERE task_id = ? AND root_cause_kind = ? FOR UPDATE
                """, (row, ignored) -> new StoredReviewFinding(row.getString("work_item_id"), row.getString("finding_key"),
                stringList(row.getString("affected_unit_keys_json")), row.getString("affected_scope_summary")),
                claim.taskId(), finding.rootCauseKind().name());
        if (rows.size() != 1) {
            throw new IllegalStateException("Task root-cause identity conflicts with an accepted review finding");
        }
        StoredReviewFinding stored = rows.get(0);
        jdbc.update("""
                UPDATE structured_review_finding
                SET affected_unit_keys_json = CAST(? AS JSON), affected_scope_summary = ?
                WHERE work_item_id = ? AND finding_key = ?
                """, json(orderedUnion(stored.affectedUnitKeys(), finding.affectedScope().unitKeys())),
                mergeExactText(stored.affectedScopeSummary(), finding.affectedScope().summary()),
                stored.workItemId(), stored.findingKey());
        bindIdempotently(stored.workItemId(), stored.findingKey(), "REVIEW_FINDING", "EVIDENCE", finding.evidenceKeys());
    }

    private static List<String> orderedUnion(List<String> first, List<String> second) {
        LinkedHashSet<String> values = new LinkedHashSet<>(first);
        values.addAll(second);
        return values.stream().sorted().toList();
    }

    private static String mergeExactText(String first, String second) {
        return java.util.stream.Stream.concat(first.lines(), second.lines())
                .filter(value -> !value.isBlank()).distinct().sorted().collect(java.util.stream.Collectors.joining("\n"));
    }

    private int count(String sql, String value) {
        Integer result = jdbc.queryForObject(sql, Integer.class, value);
        return result == null ? 0 : result;
    }

    private int countRows(String sql, Object... arguments) {
        Integer result = jdbc.queryForObject(sql, Integer.class, arguments);
        return result == null ? 0 : result;
    }

    private void bindIdempotently(String workItemId, String subjectKey, String subjectType,
            String referenceType, List<String> references) {
        for (String reference : references) jdbc.update("""
                INSERT INTO structured_reference_binding (work_item_id, subject_key, subject_type, reference_type, reference_key)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE reference_key = VALUES(reference_key)
                """, workItemId, subjectKey, subjectType, referenceType, reference);
    }

    private String functionItemOwner(WorkClaim claim, FunctionListItem row) {
        jdbc.update("""
                INSERT INTO structured_function_list_item
                (work_item_id, task_id, item_key, path_text, description, target_quotes_json)
                VALUES (?, ?, ?, ?, ?, CAST(? AS JSON))
                ON DUPLICATE KEY UPDATE item_key = VALUES(item_key)
                """, claim.workItemId(), claim.taskId(), row.itemKey(), row.path(), row.description(),
                json(row.targetQuotes()));
        List<StoredFunctionItem> existing = jdbc.query("""
                SELECT work_item_id, path_text, description, target_quotes_json
                FROM structured_function_list_item
                WHERE task_id = ? AND item_key = ? FOR UPDATE
                """, (result, ignored) -> new StoredFunctionItem(result.getString("work_item_id"),
                result.getString("path_text"), result.getString("description"),
                stringList(result.getString("target_quotes_json"))), claim.taskId(), row.itemKey());
        if (existing.isEmpty()) throw new IllegalStateException("Task-scoped function-list item was not persisted");
        StoredFunctionItem stored = existing.get(0);
        if (!stored.path().equals(row.path()) || !stored.description().equals(row.description())) {
            throw new IllegalStateException("Stable function-list identity conflicts with persisted business text");
        }
        List<String> mergedQuotes = orderedUnion(stored.targetQuotes(), row.targetQuotes());
        if (!mergedQuotes.equals(stored.targetQuotes())) {
            if (jdbc.update("""
                    UPDATE structured_function_list_item SET target_quotes_json = CAST(? AS JSON)
                    WHERE task_id = ? AND item_key = ? AND work_item_id = ?
                    """, json(mergedQuotes), claim.taskId(), row.itemKey(), stored.workItemId()) != 1) {
                throw new IllegalStateException("Task-scoped function-list target quotes changed concurrently");
            }
        }
        return stored.workItemId();
    }

    private static void insertTaskScoped(String kind, Runnable insert) {
        try {
            insert.run();
        } catch (DuplicateKeyException exception) {
            throw new IllegalStateException("Task-scoped " + kind + " key conflicts with an accepted result", exception);
        }
    }

    private FrozenWork lockedWork(String workItemId) {
        List<FrozenWork> rows = jdbc.query("""
                SELECT id, task_id, identity_key, skill_name, operation_name, status, ordinal_start, ordinal_end,
                       material_key, material_document_id, source_label, allowed_evidence_keys_json,
                       context_evidence_keys_json, parent_work_item_id, split_depth,
                       function_key, test_point_key,
                       lease_owner, lease_expires_at, accepted_result_sha256
                FROM structured_generation_work_item WHERE id = ? FOR UPDATE
                """, (row, ignored) -> new FrozenWork(row.getString("id"), row.getString("task_id"), row.getString("identity_key"),
                row.getString("skill_name"), row.getString("operation_name"), row.getString("status"),
                asInteger(row.getObject("ordinal_start")), asInteger(row.getObject("ordinal_end")), row.getString("material_key"),
                row.getString("material_document_id"), row.getString("source_label"),
                stringList(row.getString("allowed_evidence_keys_json")),
                stringList(row.getString("context_evidence_keys_json")), row.getString("parent_work_item_id"),
                row.getInt("split_depth"),
                row.getString("function_key"), row.getString("test_point_key"), row.getString("lease_owner"),
                row.getTimestamp("lease_expires_at") == null ? null : row.getTimestamp("lease_expires_at").toInstant(),
                row.getString("accepted_result_sha256")), workItemId);
        if (rows.isEmpty()) throw new IllegalStateException("Structured work item does not exist");
        return rows.get(0);
    }

    private void verifyClaim(WorkClaim claim, FrozenWork frozen, String expectedOperation) {
        if (!claim.taskId().equals(frozen.taskId()) || !claim.identityKey().equals(frozen.identityKey())
                || !claim.skillName().equals(frozen.skillName()) || !claim.operationName().equals(frozen.operationName()) || !Objects.equals(claim.materialKey(), frozen.materialKey())
                || !Objects.equals(claim.ordinalStart(), frozen.ordinalStart()) || !Objects.equals(claim.ordinalEnd(), frozen.ordinalEnd())
                || !claim.allowedEvidenceKeys().equals(frozen.allowedEvidenceKeys())
                || !Objects.equals(claim.materialDocumentId(), frozen.materialDocumentId())
                || !claim.contextEvidenceKeys().equals(frozen.contextEvidenceKeys())
                || !Objects.equals(claim.parentWorkItemId(), frozen.parentWorkItemId())
                || claim.splitDepth() != frozen.splitDepth()
                || !expectedOperation.equals(frozen.operationName())) {
            throw new IllegalStateException("Structured work claim does not match frozen work");
        }
    }

    private void verifyRunning(WorkClaim claim, FrozenWork frozen) {
        if (!"RUNNING".equals(frozen.status()) || !claim.owner().equals(frozen.leaseOwner())
                || frozen.leaseExpiresAt() == null || !frozen.leaseExpiresAt().isAfter(clock.instant())) {
            throw new IllegalStateException("Structured work claim is no longer active");
        }
        Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM structured_generation_attempt WHERE id = ? AND work_item_id = ? AND status = 'RUNNING'",
                Integer.class, claim.attemptId(), claim.workItemId());
        if (active == null || active != 1) throw new IllegalStateException("Structured work attempt is no longer active");
    }

    private void requireTask(WorkClaim claim, String taskId) {
        requireClaim(claim);
        require(taskId, "taskId");
        if (!claim.taskId().equals(taskId)) {
            throw new IllegalArgumentException("Validated work belongs to a different task");
        }
    }

    private static void publishKey(StructuredValidationRegistry registry, StructuredKeyType type, String key) {
        try {
            registry.register(type, key);
        } catch (IllegalArgumentException alreadyRegistered) {
            registry.require(type, key);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Validated structured value cannot be serialized", exception);
        }
    }

    private String sha256(Object result) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(result)));
        } catch (NoSuchAlgorithmException | JsonProcessingException exception) {
            throw new IllegalStateException("Accepted result cannot be hashed", exception);
        }
    }

    private static Integer asInteger(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private static String defaultOperation(String skillName) {
        return switch (skillName) {
            case "requirement-material-quality-review" -> "REQUIREMENT_MATERIAL_REVIEW";
            case "feature-scope-reconciliation" -> "FEATURE_SCOPE_RECONCILIATION";
            case "functional-testcase-design" -> "FUNCTIONAL_TESTCASE_DESIGN";
            default -> "UNSPECIFIED";
        };
    }

    private List<String> stringList(String value) {
        if (value == null) return List.of();
        try {
            return List.copyOf(objectMapper.readValue(value, new TypeReference<List<String>>() { }));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored work-item evidence closure cannot be read", exception);
        }
    }

    private static void requireClaim(WorkClaim claim) { Objects.requireNonNull(claim, "claim must not be null"); }
    private static void require(String value, String name) { if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank"); }
    private static void requireHash(String value) { if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("resultSha256 must be lowercase SHA-256"); }

    /** One canonical source identity in the frozen V2 comparison catalog. [Req-ID]: REQ-FSC-008 */
    public record ReconciliationSourceRef(String sourceType, String sourceKey) {
        public ReconciliationSourceRef {
            if (!Set.of("function_list_item", "requirement_fact").contains(sourceType)) {
                throw new IllegalArgumentException("sourceType is not supported");
            }
            require(sourceKey, "sourceKey");
            if (sourceKey.length() > 128) throw new IllegalArgumentException("sourceKey is too long");
        }
    }

    /** Immutable run identity independently bound by the coordinator to one task catalog. [Req-ID]: REQ-FSC-008 */
    public record ReconciliationRunIdentity(String runKey, String catalogSha256,
            int functionItemCount, int requirementFactCount) {
        public ReconciliationRunIdentity {
            require(runKey, "runKey");
            if (runKey.length() > 128) throw new IllegalArgumentException("runKey is too long");
            requireHash(catalogSha256);
            if (functionItemCount < 0 || requirementFactCount < 0 || functionItemCount + requirementFactCount == 0) {
                throw new IllegalArgumentException("catalog counts must describe at least one source");
            }
        }
    }

    /** One deterministic leaf (or pre-split parent) owner window. [Req-ID]: REQ-FSC-008 */
    public record ReconciliationOwnerWindow(String pageKey, List<ReconciliationSourceRef> ownerSourceRefs) {
        public ReconciliationOwnerWindow {
            requireHash(pageKey);
            ownerSourceRefs = List.copyOf(Objects.requireNonNull(ownerSourceRefs, "ownerSourceRefs must not be null"));
            requireCanonicalSourceRefs(ownerSourceRefs, "ownerSourceRefs");
        }
    }

    /** Durable leaf status used to resume only missing V2 pages after a process restart. */
    public record ReconciliationPageProgress(ReconciliationOwnerWindow ownerWindow, String status) {
        public ReconciliationPageProgress {
            Objects.requireNonNull(ownerWindow, "ownerWindow must not be null");
            if (!Set.of("PLANNED", "COMPLETED").contains(status)) {
                throw new IllegalArgumentException("leaf page status is invalid");
            }
        }
    }

    /** Initial complete owner partition stored before the first V2 page call. [Req-ID]: REQ-FSC-008 */
    public record ReconciliationRunPlan(ReconciliationRunIdentity run,
            List<ReconciliationOwnerWindow> initialOwnerWindows) {
        public ReconciliationRunPlan {
            Objects.requireNonNull(run, "run must not be null");
            initialOwnerWindows = List.copyOf(Objects.requireNonNull(initialOwnerWindows,
                    "initialOwnerWindows must not be null"));
            if (initialOwnerWindows.isEmpty()) throw new IllegalArgumentException("initialOwnerWindows must not be empty");
            if (new HashSet<>(initialOwnerWindows.stream().map(ReconciliationOwnerWindow::pageKey).toList()).size()
                    != initialOwnerWindows.size()) {
                throw new IllegalArgumentException("initial page keys must be unique");
            }
        }
    }

    /** One validated semantic relation plus KEE-derived identities independently checked by Java. */
    public record ReconciliationRelation(String reconciliationKey, ReconciliationSourceRef ownerSourceRef,
            List<String> functionListItemKeys, List<String> requirementFactKeys, String classification,
            List<String> evidenceKeys, String scopeRecommendation, String confirmationStatus) {
        public ReconciliationRelation {
            requireHash(reconciliationKey);
            Objects.requireNonNull(ownerSourceRef, "ownerSourceRef must not be null");
            functionListItemKeys = checkedSortedKeys(functionListItemKeys, "functionListItemKeys", true);
            requirementFactKeys = checkedSortedKeys(requirementFactKeys, "requirementFactKeys", true);
            if (functionListItemKeys.isEmpty() && requirementFactKeys.isEmpty()) {
                throw new IllegalArgumentException("relation must reference at least one catalog source");
            }
            if (!RECONCILIATION_CLASSIFICATIONS.contains(classification)) {
                throw new IllegalArgumentException("classification is not supported");
            }
            evidenceKeys = checkedSortedKeys(evidenceKeys, "evidenceKeys", false);
            require(scopeRecommendation, "scopeRecommendation");
            if (!RECONCILIATION_CONFIRMATION_STATUSES.contains(confirmationStatus)) {
                throw new IllegalArgumentException("confirmationStatus is not supported");
            }
            if ("insufficient_evidence".equals(classification) && !"pending_confirmation".equals(confirmationStatus)) {
                throw new IllegalArgumentException("insufficient evidence must remain pending confirmation");
            }
        }
    }

    /** One completed response page retained outside the business projection until global publication. */
    public record ReconciliationPageStage(ReconciliationRunIdentity run, ReconciliationOwnerWindow ownerWindow,
            List<ReconciliationSourceRef> completedOwnerSourceRefs, List<ReconciliationRelation> relations,
            String resultSha256) {
        public ReconciliationPageStage {
            Objects.requireNonNull(run, "run must not be null");
            Objects.requireNonNull(ownerWindow, "ownerWindow must not be null");
            completedOwnerSourceRefs = List.copyOf(Objects.requireNonNull(completedOwnerSourceRefs,
                    "completedOwnerSourceRefs must not be null"));
            requireCanonicalSourceRefs(completedOwnerSourceRefs, "completedOwnerSourceRefs");
            relations = canonicalRelations(relations);
            requireHash(resultSha256);
        }

        public String runKey() { return run.runKey(); }
        public String catalogSha256() { return run.catalogSha256(); }
        public String pageKey() { return ownerWindow.pageKey(); }
        public List<ReconciliationSourceRef> ownerSourceRefs() { return ownerWindow.ownerSourceRefs(); }
    }

    /** Globally validated final V2 projection that the store publishes all-or-nothing. */
    public record ReconciliationRunPublication(ReconciliationRunIdentity run, List<String> expectedPageKeys,
            List<ReconciliationSourceRef> catalogSources, List<ReconciliationRelation> relations,
            String acceptedResultSha256) {
        public ReconciliationRunPublication {
            Objects.requireNonNull(run, "run must not be null");
            expectedPageKeys = List.copyOf(Objects.requireNonNull(expectedPageKeys, "expectedPageKeys must not be null"));
            if (expectedPageKeys.isEmpty() || new HashSet<>(expectedPageKeys).size() != expectedPageKeys.size()) {
                throw new IllegalArgumentException("expectedPageKeys must be nonempty and unique");
            }
            expectedPageKeys.forEach(StructuredGenerationAcceptanceStore::requireHash);
            catalogSources = List.copyOf(Objects.requireNonNull(catalogSources, "catalogSources must not be null"));
            requireCanonicalSourceRefs(catalogSources, "catalogSources");
            relations = canonicalRelations(relations);
            requireHash(acceptedResultSha256);
        }

        public ReconciliationRunPublication(String runKey, String catalogSha256, List<String> expectedPageKeys,
                List<ReconciliationSourceRef> catalogSources, List<ReconciliationRelation> relations,
                String acceptedResultSha256) {
            this(new ReconciliationRunIdentity(runKey, catalogSha256,
                    (int) catalogSources.stream().filter(ref -> "function_list_item".equals(ref.sourceType())).count(),
                    (int) catalogSources.stream().filter(ref -> "requirement_fact".equals(ref.sourceType())).count()),
                    expectedPageKeys, catalogSources, relations, acceptedResultSha256);
        }
    }

    /** Stable work coordinates; ordinal bounds retain parsed-unit slice identity. */
    public record WorkRegistration(String taskId, String identityKey, String skillName, String operationName,
            Integer ordinalStart, Integer ordinalEnd, String materialKey, String sourceLabel,
            List<String> allowedEvidenceKeys, String functionKey, String testPointKey,
            String materialDocumentId, List<String> contextEvidenceKeys, String parentWorkItemId, int splitDepth) {
        public WorkRegistration {
            require(taskId, "taskId"); requireHash(identityKey); require(skillName, "skillName"); require(operationName, "operationName");
            allowedEvidenceKeys = List.copyOf(Objects.requireNonNull(allowedEvidenceKeys, "allowedEvidenceKeys must not be null"));
            contextEvidenceKeys = List.copyOf(Objects.requireNonNull(contextEvidenceKeys, "contextEvidenceKeys must not be null"));
            if ((ordinalStart == null) != (ordinalEnd == null) || (ordinalStart != null && (ordinalStart < 1 || ordinalEnd < ordinalStart))) throw new IllegalArgumentException("ordinal range is invalid");
            if (splitDepth < 0) throw new IllegalArgumentException("splitDepth must not be negative");
            if ("requirement-material-quality-review".equals(skillName)) {
                require(materialKey, "materialKey");
                require(sourceLabel, "sourceLabel");
            }
            if ("requirement-fact-extraction".equals(skillName)
                    || "REQUIREMENT_FACT_EXTRACTION_V2".equals(operationName)) {
                require(materialKey, "materialKey");
                require(sourceLabel, "sourceLabel");
                require(functionKey, "functionKey");
                if (!"REQUIREMENT_FACT_EXTRACTION_V2".equals(operationName)
                        || allowedEvidenceKeys.isEmpty()) {
                    throw new IllegalArgumentException("V2 fact extraction requires a nonempty frozen target window");
                }
            }
            if ("FUNCTIONAL_TESTCASE_DESIGN_V2".equals(operationName)) {
                require(functionKey, "functionKey");
                require(testPointKey, "testPointKey");
                if (materialDocumentId != null || ordinalStart != null || ordinalEnd != null
                        || !allowedEvidenceKeys.isEmpty() || !contextEvidenceKeys.isEmpty()) {
                    throw new IllegalArgumentException("V2 testcase work must not carry a parsed-unit window");
                }
            }
            if ("FEATURE_SCOPE_EXTRACT".equals(operationName)) {
                require(materialKey, "materialKey"); require(sourceLabel, "sourceLabel");
                if (allowedEvidenceKeys.isEmpty()) throw new IllegalArgumentException("Feature-scope extraction requires slice evidence");
            }
        }
        /** Historical constructor: nullable V17 coordinates identify pre-semantic work without changing its identity. */
        public WorkRegistration(String taskId, String identityKey, String skillName, String operationName,
                Integer ordinalStart, Integer ordinalEnd, String materialKey, String sourceLabel,
                List<String> allowedEvidenceKeys, String functionKey, String testPointKey) {
            this(taskId, identityKey, skillName, operationName, ordinalStart, ordinalEnd, materialKey, sourceLabel,
                    allowedEvidenceKeys, functionKey, testPointKey, null, List.of(), null, 0);
        }
        public WorkRegistration(String taskId, String identityKey, String skillName, Integer ordinalStart, Integer ordinalEnd) { this(taskId, identityKey, skillName, defaultOperation(skillName), ordinalStart, ordinalEnd, null, null, List.of(), null, null, null, List.of(), null, 0); }
        public WorkRegistration(String taskId, String identityKey, String skillName, Integer ordinalStart, Integer ordinalEnd,
                String materialKey, String sourceLabel, String functionKey, String testPointKey) {
            this(taskId, identityKey, skillName, defaultOperation(skillName), ordinalStart, ordinalEnd, materialKey,
                    sourceLabel, List.of(), functionKey, testPointKey, null, List.of(), null, 0);
        }
        public WorkRegistration(String taskId, String identityKey, String skillName, Integer ordinalStart, Integer ordinalEnd,
                String materialKey, String sourceLabel, List<String> allowedEvidenceKeys, String functionKey, String testPointKey) {
            this(taskId, identityKey, skillName, defaultOperation(skillName), ordinalStart, ordinalEnd, materialKey,
                    sourceLabel, allowedEvidenceKeys, functionKey, testPointKey, null, List.of(), null, 0);
        }
    }
    /** Exclusive running attempt returned by {@link #claimNext(String, String)}. */
    public record WorkClaim(String workItemId, String attemptId, String taskId, String identityKey, String skillName, String operationName,
            int attemptNumber, Integer ordinalStart, Integer ordinalEnd, String materialKey,
            List<String> allowedEvidenceKeys, String owner, String materialDocumentId,
            List<String> contextEvidenceKeys, String parentWorkItemId, int splitDepth) {
        public WorkClaim {
            allowedEvidenceKeys = List.copyOf(allowedEvidenceKeys);
            contextEvidenceKeys = List.copyOf(contextEvidenceKeys);
        }

        /** Preserves callers that reconstruct historical claims without V17 semantic coordinates. */
        public WorkClaim(String workItemId, String attemptId, String taskId, String identityKey, String skillName,
                String operationName, int attemptNumber, Integer ordinalStart, Integer ordinalEnd, String materialKey,
                List<String> allowedEvidenceKeys, String owner) {
            this(workItemId, attemptId, taskId, identityKey, skillName, operationName, attemptNumber, ordinalStart,
                    ordinalEnd, materialKey, allowedEvidenceKeys, owner, null, List.of(), null, 0);
        }
    }

    /** Persisted target/context coordinates used for restart-safe material orchestration. [Req-ID]: REQ-FTG-013 */
    public record MaterialWindowPlan(String workItemId, String identityKey, String status,
            Integer ordinalStart, Integer ordinalEnd, String materialDocumentId, List<String> targetEvidenceKeys,
            List<String> contextEvidenceKeys, String parentWorkItemId, int splitDepth) {
        public MaterialWindowPlan {
            targetEvidenceKeys = List.copyOf(targetEvidenceKeys);
            contextEvidenceKeys = List.copyOf(contextEvidenceKeys);
        }
    }
    /** Task-level processing and formal-coverage axes used by the workflow. */
    public record StructuredTaskState(StructuredProcessingStatus processingStatus, StructuredCoverageStatus coverageStatus) {
        public StructuredTaskState {
            Objects.requireNonNull(processingStatus, "processingStatus must not be null");
            Objects.requireNonNull(coverageStatus, "coverageStatus must not be null");
        }
    }
    /** Application-created function-list record; its stable key is verified against Java's registry, never model-generated. */
    public record FunctionListItem(String itemKey, String path, String description,
            List<String> evidenceKeys, List<String> targetQuotes) {
        public FunctionListItem {
            evidenceKeys = List.copyOf(Objects.requireNonNull(evidenceKeys, "evidenceKeys must not be null"));
            targetQuotes = List.copyOf(Objects.requireNonNull(targetQuotes, "targetQuotes must not be null"));
            targetQuotes.forEach(quote -> {
                require(quote, "targetQuote");
                if (quote.codePointCount(0, quote.length())
                        > FunctionListExtractionResult.MAX_TARGET_QUOTE_CODE_POINTS) {
                    throw new IllegalArgumentException("targetQuote exceeds maximum Unicode characters");
                }
            });
        }
    }
    /** Accepted workflow inputs reconstructed from task-owned tables, never from raw model output. */
    public record AcceptedInputs(List<AcceptedFact> facts, List<AcceptedFunctionItem> functionItems) {
        public AcceptedInputs {
            facts = List.copyOf(facts);
            functionItems = List.copyOf(functionItems);
        }
    }
    /** Complete persisted formal fact and exact parsed-unit evidence used for restart-safe testcase grounding. */
    public record AcceptedFact(String factKey, String function, List<String> roles, List<String> triggerConditions,
            List<String> inputs, List<String> businessRules, List<String> outputs, List<String> permissions,
            List<String> stateChanges, List<String> exceptionHandling, List<String> externalDependencies,
            List<String> evidenceKeys, Map<String, String> evidenceTexts) {
        public AcceptedFact {
            require(factKey, "factKey");
            require(function, "function");
            roles = List.copyOf(roles);
            triggerConditions = List.copyOf(triggerConditions);
            inputs = List.copyOf(inputs);
            businessRules = List.copyOf(businessRules);
            outputs = List.copyOf(outputs);
            permissions = List.copyOf(permissions);
            stateChanges = List.copyOf(stateChanges);
            exceptionHandling = List.copyOf(exceptionHandling);
            externalDependencies = List.copyOf(externalDependencies);
            evidenceKeys = List.copyOf(evidenceKeys);
            evidenceTexts = Map.copyOf(evidenceTexts);
            if (!evidenceTexts.keySet().equals(Set.copyOf(evidenceKeys))) {
                throw new IllegalArgumentException("Accepted fact evidence text must exactly match its evidence keys");
            }
        }
    }
    /** One task-unique function-list item and its merged evidence. */
    public record AcceptedFunctionItem(String itemKey, String path, String description,
            List<String> evidenceKeys, List<String> targetQuotes) {
        /** Reads pre-V18 accepted rows whose source quote was not yet persisted. */
        public AcceptedFunctionItem(String itemKey, String path, String description, List<String> evidenceKeys) {
            this(itemKey, path, description, evidenceKeys, List.of());
        }

        public AcceptedFunctionItem {
            evidenceKeys = List.copyOf(Objects.requireNonNull(evidenceKeys, "evidenceKeys must not be null"));
            targetQuotes = List.copyOf(Objects.requireNonNull(targetQuotes, "targetQuotes must not be null"));
        }
    }

    /** One confirmed persisted mapping that defines the final function identity used for testcase planning. */
    public record AcceptedConfirmedFunction(String reconciliationKey, List<AcceptedFunctionItem> functionItems,
            List<AcceptedFact> facts) {
        public AcceptedConfirmedFunction {
            require(reconciliationKey, "reconciliationKey");
            functionItems = List.copyOf(Objects.requireNonNull(functionItems, "functionItems must not be null"));
            facts = List.copyOf(Objects.requireNonNull(facts, "facts must not be null"));
            if (functionItems.isEmpty() && facts.isEmpty()) {
                throw new IllegalArgumentException("A confirmed function requires at least one persisted source identity");
            }
        }
    }
    /** Durable counts for completion and formal coverage. */
    public record AggregateState(int totalReviewWork, int completedReviewWork, int formalPointTotal,
            int coveredFormalPointCount, int pendingCandidateCount, int acceptedWorkCount,
            int failedWorkCount, boolean allWorkTerminal, int acceptedFunctionCandidateCount,
            int incompleteFunctionScopeCount, int failedFunctionCandidateWorkCount) {
        /** Keeps existing callers and legacy task fixtures independent from candidate-protocol counts. */
        public AggregateState(int totalReviewWork, int completedReviewWork, int formalPointTotal,
                int coveredFormalPointCount, int pendingCandidateCount, int acceptedWorkCount,
                int failedWorkCount, boolean allWorkTerminal) {
            this(totalReviewWork, completedReviewWork, formalPointTotal, coveredFormalPointCount,
                    pendingCandidateCount, acceptedWorkCount, failedWorkCount, allWorkTerminal, 0, 0, 0);
        }
    }
    /** V2 work and outcome axes; formal and pending cases are intentionally counted separately. */
    public record V2AggregateState(int totalWork, int completedWork, int failedWork, int pendingWork,
            int testPointTotal, int formalCoveredPointCount, int formalCaseCount, int pendingCaseCount,
            int unableOutcomeCount) {
        /** True only when every durable leaf work is in a terminal state. */
        public boolean allWorkTerminal() {
            return pendingWork == 0 && totalWork == completedWork + failedWork;
        }
    }
    private record WorkRow(String id, String identityKey, String skillName, String operationName,
            Integer ordinalStart, Integer ordinalEnd, String materialKey, String materialDocumentId,
            List<String> allowedEvidenceKeys, List<String> contextEvidenceKeys,
            String parentWorkItemId, int splitDepth) { }
    private record RegistrationRow(String id, String skillName, String operationName, Integer ordinalStart, Integer ordinalEnd,
            String materialKey, String materialDocumentId, String sourceLabel, List<String> allowedEvidenceKeys,
            List<String> contextEvidenceKeys, String parentWorkItemId, int splitDepth,
            String functionKey, String testPointKey) {
        boolean matches(WorkRegistration registration) {
            return skillName.equals(registration.skillName()) && operationName.equals(registration.operationName())
                    && Objects.equals(ordinalStart, registration.ordinalStart()) && Objects.equals(ordinalEnd, registration.ordinalEnd())
                    && Objects.equals(materialKey, registration.materialKey())
                    && Objects.equals(materialDocumentId, registration.materialDocumentId())
                    && Objects.equals(sourceLabel, registration.sourceLabel())
                    && allowedEvidenceKeys.equals(registration.allowedEvidenceKeys())
                    && contextEvidenceKeys.equals(registration.contextEvidenceKeys())
                    && Objects.equals(parentWorkItemId, registration.parentWorkItemId())
                    && splitDepth == registration.splitDepth() && Objects.equals(functionKey, registration.functionKey())
                    && Objects.equals(testPointKey, registration.testPointKey());
        }
    }

    private record ReactivationRow(
            String status, String acceptedResultSha256, String coverageStatus, boolean hasLease) { }

    private record ReactivationProjectionRow(
            String functionName, String testPointType, String basis, String description,
            List<String> pointMissing, boolean pointFormal, String generationOutcome,
            List<String> outcomeMissing, boolean outcomeFormal, String inputSha256, String resultSha256,
            String validatedResultReplayJson) { }
    private record FrozenWork(String id, String taskId, String identityKey, String skillName, String operationName, String status,
            Integer ordinalStart, Integer ordinalEnd, String materialKey, String materialDocumentId, String sourceLabel,
            List<String> allowedEvidenceKeys, List<String> contextEvidenceKeys,
            String parentWorkItemId, int splitDepth, String functionKey, String testPointKey,
            String leaseOwner, Instant leaseExpiresAt, String acceptedResultSha256) { }
    private record CompletedRow(String status, String hash) { }
    private record StoredFunctionItem(
            String workItemId, String path, String description, List<String> targetQuotes) { }
    private record StoredReviewFinding(String workItemId, String findingKey, List<String> affectedUnitKeys,
            String affectedScopeSummary) { }
    private record AcceptedReconciliation(String workItemId, String reconciliationKey) { }
    private record TargetWorkRow(String id, String identityKey, String skillName, String operationName, String status,
            Integer ordinalStart, Integer ordinalEnd, String materialKey, String materialDocumentId,
            List<String> allowedEvidenceKeys, List<String> contextEvidenceKeys,
            String parentWorkItemId, int splitDepth, Instant leaseExpiresAt) { }
    private record InventoryUnitKey(String unitId, int ordinal) { }
    private record ParentWindowRow(String taskId, String operationName, String materialKey,
            String materialDocumentId, int splitDepth) {
        boolean matches(WorkRegistration child) {
            return taskId.equals(child.taskId()) && operationName.equals(child.operationName())
                    && Objects.equals(materialKey, child.materialKey())
                    && Objects.equals(materialDocumentId, child.materialDocumentId())
                    && child.splitDepth() == splitDepth + 1;
        }
    }
    private record ReconciliationRunRow(String workItemId, String taskId, String runKey, String catalogSha256,
            int functionItemCount, int requirementFactCount, List<ReconciliationSourceRef> catalogSources,
            List<String> initialPageKeys, String status, String acceptedResultSha256) {
        ReconciliationRunIdentity identity() {
            return new ReconciliationRunIdentity(runKey, catalogSha256, functionItemCount, requirementFactCount);
        }
        boolean matches(ReconciliationRunIdentity run, List<ReconciliationSourceRef> sources, List<String> pageKeys) {
            return runKey.equals(run.runKey()) && catalogSha256.equals(run.catalogSha256())
                    && functionItemCount == run.functionItemCount() && requirementFactCount == run.requirementFactCount()
                    && catalogSources.equals(sources) && initialPageKeys.equals(pageKeys);
        }
    }
    private record ReconciliationPageRow(String pageKey, String runKey, String catalogSha256,
            String parentPageKey, String status, List<ReconciliationSourceRef> ownerSourceRefs,
            List<ReconciliationSourceRef> completedOwnerSourceRefs, String resultSha256) {
        boolean matches(ReconciliationRunIdentity run, ReconciliationOwnerWindow window) {
            return runKey.equals(run.runKey()) && catalogSha256.equals(run.catalogSha256())
                    && pageKey.equals(window.pageKey()) && ownerSourceRefs.equals(window.ownerSourceRefs());
        }
    }
    private record StagedRelationRow(String pageKey, String reconciliationKey, String ownerSourceType,
            String ownerSourceKey, String classification, String scopeRecommendation, String confirmationStatus) { }
}
