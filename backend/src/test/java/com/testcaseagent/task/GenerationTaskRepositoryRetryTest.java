package com.testcaseagent.task;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testcaseagent.structuredgeneration.StructuredCoverageStatus;
import com.testcaseagent.structuredgeneration.StructuredProcessingStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/**
 * Exercises the repository transaction seam for retrying failed ALL generation batches.
 *
 * [Req-ID]: REQ-CAG-007
 */
class GenerationTaskRepositoryRetryTest {

    private static final String TASK_ID = "task-retry";
    private static final String BATCH_ID = "batch-retry";

    /** [Req-ID]: REQ-CAG-007 */
    @Test
    void rollsBackAllRetryMutationsWhenReplacementAttemptCannotBeInserted() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        GenerationTaskRepository repository = new GenerationTaskRepository(jdbcTemplate, new ObjectMapper(), transactionManager);

        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of(BATCH_ID));
        when(jdbcTemplate.update(contains("UPDATE generation_batch SET status = 'QUEUED'"), eq(BATCH_ID))).thenReturn(1);
        when(jdbcTemplate.update(contains("INSERT INTO generation_attempt"), eq(BATCH_ID), eq(BATCH_ID)))
                .thenThrow(new DataIntegrityViolationException("replacement attempt rejected"));

        assertThatThrownBy(() -> repository.retryFailedBatches(TASK_ID))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(transactionManager).rollback(transactionStatus);
        verify(transactionManager, never()).commit(transactionStatus);
        verify(jdbcTemplate, never()).update(contains("SET artifact_id = NULL"), eq(TASK_ID));
        verify(jdbcTemplate, never()).update(contains("UPDATE generation_task SET status"), any(), any(), any());
    }

    /** [Req-ID]: REQ-STG-007 */
    @Test
    void rejectsStructuredCompletionWithoutAValidatedWorkbookArtifact() {
        GenerationTaskRepository repository = new GenerationTaskRepository(
                mock(JdbcTemplate.class), new ObjectMapper(), mock(PlatformTransactionManager.class));

        assertThatThrownBy(() -> repository.completeStructuredTask(TASK_ID, null,
                StructuredProcessingStatus.COMPLETED, StructuredCoverageStatus.UNABLE_TO_GENERATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("artifact");
    }
}
