package com.testcaseagent.task;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Claims at most five durable task slots across all application workers.
 *
 * [Req-ID]: REQ-TSK-003, REQ-TSK-005, REQ-TSK-006, REQ-TSK-008
 */
public final class TaskExecutionQueue {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public TaskExecutionQueue(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public Optional<TaskExecutionClaim> claimNext() {
        return Optional.ofNullable(transactionTemplate.execute(ignored -> {
            recoverExpiredClaimsInTransaction(false);
            Integer slotNumber = jdbcTemplate.query("""
                            SELECT slot_number FROM task_execution_slot
                            WHERE task_id IS NULL
                            ORDER BY slot_number
                            LIMIT 1 FOR UPDATE SKIP LOCKED
                            """, (resultSet, rowNumber) -> resultSet.getInt("slot_number"))
                    .stream().findFirst().orElse(null);
            if (slotNumber == null) {
                return null;
            }
            String taskId = jdbcTemplate.query("""
                            SELECT id FROM generation_task
                            WHERE status = 'QUEUED'
                            ORDER BY created_at, id
                            LIMIT 1 FOR UPDATE
                            """, (resultSet, rowNumber) -> resultSet.getString("id"))
                    .stream().findFirst().orElse(null);
            if (taskId == null) {
                return null;
            }
            int taskUpdated = jdbcTemplate.update("""
                            UPDATE generation_task SET status = 'AUDITING'
                            WHERE id = ? AND status = 'QUEUED'
                            """, taskId);
            int slotUpdated = jdbcTemplate.update("""
                            UPDATE task_execution_slot SET task_id = ?
                            WHERE slot_number = ? AND task_id IS NULL
                            """, taskId, slotNumber);
            if (taskUpdated != 1 || slotUpdated != 1) {
                throw new IllegalStateException("Unable to atomically claim queued task " + taskId);
            }
            return new TaskExecutionClaim(taskId, slotNumber);
        }));
    }

    /**
     * Performs the one-time process-start recovery before this JVM begins claiming work.
     *
     * <p>This is not a general heartbeat: ordinary {@link #claimNext()} only recovers expired running leases.
     * Startup additionally releases an AUDITING claim that has not reached a running batch, including the narrow
     * crash window after frozen batches were committed but before generation began.</p>
     *
     * [Req-ID]: REQ-CAG-001, REQ-TSK-008
     */
    public void recoverAtStartup() {
        transactionTemplate.executeWithoutResult(ignored -> recoverExpiredClaimsInTransaction(true));
    }

    private void recoverExpiredClaimsInTransaction(boolean startupRecovery) {
        if (startupRecovery) {
            // Structured ALL tasks have no legacy generation_batch lease. A process restart is therefore the
            // authoritative crash boundary: invalidate only the still-running structured attempt, preserve every
            // completed accepted row, release the shared slot, and let the normal bounded retry reclaim the work.
            jdbcTemplate.update("""
                    UPDATE structured_generation_attempt attempt
                    JOIN structured_generation_work_item work ON work.id = attempt.work_item_id
                    JOIN generation_task task ON task.id = work.task_id
                    SET attempt.status = 'FAILED', attempt.failure_type = 'model_execution_failed',
                        attempt.completed_at = CURRENT_TIMESTAMP(6)
                    WHERE attempt.status = 'RUNNING' AND work.status = 'RUNNING'
                      AND task.structured_processing_status = 'RUNNING'
                      AND task.status IN ('AUDITING','GENERATING','VALIDATING')
                      AND NOT EXISTS (SELECT 1 FROM generation_batch batch WHERE batch.task_id = task.id)
                    """);
            jdbcTemplate.update("""
                    UPDATE structured_generation_work_item work
                    JOIN generation_task task ON task.id = work.task_id
                    SET work.status = 'FAILED', work.lease_owner = NULL, work.lease_expires_at = NULL
                    WHERE work.status = 'RUNNING' AND task.structured_processing_status = 'RUNNING'
                      AND task.status IN ('AUDITING','GENERATING','VALIDATING')
                      AND NOT EXISTS (SELECT 1 FROM generation_batch batch WHERE batch.task_id = task.id)
                    """);
            jdbcTemplate.update("""
                    UPDATE task_execution_slot slot
                    JOIN generation_task task ON task.id = slot.task_id
                    SET slot.task_id = NULL
                    WHERE task.structured_processing_status = 'RUNNING'
                      AND task.status IN ('AUDITING','GENERATING','VALIDATING')
                      AND NOT EXISTS (SELECT 1 FROM generation_batch batch WHERE batch.task_id = task.id)
                    """);
            jdbcTemplate.update("""
                    UPDATE generation_task task
                    SET task.status = 'QUEUED', task.structured_processing_status = 'PENDING'
                    WHERE task.structured_processing_status = 'RUNNING'
                      AND task.status IN ('AUDITING','GENERATING','VALIDATING')
                      AND NOT EXISTS (SELECT 1 FROM generation_batch batch WHERE batch.task_id = task.id)
                    """);
            // The process-start runner alone recovers an AUDITING claim with no running batch. It covers both the
            // unplanned audit phase and the committed-frozen-plan window, without treating live workers as dead.
            jdbcTemplate.update("""
                                UPDATE task_execution_slot s JOIN generation_task t ON t.id = s.task_id
                                SET s.task_id = NULL, t.status = 'QUEUED'
                                WHERE t.status = 'AUDITING'
                                  AND NOT EXISTS (
                                      SELECT 1 FROM generation_batch b
                                      WHERE b.task_id = t.id AND b.status = 'RUNNING')
                                """);
        }
        jdbcTemplate.update("""
                            UPDATE task_execution_slot s JOIN generation_task t ON t.id = s.task_id
                            JOIN generation_batch b ON b.task_id = t.id
                            JOIN generation_attempt a ON a.batch_id = b.id
                            SET s.task_id = NULL, t.status = 'QUEUED', b.status = 'QUEUED',
                                b.lease_owner = NULL, b.lease_expires_at = NULL,
                                a.status = CASE WHEN a.status = 'RUNNING' THEN 'QUEUED' ELSE a.status END,
                                a.completed_at = CASE WHEN a.status = 'RUNNING' THEN NULL ELSE a.completed_at END
                            WHERE b.status = 'RUNNING' AND b.lease_expires_at < CURRENT_TIMESTAMP(6)
                            """);
    }

    public void release(TaskExecutionClaim claim) {
        int updated = jdbcTemplate.update("""
                        UPDATE task_execution_slot SET task_id = NULL
                        WHERE slot_number = ? AND task_id = ?
                        """, claim.slotNumber(), claim.taskId());
        if (updated != 1) {
            throw new IllegalStateException("Execution slot is not owned by task " + claim.taskId());
        }
    }
}
