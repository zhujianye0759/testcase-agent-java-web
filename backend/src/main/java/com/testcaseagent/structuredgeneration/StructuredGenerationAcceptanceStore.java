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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Atomically accepts already-validated structured Skill business rows.
 *
 * <p>This store persists typed projections and reference bindings only; it deliberately has no
 * column or parameter for raw model JSON or Markdown. Validation happens before a transaction can
 * write business rows, and a transaction updates the attempt/work terminal state last.</p>
 *
 * [Req-ID]: REQ-STG-001, REQ-STG-006
 */
public final class StructuredGenerationAcceptanceStore {
    public static final int MAX_ATTEMPTS = 2;
    private static final Set<String> FAILURE_TYPES = Set.of("invalid_request", "request_too_large", "session_not_found", "forbidden", "unsupported_skill",
            "skill_unavailable", "model_unavailable", "model_execution_failed", "structured_output_invalid", "response_too_large");
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

    /** Validates and atomically saves a material-review projection. */
    public void acceptReview(WorkClaim claim, RequirementMaterialReviewValidator validator,
            RequirementMaterialReviewValidator.WorkItem workItem, RequirementMaterialReviewValidator.Result result) {
        requireTask(claim, workItem.registry().taskId());
        Objects.requireNonNull(validator, "validator must not be null").validate(workItem, result);
        accept(claim, result, null, "REQUIREMENT_MATERIAL_REVIEW", ignored -> {
            for (RequirementMaterialReviewValidator.RequirementFact fact : result.requirementFacts()) {
                jdbc.update("""
                        INSERT INTO structured_requirement_fact (work_item_id, fact_key, function_name, roles_json,
                        trigger_conditions_json, inputs_json, business_rules_json, outputs_json, permissions_json, state_changes_json,
                        exception_handling_json, external_dependencies_json) VALUES (?, ?, ?, CAST(? AS JSON), CAST(? AS JSON),
                        CAST(? AS JSON), CAST(? AS JSON), CAST(? AS JSON), CAST(? AS JSON), CAST(? AS JSON), CAST(? AS JSON), CAST(? AS JSON))""",
                        claim.workItemId(), fact.factKey(), fact.function(), json(fact.roles()), json(fact.triggerConditions()), json(fact.inputs()),
                        json(fact.businessRules()), json(fact.outputs()), json(fact.permissions()), json(fact.stateChanges()),
                        json(fact.exceptionHandling()), json(fact.externalDependencies()));
                bind(claim.workItemId(), fact.factKey(), "REQUIREMENT_FACT", "EVIDENCE", fact.evidenceKeys());
            }
            for (RequirementMaterialReviewValidator.ReviewFinding finding : result.reviewFindings()) {
                jdbc.update("""
                        INSERT INTO structured_review_finding
                        (work_item_id, finding_key, issue_type, description, test_design_impact, current_project_recommendation,
                        design_center_guideline_recommendation, handling_level) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        claim.workItemId(), finding.findingKey(), finding.issueType(), finding.description(), finding.testDesignImpact(),
                        finding.currentProjectRecommendation(), finding.designCenterGuidelineRecommendation(), finding.handlingLevel().name());
                bind(claim.workItemId(), finding.findingKey(), "REVIEW_FINDING", "EVIDENCE", finding.evidenceKeys());
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
        accept(claim, result, null, "FEATURE_SCOPE_RECONCILIATION", ignored -> {
            for (FeatureReconciliationValidator.Reconciliation row : result.reconciliations()) {
                jdbc.update("""
                        INSERT INTO structured_feature_reconciliation
                        (work_item_id, reconciliation_key, classification, scope_recommendation, confirmation_status)
                        VALUES (?, ?, ?, ?, ?)
                        """, claim.workItemId(), row.reconciliationKey(), row.classification().name(),
                        row.scopeRecommendation(), row.confirmationStatus().name());
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
                jdbc.update("INSERT INTO structured_function_list_item (work_item_id, item_key, path_text, description) VALUES (?, ?, ?, ?)",
                        claim.workItemId(), row.itemKey(), row.path(), row.description());
                bind(claim.workItemId(), row.itemKey(), "FUNCTION_LIST_ITEM", "EVIDENCE", row.evidenceKeys());
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
        accept(claim, result, coverageStatus, "FUNCTIONAL_TESTCASE_DESIGN", ignored -> {
            jdbc.update("""
                    INSERT INTO structured_test_point
                    (work_item_id, test_point_key, function_key, function_name, test_point_type, basis, description, missing_information_json, formal_coverage_satisfied)
                    VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), ?)
                    """, claim.workItemId(), workItem.testPointKey(), workItem.functionKey(), workItem.functionName(),
                    workItem.testPointType().name(), workItem.basis().name(), workItem.description(), json(workItem.missingInformation()), outcome.formalCoverageSatisfied());
            bind(claim.workItemId(), workItem.testPointKey(), "TEST_POINT", "REQUIREMENT_FACT", workItem.requirementFactKeys());
            bind(claim.workItemId(), workItem.testPointKey(), "TEST_POINT", "EVIDENCE", workItem.evidenceKeys());
            for (FunctionalTestcaseResultValidator.Testcase testcase : result.testcases()) {
                jdbc.update("""
                        INSERT INTO structured_test_case
                        (work_item_id, case_key, title, preconditions_json, case_status, missing_information_json)
                        VALUES (?, ?, ?, CAST(? AS JSON), ?, CAST(? AS JSON))""", claim.workItemId(), testcase.caseKey(), testcase.title(),
                        json(testcase.preconditions()), testcase.caseStatus().name(), json(testcase.missingInformation()));
                for (FunctionalTestcaseResultValidator.Step step : testcase.steps()) jdbc.update("""
                        INSERT INTO structured_test_case_step (work_item_id, case_key, step_no, action_text, expected_text)
                        VALUES (?, ?, ?, ?, ?)
                        """, claim.workItemId(), testcase.caseKey(), step.stepNo(), step.action(), step.expected());
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

    private FrozenWork lockedWork(String workItemId) {
        List<FrozenWork> rows = jdbc.query("""
                SELECT id, task_id, identity_key, skill_name, operation_name, status, material_key, allowed_evidence_keys_json,
                       lease_owner, lease_expires_at, accepted_result_sha256
                FROM structured_generation_work_item WHERE id = ? FOR UPDATE
                """, (row, ignored) -> new FrozenWork(row.getString("id"), row.getString("task_id"), row.getString("identity_key"),
                row.getString("skill_name"), row.getString("operation_name"), row.getString("status"), row.getString("material_key"),
                stringList(row.getString("allowed_evidence_keys_json")), row.getString("lease_owner"),
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
            String materialKey, List<String> allowedEvidenceKeys, String leaseOwner, Instant leaseExpiresAt, String acceptedResultSha256) { }
    private record CompletedRow(String status, String hash) { }
}
