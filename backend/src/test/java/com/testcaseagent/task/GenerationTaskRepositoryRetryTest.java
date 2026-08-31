package com.testcaseagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.argThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testcaseagent.structuredgeneration.StructuredCoverageStatus;
import com.testcaseagent.structuredgeneration.StructuredProcessingStatus;
import com.testcaseagent.export.WorkbookArtifact;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.IntStream;
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

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void partitionsSplitLineageIdentitiesBeforeBuildingRecoveryQueries() {
        var identities = new LinkedHashSet<>(IntStream.rangeClosed(1, 259)
                .mapToObj(index -> "work-%03d".formatted(index)).toList());

        List<List<String>> batches = GenerationTaskRepository.partitionV2RecoveryIds(identities);

        assertThat(batches).extracting(List::size).containsExactly(256, 3);
        assertThat(batches.stream().flatMap(List::stream)).containsExactlyElementsOf(identities);
    }

    /** [Req-ID]: REQ-ESR-012 */
    @Test
    void advisoryBusinessRowQueriesScopeEveryFixedTableBeforeTheEarlyExit() {
        List<String> workOwnedTables = List.of(
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

        assertThat(workOwnedTables).allSatisfy(table -> {
            String sql = GenerationTaskRepository.unfinishedWorkOwnedBusinessRowSql(table, false);
            assertThat(sql)
                    .contains("JOIN " + table + " business_row")
                    .contains("WHERE work.task_id = ?")
                    .contains("LIMIT 1")
                    .doesNotContain("UNION ALL", "FOR UPDATE");
        });
    }

    /** [Req-ID]: REQ-CAG-007 */
    @Test
    void rollsBackAllRetryMutationsWhenReplacementAttemptCannotBeInserted() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        GenerationTaskRepository repository = new GenerationTaskRepository(jdbcTemplate, new ObjectMapper(), transactionManager);

        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(jdbcTemplate.query(contains("FROM generation_task WHERE id"), any(RowMapper.class), eq(TASK_ID)))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    ResultSet row = mock(ResultSet.class);
                    when(row.getString("task_mode")).thenReturn("FEATURE");
                    when(row.getString("status")).thenReturn("FAILED");
                    when(row.getString("request_snapshot")).thenReturn("{}");
                    return List.of(mapper.mapRow(row, 0));
                });
        when(jdbcTemplate.query(contains("SELECT b.id FROM generation_batch"),
                any(RowMapper.class), eq(TASK_ID), any())).thenReturn(List.of(BATCH_ID));
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

    /** [Req-ID]: REQ-AFCE-006 */
    @Test
    void publishesCandidateProtocolPartialDeliveryWithoutChangingTheCompletedProcessingAxis() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        GenerationTaskRepository repository = new GenerationTaskRepository(
                jdbcTemplate, new ObjectMapper(), transactionManager);
        WorkbookArtifact artifact = new WorkbookArtifact(
                "artifact-partial", "c".repeat(64), Path.of("partial.xlsx"));
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(jdbcTemplate.query(contains("SELECT status FROM generation_task"),
                any(RowMapper.class), eq(TASK_ID))).thenReturn(List.of("VALIDATING"));
        when(jdbcTemplate.update(argThat(sql -> sql.contains("SET status = ?")
                        && sql.contains("structured_processing_status = ?")),
                eq("PARTIAL"), eq("COMPLETED"), eq("PARTIAL"), eq(artifact.artifactId()),
                eq(artifact.sha256()), eq(artifact.path().toString()), eq(TASK_ID))).thenReturn(1);

        repository.completeStructuredTask(TASK_ID, artifact,
                StructuredProcessingStatus.COMPLETED, StructuredCoverageStatus.PARTIAL, true);

        verify(jdbcTemplate).update(argThat(sql -> sql.contains("SET status = ?")
                        && sql.contains("structured_processing_status = ?")),
                eq("PARTIAL"), eq("COMPLETED"), eq("PARTIAL"), eq(artifact.artifactId()),
                eq(artifact.sha256()), eq(artifact.path().toString()), eq(TASK_ID));
    }

    /** [Req-ID]: REQ-AFCE-006 */
    @Test
    void rejectsCandidateCompletionWhenCoverageIsUnableToGenerate() {
        GenerationTaskRepository repository = new GenerationTaskRepository(
                mock(JdbcTemplate.class), new ObjectMapper(), mock(PlatformTransactionManager.class));
        WorkbookArtifact artifact = new WorkbookArtifact(
                "artifact-untrusted", "d".repeat(64), Path.of("untrusted.xlsx"));

        assertThatThrownBy(() -> repository.completeStructuredTask(TASK_ID, artifact,
                StructuredProcessingStatus.COMPLETED, StructuredCoverageStatus.UNABLE_TO_GENERATE, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trusted");
    }

    /** [Req-ID]: REQ-SGD-005 */
    @Test
    void atomicallyReplacesOnlyCompletedStructuredArtifactMetadata() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        GenerationTaskRepository repository = new GenerationTaskRepository(
                jdbcTemplate, new ObjectMapper(), mock(PlatformTransactionManager.class));
        WorkbookArtifact artifact = new WorkbookArtifact("artifact-safe", "b".repeat(64), Path.of("safe.xlsx"));
        when(jdbcTemplate.update(contains("artifact_id = ?"),
                eq(artifact.artifactId()), eq(artifact.sha256()), eq(artifact.path().toString()), eq(TASK_ID), eq("artifact-old"))).thenReturn(1);

        repository.replaceStructuredArtifact(TASK_ID, "artifact-old", artifact);

        verify(jdbcTemplate).update(contains("artifact_id = ?"),
                eq(artifact.artifactId()), eq(artifact.sha256()), eq(artifact.path().toString()), eq(TASK_ID), eq("artifact-old"));
    }
}
