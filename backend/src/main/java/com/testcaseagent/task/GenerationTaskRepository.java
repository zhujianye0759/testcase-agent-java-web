package com.testcaseagent.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testcaseagent.export.WorkbookArtifact;
import com.testcaseagent.featureaudit.AuditWorkClaim;
import com.testcaseagent.featureaudit.FeatureReviewConclusion;
import com.testcaseagent.featureaudit.FeatureReviewConclusionType;
import com.testcaseagent.featureaudit.FeatureSourceCandidate;
import com.testcaseagent.featureaudit.FrozenFeatureSource;
import com.testcaseagent.featureaudit.FrozenFeatureTarget;
import com.testcaseagent.featureaudit.MaterialInventoryDocument;
import com.testcaseagent.featureaudit.MaterialInventoryUnit;
import com.testcaseagent.markdown.MarkdownAuditRow;
import com.testcaseagent.markdown.MarkdownGenerationResult;
import com.testcaseagent.markdown.MarkdownTestCaseRow;
import com.testcaseagent.testcase.GenerationTaskMode;
import com.testcaseagent.scope.RequirementScope;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MySQL persistence boundary for durable task, batch, and attempt state.
 *
 * [Req-ID]: REQ-TSK-001, REQ-TSK-002, REQ-TSK-004, REQ-TSK-005, REQ-TSK-006, REQ-TSK-007,
 * REQ-ANA-007, REQ-CWR-003
 */
public final class GenerationTaskRepository {

    private static final int MAX_ATTEMPTS = 3;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public GenerationTaskRepository(
            JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public void createTask(String taskId, CreateGenerationTaskRequest request) {
        jdbcTemplate.update("""
                        INSERT INTO generation_task (id, task_mode, status, request_snapshot)
                        VALUES (?, ?, 'QUEUED', ?)
                        """,
                taskId, request.taskMode().name(), asJson(request));
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
        List<MaterialInventoryDocument> documents = materialInventoryDocuments(taskId);
        if (documents.size() != scope.documents().size()) {
            return false;
        }
        java.util.Set<String> expectedIds = scope.documents().stream()
                .map(com.testcaseagent.scope.RequirementDocumentCoordinate::documentId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!documents.stream().map(MaterialInventoryDocument::documentId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet()).equals(expectedIds)) {
            return false;
        }
        return documents.stream().allMatch(document -> document.complete()
                && document.totalUnits() == materialInventory(document.documentId(), taskId).size());
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
            String attemptId = UUID.randomUUID().toString();
            jdbcTemplate.update("""
                            INSERT INTO material_audit_attempt (id, work_id, attempt_number, status)
                            VALUES (?, ?, ?, 'RUNNING')
                            """, attemptId, work.id(), attemptNumber);
            Instant expiresAt = jdbcTemplate.queryForObject(
                    "SELECT lease_expires_at FROM material_audit_work WHERE id = ?",
                    (resultSet, ignoredRow) -> resultSet.getTimestamp("lease_expires_at").toInstant(), work.id());
            return new AuditWorkClaim(work.id(), attemptId, attemptNumber, work.taskId(), work.documentId(), work.unitId(),
                    work.passNumber(), work.stage(), expiresAt);
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
                jdbcTemplate.update("""
                                INSERT INTO generation_task (id, task_mode, status, idempotency_key, request_snapshot)
                                VALUES (?, ?, 'QUEUED', ?, ?)
                                """,
                        taskId, request.taskMode().name(), idempotencyKey, asJson(request));
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

    /** Records a discovery failure without fabricating a feature batch. */
    public void failAuditingTask(String taskId) {
        requireTaskStatus(taskId, GenerationTaskStatus.AUDITING);
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

    public int retryFailedBatches(String taskId) {
        List<String> failedBatchIds = jdbcTemplate.query("""
                        SELECT b.id FROM generation_batch b
                        JOIN generation_attempt a ON a.batch_id = b.id
                        WHERE b.task_id = ? AND b.status = 'FAILED'
                          AND a.attempt_number = (SELECT MAX(latest.attempt_number)
                              FROM generation_attempt latest WHERE latest.batch_id = b.id)
                          AND a.retryable = TRUE AND a.attempt_number < ?
                        ORDER BY b.batch_sequence
                        """, (resultSet, ignored) -> resultSet.getString("id"), taskId, MAX_ATTEMPTS);
        int retried = 0;
        for (String batchId : failedBatchIds) {
            int batchChanged = jdbcTemplate.update("UPDATE generation_batch SET status = 'QUEUED' WHERE id = ? AND status = 'FAILED'", batchId);
            if (batchChanged != 1) {
                continue;
            }
            jdbcTemplate.update("""
                            INSERT INTO generation_attempt (id, batch_id, attempt_number, status)
                            VALUES (UUID(), ?, (SELECT COALESCE(MAX(attempt_number), 0) + 1 FROM generation_attempt existing WHERE existing.batch_id = ?), 'QUEUED')
                            """, batchId, batchId);
            retried++;
        }
        if (retried > 0) {
            GenerationTaskStatus current = taskStatus(taskId);
            if (current == GenerationTaskStatus.PARTIAL || current == GenerationTaskStatus.FAILED) {
                clearArtifactMetadata(taskId);
                transitionTask(taskId, GenerationTaskStatus.QUEUED);
            }
        }
        if (retried == 0 && taskStatus(taskId) == GenerationTaskStatus.FAILED && isUnbatchedAllTask(taskId)) {
            transitionTask(taskId, GenerationTaskStatus.QUEUED);
            return 1;
        }
        return retried;
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

    /** Removes internal candidate and source-unit binding tokens from reader-facing projections. */
    private static String stripMachineEvidenceTokens(String value) {
        if (value == null || value.isBlank()) return value == null ? "" : value.strip();
        String sanitized = value.replaceAll("(?i)(?:candidateIds|documentId|unitId)\\s*=\\s*[^;\\r\\n<]*(?:;\\s*)?", "");
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
        return jdbcTemplate.query("""
                        SELECT t.id, t.task_mode, t.status, t.request_snapshot, t.result_snapshot,
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
                resultSet.getString("request_snapshot"),
                resultSet.getString("result_snapshot"),
                resultSet.getString("artifact_id"),
                resultSet.getString("artifact_sha256"),
                resultSet.getString("failure_summary")), taskId).stream().findFirst().map(row -> {
            BatchCounts counts = batchCounts(taskId);
            String failureSummary = row.failureSummary();
            CreateGenerationTaskRequest request = fromJson(row.requestSnapshot(), CreateGenerationTaskRequest.class);
            return new GenerationTaskDetail(
                    row.id(), row.taskMode(), row.status(), counts.total(), counts.completed(),
                    row.artifactId() != null, row.artifactId(), row.artifactSha256(), failureSummary, failureSummary, batches(taskId),
                    acceptedMarkdownRows(taskId),
                    request, businessProgress(taskId, row.taskMode(), row.status(), request));
        });
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
        Integer expectedTestCaseTotal = frozenComplete ? counts.eligibleFeatures() * 2 : null;
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
                        SELECT t.id, t.task_mode, t.status, t.created_at, t.artifact_id,
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
                resultSet.getString("failure_summary"),
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

    private record TaskRow(
            String id,
            GenerationTaskMode taskMode,
            GenerationTaskStatus status,
            String requestSnapshot,
            String resultSnapshot,
            String artifactId,
            String artifactSha256,
            String failureSummary) {
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
