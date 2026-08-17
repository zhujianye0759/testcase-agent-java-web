package com.testcaseagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testcaseagent.export.ApachePoiWorkbookExporter;
import com.testcaseagent.export.MarkdownWorkbookExportRequest;
import com.testcaseagent.export.WorkbookArtifact;
import com.testcaseagent.fewshot.ExampleScope;
import com.testcaseagent.featureaudit.FeatureCandidateKind;
import com.testcaseagent.featureaudit.FinalReconciliationPageException;
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
import com.testcaseagent.scope.RequirementDocumentCoordinate;
import com.testcaseagent.scope.RequirementScope;
import com.testcaseagent.testcase.FewShotPolicy;
import com.testcaseagent.testcase.GenerationTaskMode;
import com.testcaseagent.web.TestCaseAgentApplication;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

    @TempDir
    Path artifactRoot;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM material_audit_duplicate_occurrence");
        jdbcTemplate.update("DELETE FROM material_audit_scan_outcome");
        jdbcTemplate.update("DELETE FROM material_audit_attempt");
        jdbcTemplate.update("DELETE FROM material_audit_work");
        jdbcTemplate.update("DELETE FROM feature_review_conclusion_candidate");
        jdbcTemplate.update("DELETE FROM feature_review_conclusion");
        jdbcTemplate.update("DELETE FROM frozen_feature_target");
        jdbcTemplate.update("DELETE FROM feature_source_candidate");
        jdbcTemplate.update("DELETE FROM material_inventory_unit");
        jdbcTemplate.update("DELETE FROM material_inventory_document");
        jdbcTemplate.update("DELETE FROM generation_audit_row");
        jdbcTemplate.update("DELETE FROM generation_test_case_row");
        jdbcTemplate.update("DELETE FROM generation_attempt");
        jdbcTemplate.update("DELETE FROM generation_batch");
        jdbcTemplate.update("DELETE FROM generation_task");
    }

    /** [Req-ID]: REQ-CWR-003 */
    @Test
    void exportsTaskOwnedReviewConclusionsAndAllAcceptedBatchCasesWithoutTechnicalCandidateTokens() throws Exception {
        String taskId = taskId();
        repository.createTask(taskId, allRequest());
        accept(taskId, "batch-second", "attempt-second", "feature-second", 2,
                twoCaseMarkdownResultWithEvidence("second", "需求摘要第二<br>candidateIds=second-a; groupAnchorId=second-a"));
        accept(taskId, "batch-first", "attempt-first", "feature-first", 1,
                twoCaseMarkdownResultWithEvidence("first", "需求摘要第一\ncandidateIds=first-a"));
        persistConclusion(taskId, "conclusion-second", 2, "FUNCTION_LIST_MISSING", "功能清单遗漏：订单导出",
                "功能清单片段B<br>candidateIds=second-a");
        persistConclusion(taskId, "conclusion-first", 1, "MATCHED", "订单查询",
                "需求片段A<br>candidateIds=first-a; groupAnchorId=first-a; documentId=document-a; unitId=unit-a");

        MarkdownTaskRows rows = repository.exportMarkdownRows(taskId);
        MarkdownTaskRows taskDetailRows = repository.acceptedMarkdownRows(taskId);

        assertThat(rows.auditRows()).extracting(MarkdownAuditRow::sequence, MarkdownAuditRow::subjectOrFeature,
                        MarkdownAuditRow::issueCategory, MarkdownAuditRow::evidenceComparison)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, "订单查询", "未发现问题",
                                "需求片段A"),
                        org.assertj.core.groups.Tuple.tuple(2, "功能清单遗漏：订单导出", "功能清单遗漏", "功能清单片段B"));
        assertThat(rows.testCaseRows()).extracting(MarkdownTestCaseRow::caseName)
                .containsExactly("case-first_正向", "case-first_反向", "case-second_正向", "case-second_反向");
        assertThat(rows.testCaseRows()).extracting(MarkdownTestCaseRow::requirementContent)
                .containsExactly("需求摘要第一", "依据通用经验，待确认", "需求摘要第二", "依据通用经验，待确认");
        assertThat(rows.auditRows()).allSatisfy(row -> assertThat(row.evidenceComparison())
                .doesNotContain("candidateIds=", "groupAnchorId="));
        assertThat(rows.testCaseRows()).allSatisfy(row -> assertThat(row.requirementContent())
                .doesNotContain("candidateIds=", "groupAnchorId="));
        assertThat(taskDetailRows.auditRows()).allSatisfy(row -> assertThat(row.subjectOrFeature() + row.issueCategory()
                + row.evidenceComparison()).doesNotContain("candidateIds=", "groupAnchorId=", "documentId=", "unitId="));
        assertThat(taskDetailRows.testCaseRows()).allSatisfy(row -> assertThat(row.caseName() + row.featureModule()
                + row.preconditions() + row.executionSteps() + row.expectedResult() + row.requirementContent())
                .doesNotContain("candidateIds=", "groupAnchorId=", "documentId=", "unitId="));

        WorkbookArtifact artifact = new ApachePoiWorkbookExporter(artifactRoot).exportMarkdown(
                new MarkdownWorkbookExportRequest(taskId, rows.auditRows(), rows.testCaseRows(), true, false));
        try (XSSFWorkbook workbook = new XSSFWorkbook(artifact.path().toFile())) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            assertThat(workbook.getSheetName(0)).isEqualTo("需求与功能清单审查发现");
            assertThat(workbook.getSheetName(1)).isEqualTo("测试用例");
            assertThat(workbook.getSheetAt(0).getRow(0).getLastCellNum()).isEqualTo((short) 4);
            assertThat(workbook.getSheetAt(1).getRow(0).getLastCellNum()).isEqualTo((short) 6);
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(3).getStringCellValue())
                    .isEqualTo("需求片段A");
            assertThat(workbook.getSheetAt(1).getRow(1).getCell(5).getStringCellValue()).isEqualTo("需求摘要第一");
            assertThat(workbook.getSheetAt(1).getRow(4).getCell(0).getStringCellValue()).isEqualTo("case-second_反向");
        }
        assertThatThrownBy(() -> new ApachePoiWorkbookExporter(artifactRoot).exportMarkdown(
                new MarkdownWorkbookExportRequest(taskId, List.of(new MarkdownAuditRow(1, "订单查询", "已匹配",
                        "需求摘要; documentId=document-a; unitId=unit-a; candidateIds=first-a")),
                        rows.testCaseRows(), true, false)))
                .hasMessageContaining("internal evidence binding tokens");
        assertThatThrownBy(() -> new ApachePoiWorkbookExporter(artifactRoot).exportMarkdown(
                new MarkdownWorkbookExportRequest(taskId, List.of(new MarkdownAuditRow(1, "订单查询", "已匹配",
                        "需求摘要; groupAnchorId=first-a")), rows.testCaseRows(), true, false)))
                .hasMessageContaining("internal evidence binding tokens");
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
    void transitionsAnAuditingTaskToCancelledAtTheAuditCheckpoint() {
        String taskId = taskId();
        repository.createTask(taskId, request());
        repository.transitionTask(taskId, GenerationTaskStatus.AUDITING);

        assertThat(repository.requestCancellation(taskId)).isTrue();
        assertThat(repository.cancelAuditingAtCheckpoint(taskId)).isTrue();

        assertThat(repository.taskStatus(taskId)).isEqualTo(GenerationTaskStatus.CANCELLED);
    }

    @Test
    void rejectsAnAcceptedBatchReplayWithoutReplacingItsRows() {
        String taskId = taskId();
        repository.createTask(taskId, request());
        accept(taskId, "batch-one", "attempt-one", "feature-one", 1, twoCaseMarkdownResult("one"));

        assertThatThrownBy(() -> repository.acceptMarkdownBatch("batch-one", "attempt-one", twoCaseMarkdownResult("replacement")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already accepted");

        MarkdownTaskRows rows = repository.acceptedMarkdownRows(taskId);
        assertThat(rows.auditRows()).extracting(MarkdownAuditRow::subjectOrFeature).containsExactly("feature-one");
        assertThat(rows.testCaseRows()).extracting(MarkdownTestCaseRow::caseName)
                .containsExactly("case-one_正向", "case-one_反向");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM generation_test_case_row WHERE batch_id = ?", Integer.class, "batch-one"))
                .isEqualTo(2);
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
    void atomicallyReplacesOnlyTheRetriedBatchWithItsTwoCaseRowsAndPreservesAttemptHistory() {
        String taskId = taskId();
        repository.createTask(taskId, allRequest());
        accept(taskId, "batch-stable", "attempt-stable", "feature-stable", 1, twoCaseMarkdownResult("stable"));

        repository.createBatch("batch-retry", taskId, "feature-retry", 2);
        repository.createAttempt("attempt-retry-one", "batch-retry");
        repository.startBatch("batch-retry", "attempt-retry-one");
        MarkdownGenerationResult invalidResult = new MarkdownGenerationResult("invalid", List.of(), List.of(
                new MarkdownTestCaseRow("case-invalid", "feature-retry", "precondition", "step", null, "requirement")));

        assertThatThrownBy(() -> repository.acceptMarkdownBatch("batch-retry", "attempt-retry-one", invalidResult))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM generation_test_case_row WHERE batch_id = ?", Integer.class, "batch-retry"))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM generation_batch WHERE id = ?", String.class, "batch-retry")).isEqualTo("RUNNING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM generation_attempt WHERE id = ?", String.class, "attempt-retry-one")).isEqualTo("RUNNING");

        repository.failBatch("batch-retry", "attempt-retry-one", "temporary persistence failure", true);
        assertThat(repository.retryFailedBatches(taskId)).isEqualTo(1);
        String retryAttempt = jdbcTemplate.queryForObject(
                "SELECT id FROM generation_attempt WHERE batch_id = ? AND attempt_number = 2", String.class, "batch-retry");
        repository.startBatch("batch-retry", retryAttempt);
        repository.acceptMarkdownBatch("batch-retry", retryAttempt, twoCaseMarkdownResult("retry"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM generation_test_case_row WHERE batch_id = ?", Integer.class, "batch-retry"))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM generation_test_case_row WHERE batch_id = ?", Integer.class, "batch-stable"))
                .isEqualTo(2);
        assertThat(repository.acceptedMarkdownRows(taskId).testCaseRows()).extracting(MarkdownTestCaseRow::caseName)
                .containsExactly("case-stable_正向", "case-stable_反向", "case-retry_正向", "case-retry_反向");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM generation_attempt WHERE id = ?", String.class, "attempt-retry-one")).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM generation_attempt WHERE id = ?", String.class, retryAttempt)).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM generation_batch WHERE id = ?", String.class, "batch-retry")).isEqualTo("ACCEPTED");
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
        repository.completeMarkdownTask(taskId, GenerationTaskStatus.PARTIAL,
                new WorkbookArtifact("artifact-partial-detail", "sha256-partial-detail", Path.of("artifacts", "partial-detail.xlsx")));

        GenerationTaskDetail detail = repository.findDetail(taskId).orElseThrow();

        assertThat(detail.status()).isEqualTo(GenerationTaskStatus.PARTIAL);
        assertThat(detail.failureSummary()).isEqualTo("current failure");
        assertThat(detail.batches()).filteredOn(batch -> batch.id().equals("batch-failed")).singleElement()
                .extracting(GenerationBatchDetail::failureSummary).isEqualTo("current failure");
    }

    @Test
    void requeuesAnUnbatchedAllDiscoveryFailureWithoutRetainingItsHistoricalFailureSummary() {
        String taskId = taskId();
        CreateGenerationTaskRequest pendingAll = new CreateGenerationTaskRequest(GenerationTaskMode.ALL, "all-pending",
                List.of(), java.util.Map.of(), FewShotPolicy.AUTO, "markdown-1.0", "1.0", "markdown-agent",
                new RequirementScope("requirement-kb", "system-1", "version-1", "admission_material", null,
                        List.of(new RequirementDocumentCoordinate("document-1"))),
                new ExampleScope("example-kb", List.of("example-1")), List.of("function_list"), "发现全部功能");
        repository.createTask(taskId, pendingAll);
        repository.transitionTask(taskId, GenerationTaskStatus.AUDITING);
        repository.failAuditingTask(taskId, "最终双向核对未满足完整性约定，未冻结功能范围");

        assertThat(repository.findDetail(taskId).orElseThrow().failureSummary())
                .isEqualTo("最终双向核对未满足完整性约定，未冻结功能范围");

        assertThat(repository.retryFailedBatches(taskId)).isEqualTo(1);
        assertThat(repository.taskStatus(taskId)).isEqualTo(GenerationTaskStatus.QUEUED);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM generation_batch WHERE task_id = ?", Integer.class, taskId)).isZero();
        assertThat(repository.findDetail(taskId).orElseThrow().failureSummary()).isNull();
        assertThat(repository.findPage(0, 1, taskId).items()).singleElement()
                .extracting(GenerationTaskListItem::failureSummary).isNull();

        repository.transitionTask(taskId, GenerationTaskStatus.AUDITING);

        assertThat(repository.findDetail(taskId).orElseThrow().failureSummary()).isNull();
        assertThat(repository.findPage(0, 1, taskId).items()).singleElement()
                .extracting(GenerationTaskListItem::failureSummary).isNull();
    }

    @Test
    // [Req-ID]: REQ-CWR-002
    void exposesASafeAuditingFailureSummaryWhenNoGenerationBatchExists() {
        String taskId = taskId();
        repository.createTask(taskId, request());
        repository.transitionTask(taskId, GenerationTaskStatus.AUDITING);

        repository.failAuditingTask(taskId, "最终双向核对未满足完整性约定，未冻结功能范围");

        GenerationTaskDetail detail = repository.findDetail(taskId).orElseThrow();
        assertThat(detail.status()).isEqualTo(GenerationTaskStatus.FAILED);
        assertThat(detail.failureSummary()).isEqualTo("最终双向核对未满足完整性约定，未冻结功能范围");
        assertThat(detail.batches()).isEmpty();
        assertThat(repository.findPage(0, 1, taskId).items()).singleElement()
                .extracting(GenerationTaskListItem::failureSummary)
                .isEqualTo("最终双向核对未满足完整性约定，未冻结功能范围");
    }

    @Test
    // [Req-ID]: REQ-CWR-004
    void projectsOnlyTheSafeFinalReconciliationPageSummaryToTaskDetailAndList() {
        String taskId = taskId();
        repository.createTask(taskId, request());
        repository.transitionTask(taskId, GenerationTaskStatus.AUDITING);
        FinalReconciliationPageException pageFailure = FinalReconciliationPageException.exhausted(2, 9, 3,
                "https://internal.invalid?secret=red-team-only; documentId=hidden; unitId=hidden");

        repository.failAuditingTask(taskId, pageFailure.safeSummary());

        String expected = "最终双向核对第 2/9 个功能审核批次连续 3 次未通过：固定合同未满足";
        assertThat(repository.findDetail(taskId).orElseThrow().failureSummary()).isEqualTo(expected)
                .doesNotContain("internal.invalid", "red-team-only", "documentId", "unitId");
        assertThat(repository.findPage(0, 1, taskId).items()).singleElement()
                .extracting(GenerationTaskListItem::failureSummary).isEqualTo(expected);
        assertThat(jdbcTemplate.queryForObject("SELECT result_snapshot FROM generation_task WHERE id = ?", String.class, taskId))
                .isEqualTo("{\"failureSummary\":\"" + expected + "\"}")
                .doesNotContain("internal.invalid", "red-team-only", "documentId", "unitId");
    }

    @Test
    void rejectsAllCompletionWhenAnAcceptedFrozenBatchDoesNotContainExactlyTwoCases() {
        String taskId = taskId();
        prepareAuditedAllTask(taskId, List.of(target("feature-one", 1, true)));
        accept(taskId, "batch-one", "attempt-one", "feature-one", 1, markdownResult("one"));
        transitionToValidating(taskId);

        assertThatThrownBy(() -> repository.completeMarkdownTask(taskId, GenerationTaskStatus.COMPLETED,
                new WorkbookArtifact("artifact-one", "sha256-one", Path.of("artifacts", "one.xlsx"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ALL completion");

        assertThat(repository.taskStatus(taskId)).isEqualTo(GenerationTaskStatus.VALIDATING);
        assertThat(repository.findReadyArtifact("artifact-one")).isEmpty();
    }

    @Test
    void publishesAnAuditedPartialArtifactAndMakesItDownloadable() {
        String taskId = taskId();
        prepareAuditedAllTask(taskId, List.of(target("feature-one", 1, true), target("feature-ineligible", 2, false)));
        accept(taskId, "batch-one", "attempt-one", "feature-one", 1, twoCaseMarkdownResult("one"));
        transitionToValidating(taskId);
        WorkbookArtifact artifact = new WorkbookArtifact("artifact-partial", "sha256-partial", Path.of("artifacts", "partial.xlsx"));

        repository.completeMarkdownTask(taskId, GenerationTaskStatus.PARTIAL, artifact);

        assertThat(repository.taskStatus(taskId)).isEqualTo(GenerationTaskStatus.PARTIAL);
        assertThat(repository.findReadyArtifact(artifact.artifactId())).contains(new GenerationTaskRepository.StoredArtifact(
                artifact.artifactId(), artifact.sha256(), artifact.path()));
    }

    @Test
    void refusesPartialWhileAnEligibleFrozenTargetStillHasQueuedGenerationWork() {
        String taskId = taskId();
        prepareAuditedAllTask(taskId, List.of(target("feature-one", 1, true), target("feature-two", 2, true),
                target("feature-ineligible", 3, false)));
        accept(taskId, "batch-one", "attempt-one", "feature-one", 1, twoCaseMarkdownResult("one"));
        repository.createBatch("batch-two", taskId, "feature-two", 2);
        repository.createAttempt("attempt-two", "batch-two");
        transitionToValidating(taskId);

        assertThatThrownBy(() -> repository.completeMarkdownTask(taskId, GenerationTaskStatus.PARTIAL,
                new WorkbookArtifact("artifact-queued", "sha256-queued", Path.of("artifacts", "queued.xlsx"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("partial artifact gate");
    }

    @Test
    void clearsThePreviousPartialArtifactBeforeRetryingOnlyTheFailedBatch() {
        String taskId = taskId();
        repository.createTask(taskId, request());
        accept(taskId, "batch-accepted", "attempt-accepted", "feature-accepted", 1, markdownResult("accepted"));
        repository.createBatch("batch-retry", taskId, "feature-retry", 2);
        repository.createAttempt("attempt-retry", "batch-retry");
        repository.startBatch("batch-retry", "attempt-retry");
        repository.failBatch("batch-retry", "attempt-retry", "temporary failure", true);
        transitionToValidating(taskId);
        repository.completeMarkdownTask(taskId, GenerationTaskStatus.PARTIAL,
                new WorkbookArtifact("artifact-before-retry", "sha256-before-retry", Path.of("artifacts", "before-retry.xlsx")));

        assertThat(repository.retryFailedBatches(taskId)).isEqualTo(1);

        assertThat(repository.taskStatus(taskId)).isEqualTo(GenerationTaskStatus.QUEUED);
        assertThat(repository.findReadyArtifact("artifact-before-retry")).isEmpty();
        assertThat(repository.acceptedMarkdownRows(taskId).testCaseRows()).extracting(MarkdownTestCaseRow::caseName)
                .containsExactly("case-accepted");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM generation_batch WHERE id = ?", String.class,
                "batch-accepted")).isEqualTo("ACCEPTED");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM generation_attempt WHERE batch_id = ?", Integer.class,
                "batch-accepted")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM generation_attempt WHERE batch_id = ?", Integer.class,
                "batch-retry")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM generation_test_case_row WHERE batch_id = ?", Integer.class,
                "batch-accepted")).isEqualTo(1);
    }

    /** [Req-ID]: REQ-CAG-007 */
    @Test
    void repeatedRetriesNeverCreateMoreThanTheThreeAllowedAttempts() {
        String taskId = taskId();
        repository.createTask(taskId, request());
        repository.createBatch("batch-retry-limit", taskId, "feature-retry", 1);
        repository.createAttempt("attempt-retry-one", "batch-retry-limit");
        repository.startBatch("batch-retry-limit", "attempt-retry-one");
        repository.failBatch("batch-retry-limit", "attempt-retry-one", "first failure", true);

        assertThat(repository.retryFailedBatches(taskId)).isEqualTo(1);
        assertThat(repository.retryFailedBatches(taskId)).isZero();
        String attemptTwo = jdbcTemplate.queryForObject(
                "SELECT id FROM generation_attempt WHERE batch_id = ? AND attempt_number = 2", String.class, "batch-retry-limit");
        repository.startBatch("batch-retry-limit", attemptTwo);
        repository.failBatch("batch-retry-limit", attemptTwo, "second failure", true);

        assertThat(repository.retryFailedBatches(taskId)).isEqualTo(1);
        String attemptThree = jdbcTemplate.queryForObject(
                "SELECT id FROM generation_attempt WHERE batch_id = ? AND attempt_number = 3", String.class, "batch-retry-limit");
        repository.startBatch("batch-retry-limit", attemptThree);
        repository.failBatch("batch-retry-limit", attemptThree, "third failure", true);

        assertThat(repository.retryFailedBatches(taskId)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM generation_attempt WHERE batch_id = ?", Integer.class,
                "batch-retry-limit")).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM generation_batch WHERE id = ?", String.class,
                "batch-retry-limit")).isEqualTo("FAILED");
    }

    /** [Req-ID]: REQ-CAG-007 */
    @Test
    void concurrentRetriesCreateOnlyOneReplacementAttemptAndLeaveAcceptedWorkUntouched() throws Exception {
        String taskId = taskId();
        repository.createTask(taskId, request());
        accept(taskId, "batch-concurrent-accepted", "attempt-concurrent-accepted", "feature-accepted", 1,
                markdownResult("concurrent-accepted"));
        repository.createBatch("batch-concurrent-retry", taskId, "feature-retry", 2);
        repository.createAttempt("attempt-concurrent-retry", "batch-concurrent-retry");
        repository.startBatch("batch-concurrent-retry", "attempt-concurrent-retry");
        repository.failBatch("batch-concurrent-retry", "attempt-concurrent-retry", "temporary failure", true);
        transitionToValidating(taskId);
        repository.completeMarkdownTask(taskId, GenerationTaskStatus.PARTIAL,
                new WorkbookArtifact("artifact-concurrent-retry", "sha256-concurrent-retry",
                        Path.of("artifacts", "concurrent-retry.xlsx")));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> retry = () -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent retry did not start");
                return repository.retryFailedBatches(taskId);
            };
            Future<Integer> first = executor.submit(retry);
            Future<Integer> second = executor.submit(retry);

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }

        assertThat(repository.taskStatus(taskId)).isEqualTo(GenerationTaskStatus.QUEUED);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM generation_batch WHERE id = ?", String.class,
                "batch-concurrent-retry")).isEqualTo("QUEUED");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM generation_attempt WHERE batch_id = ?", Integer.class,
                "batch-concurrent-retry")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM generation_batch WHERE id = ?", String.class,
                "batch-concurrent-accepted")).isEqualTo("ACCEPTED");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM generation_attempt WHERE batch_id = ?", Integer.class,
                "batch-concurrent-accepted")).isEqualTo(1);
    }

    /** [Req-ID]: REQ-CAG-007 */
    @Test
    void returnsOnlyTheAdjacentFailedReasonForSecondAndThirdAttempts() {
        String taskId = taskId();
        repository.createTask(taskId, allRequest());
        repository.createBatch("batch-retry-reason", taskId, "feature-retry", 1);
        repository.createAttempt("attempt-reason-one", "batch-retry-reason");
        repository.startBatch("batch-retry-reason", "attempt-reason-one");
        repository.failBatch("batch-retry-reason", "attempt-reason-one", "first failure", true);

        assertThat(repository.previousFailureReason("batch-retry-reason", "attempt-reason-one")).isEmpty();
        assertThat(repository.retryFailedBatches(taskId)).isEqualTo(1);
        String attemptTwo = jdbcTemplate.queryForObject(
                "SELECT id FROM generation_attempt WHERE batch_id = ? AND attempt_number = 2", String.class, "batch-retry-reason");
        assertThat(repository.previousFailureReason("batch-retry-reason", attemptTwo)).contains("first failure");
        repository.startBatch("batch-retry-reason", attemptTwo);
        repository.failBatch("batch-retry-reason", attemptTwo, "second failure", true);

        assertThat(repository.retryFailedBatches(taskId)).isEqualTo(1);
        String attemptThree = jdbcTemplate.queryForObject(
                "SELECT id FROM generation_attempt WHERE batch_id = ? AND attempt_number = 3", String.class, "batch-retry-reason");
        assertThat(repository.previousFailureReason("batch-retry-reason", attemptThree)).contains("second failure");
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

    /**
     * A valid ALL-mode batch carries the current frozen feature's positive and negative rows only.
     * The repository seam deliberately proves persistence/replace semantics, not the upstream 4.3 content validator.
     *
     * [Req-ID]: REQ-CAG-001, REQ-CAG-002
     */
    private static MarkdownGenerationResult twoCaseMarkdownResult(String suffix) {
        return twoCaseMarkdownResultWithEvidence(suffix, "requirement-" + suffix);
    }

    private static MarkdownGenerationResult twoCaseMarkdownResultWithEvidence(String suffix, String positiveRequirement) {
        return new MarkdownGenerationResult(
                "raw markdown " + suffix,
                List.of(new MarkdownAuditRow(1, "feature-" + suffix, "AMBIGUOUS", "evidence-" + suffix)),
                List.of(
                        new MarkdownTestCaseRow(
                                "case-" + suffix + "_正向", "feature-" + suffix, "precondition-" + suffix,
                                "1. normal step", "1. normal result", positiveRequirement),
                        new MarkdownTestCaseRow(
                                "case-" + suffix + "_反向", "feature-" + suffix, "precondition-" + suffix,
                                "1. invalid step", "1. error result", "依据通用经验，待确认")));
    }

    private void persistConclusion(
            String taskId, String id, int sequence, String type, String explanation, String evidenceText) {
        jdbcTemplate.update("""
                        INSERT INTO feature_review_conclusion
                            (id, task_id, conclusion_sequence, conclusion_type, explanation, evidence_text)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, id, taskId, sequence, type, explanation, evidenceText);
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

    private static CreateGenerationTaskRequest allRequest() {
        return new CreateGenerationTaskRequest(GenerationTaskMode.ALL, "all", List.of(), java.util.Map.of(),
                FewShotPolicy.AUTO, "markdown-1.0", "1.0", "markdown-agent",
                new RequirementScope("requirement-kb", "system-1", "version-1", "admission_material", null,
                        List.of(new RequirementDocumentCoordinate("document-1"))),
                new ExampleScope("example-kb", List.of("example-1")), List.of("function_list"),
                "markdown persistence retry test");
    }

    private void prepareAuditedAllTask(String taskId, List<FrozenFeatureTarget> targets) {
        repository.createTask(taskId, allRequest());
        MaterialInventoryUnit unit = new MaterialInventoryUnit("document-1", "FUNCTION_LIST", "unit-1", 0, 1,
                "功能材料", 0, 4);
        repository.replaceMaterialInventory(taskId, List.of(new MaterialInventoryDocument("document-1", "knowledge-1",
                "FUNCTION_LIST", 1, true, List.of(unit))), false);
        List<FeatureSourceCandidate> candidates = targets.stream().map(target -> new FeatureSourceCandidate(
                "candidate-" + target.stableFeatureId(), FeatureCandidateKind.FUNCTION_LIST, "document-1", "unit-1",
                1, target.stableSequence(), target.featureName(), "功能", "document-1/unit-1", 1,
                target.stableSequence())).toList();
        repository.persistScanAndCompleteAuditWork(repository.claimNextAuditWork(taskId, "test-worker", Duration.ofMinutes(1))
                .orElseThrow(), candidates, List.of(), true);
        repository.persistFeatureReviewConclusions(taskId, targets.stream().map(target -> new FeatureReviewConclusion(
                "conclusion-" + target.stableFeatureId(), target.stableSequence(), target.source().conclusionType(),
                target.featureName(), "document-1/unit-1", List.of("candidate-" + target.stableFeatureId()))).toList());
        repository.persistFrozenFeatureTargets(taskId, targets);
    }

    private static FrozenFeatureTarget target(String featureId, int sequence, boolean generationEligible) {
        FeatureReviewConclusionType conclusionType = generationEligible
                ? FeatureReviewConclusionType.MATCHED : FeatureReviewConclusionType.INSUFFICIENT_EVIDENCE;
        return new FrozenFeatureTarget(featureId, sequence, featureId, generationEligible,
                new FrozenFeatureSource("conclusion-" + featureId, conclusionType,
                        List.of("candidate-" + featureId), featureId));
    }

    private void transitionToValidating(String taskId) {
        repository.transitionTask(taskId, GenerationTaskStatus.AUDITING);
        repository.transitionTask(taskId, GenerationTaskStatus.GENERATING);
        repository.transitionTask(taskId, GenerationTaskStatus.VALIDATING);
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
