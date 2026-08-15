package com.testcaseagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testcaseagent.export.WorkbookArtifact;
import com.testcaseagent.fewshot.ExampleScope;
import com.testcaseagent.markdown.MarkdownAuditRow;
import com.testcaseagent.markdown.MarkdownGenerationResult;
import com.testcaseagent.markdown.MarkdownTestCaseRow;
import com.testcaseagent.scope.RequirementDocumentCoordinate;
import com.testcaseagent.scope.RequirementScope;
import com.testcaseagent.testcase.FewShotPolicy;
import com.testcaseagent.testcase.GenerationTaskMode;
import com.testcaseagent.web.TestCaseAgentApplication;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the MySQL Markdown result accumulation seam.
 *
 * [Test-Ref]: MarkdownBatchPersistenceIntegrationTest
 * [Req-ID]: REQ-TSK-004, REQ-TSK-005, REQ-TSK-007, REQ-TSK-009, REQ-ANA-006, REQ-ANA-007
 */
@Testcontainers
@SpringBootTest(classes = TestCaseAgentApplication.class, properties = "app.knowledge-agent.enabled=false")
@Import(MarkdownBatchPersistenceIntegrationTest.RepositoryDependencies.class)
class MarkdownBatchPersistenceIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("markdown_batch_persistence_test")
            .withUsername("testcase_agent")
            .withPassword("test-only-password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    GenerationTaskRepository repository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM generation_audit_row");
        jdbcTemplate.update("DELETE FROM generation_test_case_row");
        jdbcTemplate.update("DELETE FROM generation_attempt");
        jdbcTemplate.update("DELETE FROM generation_batch");
        jdbcTemplate.update("DELETE FROM generation_task");
    }

    @Test
    void accumulatesThreeAcceptedBatchesByPersistedBatchAndRowSequence() {
        String taskId = taskId();
        repository.createTask(taskId, request());

        accept(taskId, "batch-third", "attempt-third", "feature-third", 3, markdownResult("third"));
        accept(taskId, "batch-first", "attempt-first", "feature-first", 1, markdownResult("first"));
        accept(taskId, "batch-second", "attempt-second", "feature-second", 2, markdownResult("second"));

        MarkdownTaskRows rows = repository.acceptedMarkdownRows(taskId);

        assertThat(rows.auditRows()).extracting(MarkdownAuditRow::subjectOrFeature)
                .containsExactly("feature-first", "feature-second", "feature-third");
        assertThat(rows.testCaseRows()).extracting(MarkdownTestCaseRow::caseName)
                .containsExactly("case-first", "case-second", "case-third");
    }

    @Test
    void cooperativelyCancelsRemainingBatchesWithoutDeletingAnAcceptedBatch() {
        String taskId = taskId();
        repository.createTask(taskId, request());
        accept(taskId, "batch-accepted", "attempt-accepted", "feature-accepted", 1, markdownResult("accepted"));
        repository.createBatch("batch-running", taskId, "feature-running", 2);
        repository.createAttempt("attempt-running", "batch-running");
        repository.createBatch("batch-queued", taskId, "feature-queued", 3);
        repository.createAttempt("attempt-queued", "batch-queued");
        repository.transitionTask(taskId, GenerationTaskStatus.AUDITING);
        repository.transitionTask(taskId, GenerationTaskStatus.GENERATING);
        repository.startBatch("batch-running", "attempt-running");

        assertThat(repository.requestCancellation(taskId)).isTrue();
        assertThat(repository.cancelAtCheckpoint(taskId, "batch-running", "attempt-running")).isTrue();

        assertThat(repository.taskStatus(taskId)).isEqualTo(GenerationTaskStatus.CANCELLED);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM generation_batch WHERE id = ?", String.class, "batch-accepted"))
                .isEqualTo("ACCEPTED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM generation_batch WHERE id = ?", String.class, "batch-running"))
                .isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM generation_batch WHERE id = ?", String.class, "batch-queued"))
                .isEqualTo("CANCELLED");
    }

    @Test
    void rejectsAnAcceptedBatchReplayWithoutReplacingItsRows() {
        String taskId = taskId();
        repository.createTask(taskId, request());
        accept(taskId, "batch-one", "attempt-one", "feature-one", 1, markdownResult("one"));

        assertThatThrownBy(() -> repository.acceptMarkdownBatch("batch-one", "attempt-one", markdownResult("replacement")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already accepted");

        MarkdownTaskRows rows = repository.acceptedMarkdownRows(taskId);
        assertThat(rows.auditRows()).extracting(MarkdownAuditRow::subjectOrFeature).containsExactly("feature-one");
        assertThat(rows.testCaseRows()).extracting(MarkdownTestCaseRow::caseName).containsExactly("case-one");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM generation_batch WHERE id = ?", String.class, "batch-one")).isEqualTo("ACCEPTED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM generation_attempt WHERE id = ?", String.class, "attempt-one")).isEqualTo("COMPLETED");
    }

    @Test
    void rollsBackANewBatchWriteAndLetsARetriedFailedBatchAddRowsOnceWithoutLosingEarlierRows() {
        String taskId = taskId();
        repository.createTask(taskId, request());
        accept(taskId, "batch-accepted", "attempt-accepted", "feature-accepted", 1, markdownResult("accepted"));

        repository.createBatch("batch-retry", taskId, "feature-retry", 2);
        repository.createAttempt("attempt-retry-one", "batch-retry");
        repository.startBatch("batch-retry", "attempt-retry-one");

        MarkdownGenerationResult invalidResult = new MarkdownGenerationResult("invalid", List.of(), List.of(
                new MarkdownTestCaseRow("case-invalid", "feature-retry", "precondition", "step", null, "requirement")));
        assertThatThrownBy(() -> repository.acceptMarkdownBatch("batch-retry", "attempt-retry-one", invalidResult))
                .isInstanceOf(RuntimeException.class);

        assertThat(repository.acceptedMarkdownRows(taskId).testCaseRows()).extracting(MarkdownTestCaseRow::caseName)
                .containsExactly("case-accepted");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM generation_batch WHERE id = ?", String.class, "batch-retry")).isEqualTo("RUNNING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM generation_attempt WHERE id = ?", String.class, "attempt-retry-one")).isEqualTo("RUNNING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM generation_test_case_row WHERE batch_id = ?", Integer.class, "batch-retry")).isZero();

        repository.failBatch("batch-retry", "attempt-retry-one", "temporary persistence failure", true);
        assertThat(repository.acceptedMarkdownRows(taskId).testCaseRows()).extracting(MarkdownTestCaseRow::caseName)
                .containsExactly("case-accepted");
        assertThat(repository.retryFailedBatches(taskId)).isEqualTo(1);
        String retryAttempt = jdbcTemplate.queryForObject(
                "SELECT id FROM generation_attempt WHERE batch_id = ? AND attempt_number = 2", String.class, "batch-retry");
        repository.startBatch("batch-retry", retryAttempt);
        repository.acceptMarkdownBatch("batch-retry", retryAttempt, markdownResult("retry"));

        assertThat(repository.acceptedMarkdownRows(taskId).testCaseRows()).extracting(MarkdownTestCaseRow::caseName)
                .containsExactly("case-accepted", "case-retry");
    }

    @Test
    void hidesHistoricalRetryFailureAfterTheReplacementAttemptIsAccepted() {
        String taskId = taskId();
        repository.createTask(taskId, request());
        repository.createBatch("batch-retried", taskId, "feature-retried", 1);
        repository.createAttempt("attempt-retried-one", "batch-retried");
        repository.startBatch("batch-retried", "attempt-retried-one");
        repository.failBatch("batch-retried", "attempt-retried-one", "first Markdown contract failure", true);

        assertThat(repository.retryFailedBatches(taskId)).isEqualTo(1);
        String retryAttempt = jdbcTemplate.queryForObject(
                "SELECT id FROM generation_attempt WHERE batch_id = ? AND attempt_number = 2", String.class, "batch-retried");
        repository.startBatch("batch-retried", retryAttempt);
        repository.acceptMarkdownBatch("batch-retried", retryAttempt, markdownResult("retried"));
        repository.transitionTask(taskId, GenerationTaskStatus.AUDITING);
        repository.transitionTask(taskId, GenerationTaskStatus.GENERATING);
        repository.transitionTask(taskId, GenerationTaskStatus.VALIDATING);
        repository.completeMarkdownTask(taskId, GenerationTaskStatus.COMPLETED,
                new WorkbookArtifact("artifact-retried", "sha256-retried", Path.of("artifacts", "retried.xlsx")));

        GenerationTaskDetail detail = repository.findDetail(taskId).orElseThrow();

        assertThat(detail.status()).isEqualTo(GenerationTaskStatus.COMPLETED);
        assertThat(detail.validationFailure()).isNull();
        assertThat(detail.failureSummary()).isNull();
        assertThat(detail.batches()).singleElement().satisfies(batch -> {
            assertThat(batch.status()).isEqualTo(GenerationBatchStatus.ACCEPTED);
            assertThat(batch.failureSummary()).isNull();
        });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT failure_reason FROM generation_attempt WHERE id = ?", String.class, "attempt-retried-one"))
                .isEqualTo("first Markdown contract failure");
    }

    @Test
    void keepsTheLatestFailureVisibleForAPartialTask() {
        String taskId = taskId();
        repository.createTask(taskId, request());
        accept(taskId, "batch-accepted", "attempt-accepted", "feature-accepted", 1, markdownResult("accepted"));
        repository.createBatch("batch-failed", taskId, "feature-failed", 2);
        repository.createAttempt("attempt-failed", "batch-failed");
        repository.startBatch("batch-failed", "attempt-failed");
        repository.failBatch("batch-failed", "attempt-failed", "current failure", false);
        repository.transitionTask(taskId, GenerationTaskStatus.AUDITING);
        repository.transitionTask(taskId, GenerationTaskStatus.GENERATING);
        repository.transitionTask(taskId, GenerationTaskStatus.VALIDATING);
        repository.finishTaskFromBatches(taskId);

        GenerationTaskDetail detail = repository.findDetail(taskId).orElseThrow();

        assertThat(detail.status()).isEqualTo(GenerationTaskStatus.PARTIAL);
        assertThat(detail.failureSummary()).isEqualTo("current failure");
        assertThat(detail.batches()).filteredOn(batch -> batch.id().equals("batch-failed")).singleElement()
                .extracting(GenerationBatchDetail::failureSummary).isEqualTo("current failure");
    }

    @Test
    void requeuesAnUnbatchedAllDiscoveryFailureForAnotherBackgroundDiscoveryAttempt() {
        String taskId = taskId();
        CreateGenerationTaskRequest pendingAll = new CreateGenerationTaskRequest(GenerationTaskMode.ALL, "all-pending",
                List.of(), java.util.Map.of(), FewShotPolicy.AUTO, "markdown-1.0", "1.0", "markdown-agent",
                new RequirementScope("requirement-kb", "system-1", "version-1", "admission_material", null,
                        List.of(new RequirementDocumentCoordinate("document-1"))),
                new ExampleScope("example-kb", List.of("example-1")), List.of("function_list"), "发现全部功能");
        repository.createTask(taskId, pendingAll);
        repository.transitionTask(taskId, GenerationTaskStatus.AUDITING);
        repository.failAuditingTask(taskId);

        assertThat(repository.retryFailedBatches(taskId)).isEqualTo(1);
        assertThat(repository.taskStatus(taskId)).isEqualTo(GenerationTaskStatus.QUEUED);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM generation_batch WHERE task_id = ?", Integer.class, taskId)).isZero();
    }

    private void accept(
            String taskId, String batchId, String attemptId, String featureId, int batchSequence, MarkdownGenerationResult result) {
        repository.createBatch(batchId, taskId, featureId, batchSequence);
        repository.createAttempt(attemptId, batchId);
        repository.startBatch(batchId, attemptId);
        repository.acceptMarkdownBatch(batchId, attemptId, result);
    }

    private static MarkdownGenerationResult markdownResult(String suffix) {
        return new MarkdownGenerationResult(
                "raw markdown " + suffix,
                List.of(new MarkdownAuditRow(1, "feature-" + suffix, "AMBIGUOUS", "evidence-" + suffix)),
                List.of(new MarkdownTestCaseRow(
                        "case-" + suffix, "feature-" + suffix, "precondition-" + suffix,
                        "step-" + suffix, "expected-" + suffix, "requirement-" + suffix)));
    }

    private static String taskId() {
        return UUID.randomUUID().toString();
    }

    private static CreateGenerationTaskRequest request() {
        return new CreateGenerationTaskRequest(
                GenerationTaskMode.FEATURE, "feature", FewShotPolicy.NONE, "1.0", "1.0", "markdown-agent",
                new RequirementScope("requirement-kb", "system-1", "version-1", "admission_material", "project-1",
                        List.of(new RequirementDocumentCoordinate("document-1"))),
                new ExampleScope("example-kb", List.of("example-1")), "requirements_spec", "markdown persistence test");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RepositoryDependencies {

        @Bean
        GenerationTaskRepository generationTaskRepository(
                JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
            return new GenerationTaskRepository(jdbcTemplate, objectMapper, transactionManager);
        }
    }
}
