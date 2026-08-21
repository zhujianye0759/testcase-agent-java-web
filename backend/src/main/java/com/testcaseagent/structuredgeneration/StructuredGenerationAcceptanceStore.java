package com.testcaseagent.structuredgeneration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testcaseagent.validation.FeatureReconciliationValidator;
import com.testcaseagent.validation.FunctionalTestcaseResultValidator;
import com.testcaseagent.validation.RequirementMaterialReviewValidator;
import com.testcaseagent.validation.StructuredKeyType;
import com.testcaseagent.validation.StructuredValidationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
    private static final Set<String> FAILURE_TYPES = Set.of("invalid_request", "request_too_large", "session_not_found", "forbidden", "unsupported_skill",
            "skill_unavailable", "model_unavailable", "model_execution_failed", "structured_output_invalid", "response_too_large",
            "scope_validation_failed", "business_validation_failed");
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
        return transaction.execute(status -> {
            String id = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO structured_generation_work_item
                    (id, task_id, identity_key, skill_name, operation_name, status, ordinal_start, ordinal_end, material_key, source_label, allowed_evidence_keys_json, function_key, test_point_key)
                    VALUES (?, ?, ?, ?, ?, 'QUEUED', ?, ?, ?, ?, CAST(? AS JSON), ?, ?)
                    ON DUPLICATE KEY UPDATE id = id
                    """, id, item.taskId(), item.identityKey(), item.skillName(), item.operationName(), item.ordinalStart(), item.ordinalEnd(),
                    item.materialKey(), item.sourceLabel(), json(item.allowedEvidenceKeys()), item.functionKey(), item.testPointKey());
            List<RegistrationRow> rows = jdbc.query("""
                    SELECT id, skill_name, operation_name, ordinal_start, ordinal_end, material_key, source_label,
                           allowed_evidence_keys_json, function_key, test_point_key
                    FROM structured_generation_work_item WHERE task_id = ? AND identity_key = ? FOR UPDATE
                    """, (row, ignored) -> new RegistrationRow(row.getString("id"), row.getString("skill_name"), row.getString("operation_name"),
                    asInteger(row.getObject("ordinal_start")), asInteger(row.getObject("ordinal_end")), row.getString("material_key"),
                    row.getString("source_label"), stringList(row.getString("allowed_evidence_keys_json")), row.getString("function_key"),
                    row.getString("test_point_key")), item.taskId(), item.identityKey());
            RegistrationRow stored = rows.get(0);
            if (!stored.matches(item)) throw new IllegalStateException("Work identity conflicts with frozen registration coordinates");
            return stored.id();
        });
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
                jdbc.update("UPDATE structured_generation_attempt SET status = 'FAILED', failure_type = 'model_execution_failed', completed_at = ? "
                        + "WHERE work_item_id = ? AND status = 'RUNNING'", now, expiredId);
                jdbc.update("UPDATE structured_generation_work_item SET status = 'FAILED', lease_owner = NULL, lease_expires_at = NULL WHERE id = ?", expiredId);
            }
            List<WorkRow> rows = jdbc.query("""
                    SELECT id, identity_key, skill_name, operation_name, ordinal_start, ordinal_end, material_key, allowed_evidence_keys_json
                    FROM structured_generation_work_item
                    WHERE task_id = ? AND (status = 'QUEUED' OR (status = 'FAILED'
                      AND (SELECT COUNT(*) FROM structured_generation_attempt a WHERE a.work_item_id = structured_generation_work_item.id) < 2
                      AND (SELECT a.failure_type FROM structured_generation_attempt a WHERE a.work_item_id = structured_generation_work_item.id
                           ORDER BY a.attempt_number DESC LIMIT 1) IN ('model_unavailable', 'model_execution_failed')))
                    ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """, (row, ignored) -> new WorkRow(row.getString("id"), row.getString("identity_key"), row.getString("skill_name"),
                    row.getString("operation_name"), asInteger(row.getObject("ordinal_start")), asInteger(row.getObject("ordinal_end")),
                    row.getString("material_key"), stringList(row.getString("allowed_evidence_keys_json"))), taskId);
            if (rows.isEmpty()) return Optional.empty();
            WorkRow row = rows.get(0);
            int attemptNumber = jdbc.queryForObject("SELECT COALESCE(MAX(attempt_number), 0) + 1 FROM structured_generation_attempt WHERE work_item_id = ?",
                    Integer.class, row.id());
            String attemptId = UUID.randomUUID().toString();
            Instant expires = clock.instant().plus(Duration.ofMinutes(5));
            if (jdbc.update("""
                    UPDATE structured_generation_work_item SET status = 'RUNNING', lease_owner = ?, lease_expires_at = ?
                    WHERE id = ? AND status IN ('QUEUED', 'FAILED')
                    """, owner, expires, row.id()) != 1) throw new IllegalStateException("Structured work claim was lost");
            jdbc.update("INSERT INTO structured_generation_attempt (id, work_item_id, attempt_number, status) VALUES (?, ?, ?, 'RUNNING')",
                    attemptId, row.id(), attemptNumber);
            return Optional.of(new WorkClaim(row.id(), attemptId, taskId, row.identityKey(), row.skillName(), row.operationName(), attemptNumber,
                    row.ordinalStart(), row.ordinalEnd(), row.materialKey(), row.allowedEvidenceKeys(), owner));
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
                           material_key, allowed_evidence_keys_json, lease_expires_at
                    FROM structured_generation_work_item
                    WHERE task_id = ? AND id = ? FOR UPDATE
                    """, (row, ignored) -> new TargetWorkRow(row.getString("id"), row.getString("identity_key"),
                    row.getString("skill_name"), row.getString("operation_name"), row.getString("status"),
                    asInteger(row.getObject("ordinal_start")), asInteger(row.getObject("ordinal_end")),
                    row.getString("material_key"), stringList(row.getString("allowed_evidence_keys_json")),
                    row.getTimestamp("lease_expires_at") == null ? null : row.getTimestamp("lease_expires_at").toInstant()),
                    taskId, workItemId);
            if (rows.isEmpty()) return Optional.empty();
            TargetWorkRow row = rows.get(0);
            String currentStatus = row.status();
            if ("RUNNING".equals(currentStatus) && row.leaseExpiresAt() != null
                    && !row.leaseExpiresAt().isAfter(now)) {
                jdbc.update("UPDATE structured_generation_attempt SET status = 'FAILED', failure_type = 'model_execution_failed', completed_at = ? "
                        + "WHERE work_item_id = ? AND status = 'RUNNING'", now, workItemId);
                jdbc.update("UPDATE structured_generation_work_item SET status = 'FAILED', lease_owner = NULL, lease_expires_at = NULL WHERE id = ?",
                        workItemId);
                currentStatus = "FAILED";
            }
            int attempts = count("SELECT COUNT(*) FROM structured_generation_attempt WHERE work_item_id = ?", workItemId);
            if ("FAILED".equals(currentStatus)) {
                List<String> failures = jdbc.queryForList("""
                        SELECT failure_type FROM structured_generation_attempt
                        WHERE work_item_id = ? ORDER BY attempt_number DESC LIMIT 1
                        """, String.class, workItemId);
                if (attempts >= MAX_ATTEMPTS || failures.isEmpty()
                        || !("model_unavailable".equals(failures.get(0))
                        || "model_execution_failed".equals(failures.get(0)))) return Optional.empty();
            } else if (!"QUEUED".equals(currentStatus)) {
                return Optional.empty();
            }
            int attemptNumber = attempts + 1;
            String attemptId = UUID.randomUUID().toString();
            Instant expires = now.plus(Duration.ofMinutes(5));
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
                    row.allowedEvidenceKeys(), owner));
        });
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
        requireClaim(claim); require(failureType, "failureType");
        if (!FAILURE_TYPES.contains(failureType)) throw new IllegalArgumentException("Unsupported structured failure type");
        transaction.executeWithoutResult(status -> {
            FrozenWork frozen = lockedWork(claim.workItemId());
            verifyClaim(claim, frozen, frozen.operationName());
            verifyRunning(claim, frozen);
            jdbc.update("UPDATE structured_generation_attempt SET status = 'FAILED', failure_type = ?, completed_at = ? WHERE id = ?",
                    failureType, clock.instant(), claim.attemptId());
            jdbc.update("UPDATE structured_generation_work_item SET status = 'FAILED', lease_owner = NULL, lease_expires_at = NULL WHERE id = ?", claim.workItemId());
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
                SELECT item.work_item_id, item.item_key, item.path_text, item.description
                FROM structured_function_list_item item
                JOIN structured_generation_work_item w ON w.id = item.work_item_id
                WHERE item.task_id = ? AND w.status = 'COMPLETED' AND w.accepted_result_sha256 IS NOT NULL
                ORDER BY item.path_text, item.item_key
                """, (row, ignored) -> new AcceptedFunctionItem(row.getString("item_key"), row.getString("path_text"),
                row.getString("description"), referenceKeys(row.getString("work_item_id"), row.getString("item_key"),
                        "FUNCTION_LIST_ITEM", "EVIDENCE")), taskId);
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
        int totalReview = count("SELECT COUNT(*) FROM structured_generation_work_item WHERE task_id = ? AND operation_name = 'REQUIREMENT_MATERIAL_REVIEW'", taskId);
        int completedReview = count("SELECT COUNT(*) FROM structured_generation_work_item WHERE task_id = ? AND operation_name = 'REQUIREMENT_MATERIAL_REVIEW' AND status = 'COMPLETED'", taskId);
        int formalPoints = count("SELECT COUNT(*) FROM structured_test_point WHERE task_id = ? AND basis = 'FORMAL_REQUIREMENT'", taskId);
        int coveredFormal = count("SELECT COUNT(*) FROM structured_test_point WHERE task_id = ? AND basis = 'FORMAL_REQUIREMENT' AND formal_coverage_satisfied = TRUE", taskId);
        int pendingCases = count("SELECT COUNT(*) FROM structured_test_case WHERE task_id = ? AND case_status = 'PENDING_CONFIRMATION'", taskId);
        int acceptedWork = count("SELECT COUNT(*) FROM structured_generation_work_item WHERE task_id = ? AND status = 'COMPLETED'", taskId);
        int failedWork = count("SELECT COUNT(*) FROM structured_generation_work_item WHERE task_id = ? AND status = 'FAILED'", taskId);
        int nonterminal = count("SELECT COUNT(*) FROM structured_generation_work_item WHERE task_id = ? AND status IN ('QUEUED','RUNNING')", taskId);
        return new AggregateState(totalReview, completedReview, formalPoints, coveredFormal, pendingCases,
                acceptedWork, failedWork, nonterminal == 0);
    }

    /** Returns whether an exact registered work identity already has an accepted hash. */
    public boolean isCompleted(String workItemId) {
        require(workItemId, "workItemId");
        return count("SELECT COUNT(*) FROM structured_generation_work_item WHERE id = ? AND status = 'COMPLETED' AND accepted_result_sha256 IS NOT NULL",
                workItemId) == 1;
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
                INSERT INTO structured_function_list_item (work_item_id, task_id, item_key, path_text, description)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE item_key = VALUES(item_key)
                """, claim.workItemId(), claim.taskId(), row.itemKey(), row.path(), row.description());
        List<StoredFunctionItem> existing = jdbc.query("""
                SELECT work_item_id, path_text, description
                FROM structured_function_list_item
                WHERE task_id = ? AND item_key = ? FOR UPDATE
                """, (result, ignored) -> new StoredFunctionItem(result.getString("work_item_id"),
                result.getString("path_text"), result.getString("description")), claim.taskId(), row.itemKey());
        if (existing.isEmpty()) throw new IllegalStateException("Task-scoped function-list item was not persisted");
        StoredFunctionItem stored = existing.get(0);
        if (!stored.path().equals(row.path()) || !stored.description().equals(row.description())) {
            throw new IllegalStateException("Stable function-list identity conflicts with persisted business text");
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
                SELECT id, task_id, identity_key, skill_name, operation_name, status, material_key, allowed_evidence_keys_json,
                       function_key, test_point_key,
                       lease_owner, lease_expires_at, accepted_result_sha256
                FROM structured_generation_work_item WHERE id = ? FOR UPDATE
                """, (row, ignored) -> new FrozenWork(row.getString("id"), row.getString("task_id"), row.getString("identity_key"),
                row.getString("skill_name"), row.getString("operation_name"), row.getString("status"), row.getString("material_key"),
                stringList(row.getString("allowed_evidence_keys_json")), row.getString("function_key"), row.getString("test_point_key"), row.getString("lease_owner"),
                row.getTimestamp("lease_expires_at") == null ? null : row.getTimestamp("lease_expires_at").toInstant(),
                row.getString("accepted_result_sha256")), workItemId);
        if (rows.isEmpty()) throw new IllegalStateException("Structured work item does not exist");
        return rows.get(0);
    }

    private void verifyClaim(WorkClaim claim, FrozenWork frozen, String expectedOperation) {
        if (!claim.taskId().equals(frozen.taskId()) || !claim.identityKey().equals(frozen.identityKey())
                || !claim.skillName().equals(frozen.skillName()) || !claim.operationName().equals(frozen.operationName()) || !Objects.equals(claim.materialKey(), frozen.materialKey())
                || !claim.allowedEvidenceKeys().equals(frozen.allowedEvidenceKeys()) || !expectedOperation.equals(frozen.operationName())) {
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

    /** Stable work coordinates; ordinal bounds retain parsed-unit slice identity. */
    public record WorkRegistration(String taskId, String identityKey, String skillName, String operationName, Integer ordinalStart, Integer ordinalEnd,
            String materialKey, String sourceLabel, List<String> allowedEvidenceKeys, String functionKey, String testPointKey) {
        public WorkRegistration {
            require(taskId, "taskId"); requireHash(identityKey); require(skillName, "skillName"); require(operationName, "operationName");
            allowedEvidenceKeys = List.copyOf(Objects.requireNonNull(allowedEvidenceKeys, "allowedEvidenceKeys must not be null"));
            if ((ordinalStart == null) != (ordinalEnd == null) || (ordinalStart != null && (ordinalStart < 1 || ordinalEnd < ordinalStart))) throw new IllegalArgumentException("ordinal range is invalid");
            if ("requirement-material-quality-review".equals(skillName)) {
                require(materialKey, "materialKey");
                require(sourceLabel, "sourceLabel");
            }
            if ("FEATURE_SCOPE_EXTRACT".equals(operationName)) {
                require(materialKey, "materialKey"); require(sourceLabel, "sourceLabel");
                if (allowedEvidenceKeys.isEmpty()) throw new IllegalArgumentException("Feature-scope extraction requires slice evidence");
            }
        }
        public WorkRegistration(String taskId, String identityKey, String skillName, Integer ordinalStart, Integer ordinalEnd) { this(taskId, identityKey, skillName, defaultOperation(skillName), ordinalStart, ordinalEnd, null, null, List.of(), null, null); }
        public WorkRegistration(String taskId, String identityKey, String skillName, Integer ordinalStart, Integer ordinalEnd,
                String materialKey, String sourceLabel, String functionKey, String testPointKey) {
            this(taskId, identityKey, skillName, defaultOperation(skillName), ordinalStart, ordinalEnd, materialKey, sourceLabel, List.of(), functionKey, testPointKey);
        }
        public WorkRegistration(String taskId, String identityKey, String skillName, Integer ordinalStart, Integer ordinalEnd,
                String materialKey, String sourceLabel, List<String> allowedEvidenceKeys, String functionKey, String testPointKey) {
            this(taskId, identityKey, skillName, defaultOperation(skillName), ordinalStart, ordinalEnd, materialKey, sourceLabel, allowedEvidenceKeys, functionKey, testPointKey);
        }
    }
    /** Exclusive running attempt returned by {@link #claimNext(String, String)}. */
    public record WorkClaim(String workItemId, String attemptId, String taskId, String identityKey, String skillName, String operationName,
            int attemptNumber, Integer ordinalStart, Integer ordinalEnd, String materialKey, List<String> allowedEvidenceKeys, String owner) { }
    /** Task-level processing and formal-coverage axes used by the workflow. */
    public record StructuredTaskState(StructuredProcessingStatus processingStatus, StructuredCoverageStatus coverageStatus) {
        public StructuredTaskState {
            Objects.requireNonNull(processingStatus, "processingStatus must not be null");
            Objects.requireNonNull(coverageStatus, "coverageStatus must not be null");
        }
    }
    /** Application-created function-list record; its stable key is verified against Java's registry, never model-generated. */
    public record FunctionListItem(String itemKey, String path, String description, List<String> evidenceKeys) { }
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
    public record AcceptedFunctionItem(String itemKey, String path, String description, List<String> evidenceKeys) { }

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
            int failedWorkCount, boolean allWorkTerminal) { }
    private record WorkRow(String id, String identityKey, String skillName, String operationName, Integer ordinalStart, Integer ordinalEnd,
            String materialKey, List<String> allowedEvidenceKeys) { }
    private record RegistrationRow(String id, String skillName, String operationName, Integer ordinalStart, Integer ordinalEnd,
            String materialKey, String sourceLabel, List<String> allowedEvidenceKeys, String functionKey, String testPointKey) {
        boolean matches(WorkRegistration registration) {
            return skillName.equals(registration.skillName()) && operationName.equals(registration.operationName())
                    && Objects.equals(ordinalStart, registration.ordinalStart()) && Objects.equals(ordinalEnd, registration.ordinalEnd())
                    && Objects.equals(materialKey, registration.materialKey()) && Objects.equals(sourceLabel, registration.sourceLabel())
                    && allowedEvidenceKeys.equals(registration.allowedEvidenceKeys()) && Objects.equals(functionKey, registration.functionKey())
                    && Objects.equals(testPointKey, registration.testPointKey());
        }
    }
    private record FrozenWork(String id, String taskId, String identityKey, String skillName, String operationName, String status,
            String materialKey, List<String> allowedEvidenceKeys, String functionKey, String testPointKey,
            String leaseOwner, Instant leaseExpiresAt, String acceptedResultSha256) { }
    private record CompletedRow(String status, String hash) { }
    private record StoredFunctionItem(String workItemId, String path, String description) { }
    private record StoredReviewFinding(String workItemId, String findingKey, List<String> affectedUnitKeys,
            String affectedScopeSummary) { }
    private record AcceptedReconciliation(String workItemId, String reconciliationKey) { }
    private record TargetWorkRow(String id, String identityKey, String skillName, String operationName, String status,
            Integer ordinalStart, Integer ordinalEnd, String materialKey, List<String> allowedEvidenceKeys,
            Instant leaseExpiresAt) { }
}
