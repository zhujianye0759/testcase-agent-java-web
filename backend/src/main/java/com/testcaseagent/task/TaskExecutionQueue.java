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

    public void recoverExpiredClaims() {
        transactionTemplate.executeWithoutResult(ignored -> recoverExpiredClaimsInTransaction(true));
    }

    private void recoverExpiredClaimsInTransaction(boolean recoverUnbatchedAudit) {
        // ALL discovery creates no batch until the agent returns a complete Markdown feature table. At
        // startup an AUDITING task therefore has no batch lease from which to infer recovery; release
        // that abandoned slot before applying the ordinary running-batch lease recovery below.
        if (recoverUnbatchedAudit) {
            jdbcTemplate.update("""
                                UPDATE task_execution_slot s JOIN generation_task t ON t.id = s.task_id
                                LEFT JOIN generation_batch b ON b.task_id = t.id
                                SET s.task_id = NULL, t.status = 'QUEUED'
                                WHERE t.status = 'AUDITING' AND b.id IS NULL
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
