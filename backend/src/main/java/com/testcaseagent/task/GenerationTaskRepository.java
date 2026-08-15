package com.testcaseagent.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testcaseagent.export.WorkbookArtifact;
import com.testcaseagent.markdown.MarkdownAuditRow;
import com.testcaseagent.markdown.MarkdownGenerationResult;
import com.testcaseagent.markdown.MarkdownTestCaseRow;
import com.testcaseagent.testcase.GenerationTaskMode;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MySQL persistence boundary for durable task, batch, and attempt state.
 *
 * [Req-ID]: REQ-TSK-001, REQ-TSK-002, REQ-TSK-004, REQ-TSK-005, REQ-TSK-006, REQ-TSK-007,
 * REQ-ANA-007
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

    /** Persists the one immutable feature list discovered after an ALL task was durably queued. */
    public void planDiscoveredBatches(String taskId, CreateGenerationTaskRequest discoveredRequest,
            List<PlannedBatch> plannedBatches) {
        transactionTemplate.executeWithoutResult(ignored -> {
            requireTaskStatus(taskId, GenerationTaskStatus.AUDITING);
            if (plannedBatches.isEmpty()) throw new IllegalArgumentException("Discovered batches must not be empty");
            jdbcTemplate.update("UPDATE generation_task SET request_snapshot = ? WHERE id = ?", asJson(discoveredRequest), taskId);
            for (int index = 0; index < plannedBatches.size(); index++) {
                PlannedBatch batch = plannedBatches.get(index);
                createBatch(batch.batchId(), taskId, batch.featureId(), index + 1);
                createAttempt(batch.attemptId(), batch.batchId());
            }
        });
    }

    /** Records a discovery failure without fabricating a feature batch. */
    public void failAuditingTask(String taskId) {
        requireTaskStatus(taskId, GenerationTaskStatus.AUDITING);
        transitionTask(taskId, GenerationTaskStatus.FAILED);
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
        BatchCounts counts = batchCounts(taskId);
        GenerationTaskStatus terminal = counts.accepted() > 0 && counts.failed() > 0
                ? GenerationTaskStatus.PARTIAL
                : counts.failed() > 0 ? GenerationTaskStatus.FAILED : null;
        if (terminal == null) {
            throw new IllegalStateException("A successful task requires a validated artifact before completion");
        }
        transitionTask(taskId, terminal);
        return terminal;
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
                resultSet.getString("subject_or_feature"),
                resultSet.getString("issue_category"),
                resultSet.getString("evidence_comparison")), taskId);
        List<MarkdownTestCaseRow> testCaseRows = jdbcTemplate.query("""
                        SELECT test_case.case_name, test_case.feature_module, test_case.preconditions,
                               test_case.execution_steps, test_case.expected_result, test_case.requirement_content
                        FROM generation_test_case_row test_case
                        JOIN generation_batch batch ON batch.id = test_case.batch_id
                        WHERE batch.task_id = ? AND batch.status = 'ACCEPTED'
                        ORDER BY batch.batch_sequence, test_case.row_sequence
                        """, (resultSet, ignored) -> new MarkdownTestCaseRow(
                resultSet.getString("case_name"),
                resultSet.getString("feature_module"),
                resultSet.getString("preconditions"),
                resultSet.getString("execution_steps"),
                resultSet.getString("expected_result"),
                resultSet.getString("requirement_content")), taskId);
        return new MarkdownTaskRows(auditRows, testCaseRows);
    }

    /** Completes or partially completes a task using its already-persisted Markdown rows. */
    public void completeMarkdownTask(String taskId, GenerationTaskStatus terminalStatus, WorkbookArtifact artifact) {
        if (terminalStatus != GenerationTaskStatus.COMPLETED && terminalStatus != GenerationTaskStatus.PARTIAL) {
            throw new IllegalArgumentException("Markdown task must finish COMPLETED or PARTIAL");
        }
        requireTaskStatus(taskId, GenerationTaskStatus.VALIDATING);
        int changed = jdbcTemplate.update("""
                        UPDATE generation_task SET status = ?, artifact_id = ?, artifact_sha256 = ?, artifact_path = ?
                        WHERE id = ? AND status = 'VALIDATING'
                        """, terminalStatus.name(), artifact.artifactId(), artifact.sha256(), artifact.path().toString(), taskId);
        if (changed != 1) throw new IllegalStateException("Markdown task completion did not update exactly one validating task");
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
            return new GenerationTaskDetail(
                    row.id(), row.taskMode(), row.status(), counts.total(), counts.completed(),
                    row.artifactId() != null, row.artifactId(), row.artifactSha256(), failureSummary, failureSummary, batches(taskId),
                    acceptedMarkdownRows(taskId),
                    fromJson(row.requestSnapshot(), CreateGenerationTaskRequest.class));
        });
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
                        WHERE artifact_id = ? AND status = 'COMPLETED' AND artifact_sha256 IS NOT NULL
                        """, (resultSet, ignored) -> new StoredArtifact(
                resultSet.getString("artifact_id"),
                resultSet.getString("artifact_sha256"),
                Path.of(resultSet.getString("artifact_path"))), artifactId).stream().findFirst();
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

    public record BatchCounts(int total, int completed, int accepted, int failed) {
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
