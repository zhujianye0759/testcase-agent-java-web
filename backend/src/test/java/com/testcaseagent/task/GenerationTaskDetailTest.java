package com.testcaseagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testcaseagent.featureaudit.AuditWorkClaim;
import com.testcaseagent.featureaudit.FeatureCandidateKind;
import com.testcaseagent.featureaudit.FeatureReviewConclusion;
import com.testcaseagent.featureaudit.FeatureReviewConclusionType;
import com.testcaseagent.featureaudit.FeatureSourceCandidate;
import com.testcaseagent.featureaudit.FrozenFeatureSource;
import com.testcaseagent.featureaudit.FrozenFeatureTarget;
import com.testcaseagent.featureaudit.MaterialInventoryDocument;
import com.testcaseagent.featureaudit.MaterialInventoryUnit;
import com.testcaseagent.fewshot.ExampleScope;
import com.testcaseagent.scope.RequirementDocumentCoordinate;
import com.testcaseagent.scope.RequirementScope;
import com.testcaseagent.testcase.FewShotPolicy;
import com.testcaseagent.testcase.GenerationTaskMode;
import com.testcaseagent.web.TestCaseAgentApplication;
import com.testcaseagent.export.WorkbookArtifact;
import java.nio.file.Path;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Locks the browser-safe task-detail progress contract to retained task-owned counts only.
 *
 * [Req-ID]: REQ-CWR-001
 */
@Testcontainers
@SpringBootTest(classes = TestCaseAgentApplication.class, properties = "app.knowledge-agent.enabled=false")
@Import(GenerationTaskDetailTest.RepositoryDependencies.class)
class GenerationTaskDetailTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("generation_task_detail_test")
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

    @Autowired
    DataSource dataSource;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM v2_work_publication");
        jdbcTemplate.update("DELETE FROM v2_generation_outcome");
        jdbcTemplate.update("DELETE FROM v2_testability_feedback_quote");
        jdbcTemplate.update("DELETE FROM v2_testability_feedback");
        jdbcTemplate.update("DELETE FROM v2_requirement_fact_quote");
        jdbcTemplate.update("DELETE FROM v2_requirement_fact");
        jdbcTemplate.update("DELETE FROM v2_approved_test_point");
        jdbcTemplate.update("DELETE FROM v2_approved_function");
        jdbcTemplate.update("DELETE FROM structured_reference_binding");
        jdbcTemplate.update("DELETE FROM structured_function_outcome_candidate");
        jdbcTemplate.update("DELETE FROM structured_function_candidate");
        jdbcTemplate.update("DELETE FROM structured_function_source_outcome");
        jdbcTemplate.update("DELETE FROM structured_test_case_step");
        jdbcTemplate.update("DELETE FROM structured_test_case");
        jdbcTemplate.update("DELETE FROM structured_test_point");
        jdbcTemplate.update("DELETE FROM structured_function_list_item");
        jdbcTemplate.update("DELETE FROM structured_feature_reconciliation");
        jdbcTemplate.update("DELETE FROM structured_review_finding");
        jdbcTemplate.update("DELETE FROM structured_requirement_fact");
        jdbcTemplate.update("DELETE FROM structured_generation_attempt");
        jdbcTemplate.update("DELETE FROM structured_generation_work_item");
        jdbcTemplate.update("DELETE FROM feature_review_conclusion_candidate");
        jdbcTemplate.update("DELETE FROM feature_review_conclusion");
        jdbcTemplate.update("DELETE FROM frozen_feature_target");
        jdbcTemplate.update("DELETE FROM feature_source_candidate");
        jdbcTemplate.update("DELETE FROM material_audit_duplicate_occurrence");
        jdbcTemplate.update("DELETE FROM material_audit_scan_outcome");
        jdbcTemplate.update("DELETE FROM material_audit_attempt");
        jdbcTemplate.update("DELETE FROM material_audit_work");
        jdbcTemplate.update("DELETE FROM material_inventory_unit");
        jdbcTemplate.update("DELETE FROM material_inventory_document");
        jdbcTemplate.update("DELETE FROM generation_audit_row");
        jdbcTemplate.update("DELETE FROM generation_test_case_row");
        jdbcTemplate.update("DELETE FROM generation_attempt");
        jdbcTemplate.update("DELETE FROM generation_batch");
        jdbcTemplate.update("DELETE FROM generation_task");
    }

    /** [Req-ID]: REQ-TGV2-007, REQ-TGV2-009, REQ-TGV2-010 */
    @Test
    void projectsV2FeedbackThreeOutcomesAndFormalOnlyWorkbookWithoutLegacyAuditInternals() throws Exception {
        String taskId = createV2Task();
        jdbcTemplate.update("""
                UPDATE generation_task
                SET status='PARTIAL', structured_processing_status='COMPLETED', structured_coverage_status='PARTIAL',
                    validation_error_code='TESTCASE_OUTCOME_INCONSISTENT',
                    validation_error_path='$.testcases[0]', validation_error_message='内部诊断'
                WHERE id=?
                """, taskId);
        String factWork = completedWork(taskId, "requirement-fact-extraction",
                "REQUIREMENT_FACT_EXTRACTION_V2", "需求材料");
        jdbcTemplate.update("""
                INSERT INTO v2_requirement_fact
                (task_id, fact_key, first_work_item_id, function_key, fact_type, statement_text)
                VALUES (?, 'fact-v2', ?, 'function-v2', 'business_rule', '订单可以提交')
                """, taskId, factWork);
        jdbcTemplate.update("""
                INSERT INTO v2_requirement_fact_quote
                (task_id, fact_key, evidence_key, quote_sha256, quote_text)
                VALUES (?, 'fact-v2', 'unit-v2', ?, '订单可以提交')
                """, taskId, "1".repeat(64));
        jdbcTemplate.update("""
                INSERT INTO v2_testability_feedback
                (task_id, feedback_key, work_item_id, function_key, window_key, observation_type,
                 description_text, affected_fact_types_json)
                VALUES (?, 'feedback-v2', ?, 'function-v2', 'window-v2', 'unquantified',
                        '响应时间没有量化标准', JSON_ARRAY('business_rule'))
                """, taskId, factWork);

        String generatedWork = insertV2Outcome(taskId, "point-generated", "generated", true, List.of());
        insertV2Case(taskId, generatedWork, "case-formal", "FORMAL", List.of());
        insertV2Case(taskId, generatedWork, "case-generated-pending", "PENDING_CONFIRMATION",
                List.of("缺少状态规则"));
        String pendingWork = insertV2Outcome(taskId, "point-pending", "pending_only", false, List.of());
        insertV2Case(taskId, pendingWork, "case-pending", "PENDING_CONFIRMATION", List.of("缺少角色权限"));
        insertV2Outcome(taskId, "point-unable", "unable_to_generate", false, List.of("缺少可执行输入"));
        seedLegacyAuditProjectionForV2Task(taskId);

        JsonNode response = objectMapper.readTree(objectMapper.writeValueAsString(
                GenerationTaskDetailResponse.from(repository.findDetail(taskId).orElseThrow())));
        JsonNode structured = response.path("structuredResult");
        assertThat(structured.path("workflowVersion").asText()).isEqualTo("2.0");
        assertThat(response.path("businessProgress").path("expectedTestCaseTotal").isNull()).isTrue();
        assertThat(structured.path("phaseProgress").path("factExtraction").path("total").asInt()).isEqualTo(1);
        assertThat(structured.path("phaseProgress").path("requirementReview").path("total").asInt()).isZero();
        assertThat(structured.path("phaseProgress").path("featureReconciliation").path("total").asInt()).isZero();
        assertThat(structured.path("phaseProgress").path("testcaseDesign").path("total").asInt()).isEqualTo(3);
        assertThat(structured.path("pendingCandidateCaseCount").asInt()).isEqualTo(2);
        assertThat(structured.has("validationFailure")).isFalse();
        assertThat(structured.path("testabilityFeedback")).isEmpty();
        assertThat(structured.path("testPoints")).isEmpty();
        assertThat(structured.path("v2Collections").path("testabilityFeedback").path("items"))
                .singleElement().satisfies(feedback -> {
            assertThat(feedback.path("observationType").asText()).isEqualTo("未量化");
            assertThat(feedback.path("description").asText()).isEqualTo("响应时间没有量化标准");
        });
        var outcomes = new java.util.ArrayList<String>();
        for (int page = 0; page < 3; page++) {
            StructuredGenerationTaskDetail paged = repository.findDetail(
                    taskId, new StructuredDetailQuery(0, page, 20)).orElseThrow().structuredResult();
            paged.v2Collections().testPoints().items().stream()
                    .map(StructuredGenerationTaskDetail.TestPoint::generationOutcome)
                    .forEach(outcomes::add);
        }
        assertThat(outcomes).containsExactlyInAnyOrder("已生成", "仅待确认", "无法生成");
        assertThat(response.toString()).doesNotContain("fact-v2", "feedback-v2", "unit-v2", "window-v2",
                "unquantified", "unable_to_generate", "business_rule", factWork, generatedWork, pendingWork);

        var workbook = repository.structuredWorkbookRequest(taskId);
        assertThat(workbook.reviewRows()).hasSize(4);
        assertThat(workbook.reviewRows()).anySatisfy(row -> {
            assertThat(row.source()).isEqualTo(com.testcaseagent.export.StructuredReviewRow.Source.TESTABILITY_FEEDBACK);
            assertThat(row.summary()).isEqualTo("响应时间没有量化标准");
        });
        assertThat(workbook.reviewRows()).anySatisfy(row -> {
            assertThat(row.source()).isEqualTo(com.testcaseagent.export.StructuredReviewRow.Source.GENERATION_OUTCOME);
            assertThat(row.classification()).isEqualTo("含待确认用例");
            assertThat(row.summary()).contains("缺少状态规则");
        });
        assertThat(workbook.reviewRows()).anySatisfy(row -> {
            assertThat(row.source()).isEqualTo(com.testcaseagent.export.StructuredReviewRow.Source.GENERATION_OUTCOME);
            assertThat(row.classification()).isEqualTo("仅生成待确认用例");
            assertThat(row.summary()).contains("缺少角色权限");
        });
        assertThat(workbook.reviewRows()).anySatisfy(row -> {
            assertThat(row.source()).isEqualTo(com.testcaseagent.export.StructuredReviewRow.Source.GENERATION_OUTCOME);
            assertThat(row.classification()).isEqualTo("无法生成用例");
            assertThat(row.summary()).contains("缺少可执行输入");
        });
        assertThat(workbook.testCaseRows()).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo(com.testcaseagent.export.StructuredTestCaseRow.Status.FORMAL);
            assertThat(row.requirementSummaries()).containsExactly("订单可以提交");
            assertThat(row.inputs()).singleElement().satisfies(input ->
                    assertThat(input.nature()).isEqualTo(com.testcaseagent.export.StructuredTestCaseRow.InputNature.VALID));
        });

        String outcomeOnlyTask = createV2Task();
        String outcomeOnlyPending = insertV2Outcome(
                outcomeOnlyTask, "point-only-pending", "pending_only", false, List.of());
        insertV2Case(outcomeOnlyTask, outcomeOnlyPending, "case-only-pending", "PENDING_CONFIRMATION",
                List.of("第二项信息", "第一项信息"));
        insertV2Case(outcomeOnlyTask, outcomeOnlyPending, "case-only-pending-2", "PENDING_CONFIRMATION",
                List.of("第三项信息", "第一项信息"));
        insertV2Outcome(outcomeOnlyTask, "point-only-unable", "unable_to_generate", false, List.of("缺少可执行输入"));
        var outcomeOnlyWorkbook = repository.structuredWorkbookRows(outcomeOnlyTask);
        var outcomeOnlyReviews = new java.util.ArrayList<com.testcaseagent.export.StructuredReviewRow>();
        outcomeOnlyWorkbook.forEachReview(outcomeOnlyReviews::add);
        assertThat(outcomeOnlyReviews).extracting(com.testcaseagent.export.StructuredReviewRow::classification)
                .containsExactly("仅生成待确认用例", "无法生成用例");
        assertThat(outcomeOnlyReviews).extracting(com.testcaseagent.export.StructuredReviewRow::summary)
                .containsExactly(java.util.stream.Stream.of("第一项信息", "第二项信息", "第三项信息")
                        .sorted().collect(java.util.stream.Collectors.joining("；")), "缺少可执行输入");
        assertThat(outcomeOnlyWorkbook.testCaseRowCount()).isZero();
    }

    /** [Req-ID]: REQ-TGV2-009 */
    @Test
    void pagesV2FeedbackAndTestPointsWithoutChangingTheV1DetailShape() throws Exception {
        String taskId = createV2Task();
        jdbcTemplate.update("""
                UPDATE generation_task
                SET structured_processing_status='COMPLETED', structured_coverage_status='COMPLETE'
                WHERE id=?
                """, taskId);
        String factWork = completedWork(taskId, "requirement-fact-extraction",
                "REQUIREMENT_FACT_EXTRACTION_V2", "需求材料");
        for (int index = 0; index < 12; index++) {
            jdbcTemplate.update("""
                    INSERT INTO v2_testability_feedback
                    (task_id, feedback_key, work_item_id, function_key, window_key, observation_type,
                     description_text, affected_fact_types_json)
                    VALUES (?, ?, ?, 'function-v2', ?, 'unquantified', ?, JSON_ARRAY('business_rule'))
                    """, taskId, "feedback-%02d".formatted(index), factWork,
                    "window-%02d".formatted(index), "反馈%02d".formatted(index));
        }
        for (int index = 0; index < 25; index++) {
            String pointKey = "point-%02d".formatted(index);
            String workId = insertV2Outcome(taskId, pointKey, "generated", true, List.of());
            jdbcTemplate.update("UPDATE structured_test_point SET function_name = ? WHERE work_item_id = ?",
                    "功能%02d".formatted(index), workId);
            insertV2Case(taskId, workId, "case-%02d".formatted(index), "FORMAL", List.of());
        }

        StructuredGenerationTaskDetail result = repository.findDetail(
                taskId, new StructuredDetailQuery(1, 2, 0, 5)).orElseThrow().structuredResult();

        assertThat(result.testabilityFeedback()).isEmpty();
        assertThat(result.testPoints()).isEmpty();
        assertThat(result.v2Collections()).isNotNull();
        assertThat(result.v2Collections().testabilityFeedback()).satisfies(page -> {
            assertThat(page.page()).isEqualTo(1);
            assertThat(page.size()).isEqualTo(5);
            assertThat(page.totalItems()).isEqualTo(12);
            assertThat(page.hasNext()).isTrue();
            assertThat(page.items()).extracting(StructuredGenerationTaskDetail.TestabilityFeedback::description)
                    .containsExactly("反馈05", "反馈06", "反馈07", "反馈08", "反馈09");
        });
        assertThat(result.v2Collections().testPoints()).satisfies(page -> {
            assertThat(page.page()).isEqualTo(2);
            // One point can carry an almost 4 MiB accepted result, so the public detail projection keeps one
            // point per page while feedback retains the caller-requested lightweight page size.
            assertThat(page.size()).isEqualTo(1);
            assertThat(page.totalItems()).isEqualTo(25);
            assertThat(page.hasNext()).isTrue();
            assertThat(page.items()).extracting(StructuredGenerationTaskDetail.TestPoint::functionName)
                    .containsExactly("功能02");
            assertThat(page.items()).allSatisfy(point -> assertThat(point.testcases()).isEmpty());
        });
        assertThat(result.v2Collections().testcases()).satisfies(page -> {
            assertThat(page.page()).isZero();
            assertThat(page.size()).isEqualTo(1);
            assertThat(page.totalItems()).isOne();
            assertThat(page.items()).hasSize(1);
        });

        var workbookRows = repository.structuredWorkbookRows(taskId);
        var exportedReviews = new java.util.ArrayList<com.testcaseagent.export.StructuredReviewRow>();
        var exportedCases = new java.util.ArrayList<com.testcaseagent.export.StructuredTestCaseRow>();
        workbookRows.forEachReview(exportedReviews::add);
        workbookRows.forEachTestCase(exportedCases::add);
        assertThat(workbookRows.reviewRowCount()).isEqualTo(12);
        assertThat(workbookRows.testCaseRowCount()).isEqualTo(25);
        assertThat(exportedReviews).hasSize(12).extracting(com.testcaseagent.export.StructuredReviewRow::sequence)
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 12).boxed().toList());
        assertThat(exportedCases).hasSize(25)
                .extracting(com.testcaseagent.export.StructuredTestCaseRow::functionName)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 25)
                        .mapToObj(index -> "功能%02d".formatted(index)).toList());

        AtomicInteger preparedStatements = new AtomicInteger();
        var fetchSizes = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
        DataSource countingDataSource = countingDataSource(dataSource, preparedStatements, fetchSizes);
        var countedRepository = new GenerationTaskRepository(new JdbcTemplate(countingDataSource), objectMapper,
                new DataSourceTransactionManager(countingDataSource));
        var countedRows = countedRepository.structuredWorkbookRows(taskId);
        preparedStatements.set(0);
        fetchSizes.clear();
        var countedReviews = new java.util.ArrayList<com.testcaseagent.export.StructuredReviewRow>();
        var countedCases = new java.util.ArrayList<com.testcaseagent.export.StructuredTestCaseRow>();
        countedRows.forEachReview(countedReviews::add);
        countedRows.forEachTestCase(countedCases::add);
        assertThat(countedReviews).hasSize(12);
        assertThat(countedCases).hasSize(25);
        // Three streaming queries plus three final count-drift checks; row count must not create N+1 queries.
        assertThat(preparedStatements).hasValueLessThanOrEqualTo(6);
        assertThat(fetchSizes).containsExactly(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);

        String legacyTaskId = createAllTask();
        jdbcTemplate.update("""
                UPDATE generation_task
                SET structured_processing_status='PENDING', structured_coverage_status='PENDING'
                WHERE id=?
                """, legacyTaskId);
        JsonNode legacy = objectMapper.readTree(objectMapper.writeValueAsString(
                GenerationTaskDetailResponse.from(repository.findDetail(legacyTaskId).orElseThrow())));
        JsonNode legacyStructured = legacy.path("structuredResult");
        assertThat(legacyStructured.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "processingStatus", "coverageStatus", "pendingCandidateCaseCount", "phaseProgress",
                "reviewFindings", "reconciliations", "testPoints");
        assertThat(legacyStructured.path("processingStatus").asText()).isEqualTo("PENDING");
        assertThat(legacyStructured.path("coverageStatus").asText()).isEqualTo("PENDING");
        assertThat(legacyStructured.path("phaseProgress").fieldNames()).toIterable().containsExactlyInAnyOrder(
                "materialTraversal", "requirementReview", "featureReconciliation", "testcaseDesign");
    }

    /** [Req-ID]: REQ-TGV2-009 */
    @Test
    void hidesMutableV2CollectionsUntilStructuredProcessingIsTerminal() throws Exception {
        String taskId = createV2Task();
        String runningWork = insertV2Outcome(taskId, "point-running", "generated", true, List.of());
        insertV2Case(taskId, runningWork, "case-running-pending", "PENDING_CONFIRMATION", List.of("缺少状态规则"));
        jdbcTemplate.update("""
                UPDATE generation_task
                SET structured_processing_status='RUNNING', structured_coverage_status='PENDING'
                WHERE id=?
                """, taskId);

        StructuredGenerationTaskDetail pending = repository.findDetail(taskId).orElseThrow().structuredResult();

        assertThat(pending.v2Collections().testPoints().items()).isEmpty();
        assertThat(pending.v2Collections().testPoints().totalItems()).isZero();
        assertThat(pending.v2Collections().testcases().items()).isEmpty();
        assertThat(pending.pendingCandidateCaseCount()).isZero();
        jdbcTemplate.update("""
                UPDATE generation_task
                SET structured_processing_status='COMPLETED', structured_coverage_status='COMPLETE'
                WHERE id=?
                """, taskId);
        StructuredGenerationTaskDetail completed = repository.findDetail(taskId).orElseThrow().structuredResult();
        assertThat(completed.v2Collections().testPoints().totalItems()).isOne();
        assertThat(completed.pendingCandidateCaseCount()).isOne();
    }

    /** [Req-ID]: REQ-TGV2-009 */
    @Test
    void pagesCasesInsideTheSelectedV2TestPointWithoutLoadingTheWholeAcceptedResult() throws Exception {
        String taskId = createV2Task();
        jdbcTemplate.update("""
                UPDATE generation_task
                SET structured_processing_status='COMPLETED', structured_coverage_status='COMPLETE'
                WHERE id=?
                """, taskId);
        String workId = insertV2Outcome(taskId, "point-many-cases", "generated", true, List.of());
        insertV2Case(taskId, workId, "case-01", "FORMAL", List.of());
        insertV2Case(taskId, workId, "case-02", "FORMAL", List.of());
        insertV2Case(taskId, workId, "case-03", "FORMAL", List.of());

        StructuredGenerationTaskDetail second = repository.findDetail(
                taskId, new StructuredDetailQuery(0, 0, 1, 10)).orElseThrow().structuredResult();

        assertThat(second.v2Collections().testPoints().items()).singleElement()
                .satisfies(point -> assertThat(point.testcases()).isEmpty());
        assertThat(second.v2Collections().testcases()).satisfies(page -> {
            assertThat(page.page()).isOne();
            assertThat(page.size()).isOne();
            assertThat(page.totalItems()).isEqualTo(3);
            assertThat(page.hasNext()).isTrue();
            assertThat(page.items()).singleElement()
                    .satisfies(testcase -> assertThat(testcase.title()).isEqualTo("验证提交订单"));
        });
    }

    /** [Req-ID]: REQ-TGV2-009 */
    @Test
    void rejectsUnsafeStructuredDetailPageSizes() {
        assertThatThrownBy(() -> new StructuredDetailQuery(0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StructuredDetailQuery(0, 0, 21))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StructuredDetailQuery(0, 0, -1, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keepsFrozenAndExpectedCaseCountsUnknownWhileAuditScanningIsStillInProgress() {
        String taskId = createAllTask();
        repository.replaceMaterialInventory(taskId, documents(), false);
        repository.transitionTask(taskId, GenerationTaskStatus.AUDITING);
        AuditWorkClaim completedFunctionListScan = repository.claimNextAuditWork(taskId, "worker", Duration.ofMinutes(1))
                .orElseThrow();
        repository.persistScanAndCompleteAuditWork(completedFunctionListScan, List.of(), List.of(), true);

        GenerationTaskBusinessProgress progress = repository.findDetail(taskId).orElseThrow().businessProgress();

        assertThat(progress.currentBusinessStage()).isEqualTo("需求扫描（第一遍）");
        assertThat(progress.materialDocumentTotal()).isEqualTo(2);
        assertThat(progress.completeMaterialDocumentCount()).isEqualTo(2);
        assertThat(progress.materialUnitTotal()).isEqualTo(2);
        assertThat(progress.processedMaterialUnitCount()).isEqualTo(1);
        assertThat(progress.totalAuditWork()).isEqualTo(3);
        assertThat(progress.completedAuditWork()).isEqualTo(1);
        assertThat(progress.failedAuditWork()).isZero();
        assertThat(progress.featureCandidateTotal()).isZero();
        assertThat(progress.frozenComplete()).isFalse();
        assertThat(progress.frozenFeatureTotal()).isNull();
        assertThat(progress.expectedTestCaseTotal()).isNull();
        assertThat(progress.acceptedTestCaseCount()).isZero();
        assertThat(progress.coverageStatus()).isEqualTo("进行中");
    }

    /** [Req-ID]: REQ-FSC-007 */
    @Test
    void exposesOnlyThePersistedSafeStructuredValidationDiagnostic() throws Exception {
        String taskId = createV2Task();
        jdbcTemplate.update("""
                UPDATE generation_task
                SET status = 'FAILED', structured_processing_status = 'FAILED', structured_coverage_status = 'PENDING',
                    validation_error_code = 'REVIEW_FACT_DIRECT_EVIDENCE_UNSUPPORTED',
                    validation_error_path = '$.requirement_facts[0].business_rules[0]',
                    validation_error_message = '正式需求事实未在引用材料单元中直接出现'
                WHERE id = ?
                """, taskId);

        JsonNode response = objectMapper.readTree(objectMapper.writeValueAsString(
                GenerationTaskDetailResponse.from(repository.findDetail(taskId).orElseThrow())));

        JsonNode failure = response.path("structuredResult").path("validationFailure");
        assertThat(failure.path("code").asText()).isEqualTo("REVIEW_FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        assertThat(failure.path("path").asText()).isEqualTo("$.requirement_facts[0].business_rules[0]");
        assertThat(failure.path("message").asText()).isEqualTo("正式需求事实未在引用材料单元中直接出现");
        assertThat(response.toString()).doesNotContain("password=do-not-persist", "model response", "material content");
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void hidesInternalDirectEvidenceReasonsFromTheTaskDetailApi() throws Exception {
        String taskId = createV2Task();
        jdbcTemplate.update("""
                UPDATE generation_task
                SET status = 'FAILED', structured_processing_status = 'FAILED', structured_coverage_status = 'PENDING',
                    validation_error_code = 'FACT_DIRECT_EVIDENCE_UNSUPPORTED',
                    validation_error_path = '$.requirement_facts[0].statement',
                    validation_error_message = '需求事实正文未由任一引用材料单元直接支撑|direct_evidence_reasons=LITERAL_UNSUPPORTED,TOKEN_ORDER_OR_ADDITION'
                WHERE id = ?
                """, taskId);

        JsonNode response = objectMapper.readTree(objectMapper.writeValueAsString(
                GenerationTaskDetailResponse.from(repository.findDetail(taskId).orElseThrow())));

        JsonNode failure = response.path("structuredResult").path("validationFailure");
        assertThat(failure.path("code").asText()).isEqualTo("FACT_DIRECT_EVIDENCE_UNSUPPORTED");
        assertThat(failure.path("message").asText()).isEqualTo("需求事实正文未由任一引用材料单元直接支撑");
        assertThat(response.toString()).doesNotContain(
                "direct_evidence_reasons", "LITERAL_UNSUPPORTED", "TOKEN_ORDER_OR_ADDITION");
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void rejectsCorruptedStoredDiagnosticsWithoutRetainingTheirValueInTheExceptionChain() {
        String taskId = createV2Task();
        String sensitiveMarker = "private-diagnostic-marker";
        jdbcTemplate.update("""
                UPDATE generation_task
                SET status = 'FAILED', structured_processing_status = 'FAILED', structured_coverage_status = 'PENDING',
                    validation_error_code = ?, validation_error_path = '$.requirement_facts[0].statement',
                    validation_error_message = 'corrupted'
                WHERE id = ?
                """, sensitiveMarker, taskId);

        assertThatThrownBy(() -> repository.findDetail(taskId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Structured validation diagnostic is not recognized")
                .hasNoCause()
                .satisfies(rejected -> assertThat(rejected.toString()).doesNotContain(sensitiveMarker));
    }

    /** [Req-ID]: REQ-ESR-006 */
    @Test
    void exposesOnlyFixedCoordinatorCategoryAndStageWithoutAStackOrArbitraryExceptionText() throws Exception {
        String taskId = createV2Task();
        jdbcTemplate.update("""
                UPDATE generation_task
                SET status = 'FAILED', structured_processing_status = 'FAILED', structured_coverage_status = 'PENDING',
                    validation_error_code = 'STRUCTURED_COORDINATOR_STATE_FAILURE',
                    validation_error_path = '$.function_extraction_pre_split',
                    validation_error_message = '结构化任务在状态处理阶段失败'
                WHERE id = ?
                """, taskId);

        JsonNode response = objectMapper.readTree(objectMapper.writeValueAsString(
                GenerationTaskDetailResponse.from(repository.findDetail(taskId).orElseThrow())));

        JsonNode failure = response.path("structuredResult").path("validationFailure");
        assertThat(failure.path("code").asText()).isEqualTo("STRUCTURED_COORDINATOR_STATE_FAILURE");
        assertThat(failure.path("path").asText()).isEqualTo("$.function_extraction_pre_split");
        assertThat(failure.path("message").asText()).isEqualTo("结构化任务在状态处理阶段失败");
        assertThat(response.toString()).doesNotContain(
                "stackTrace", "exception", "Authorization", "material content", "requestBody", "responseBody");
    }

    /** [Req-ID]: REQ-ESR-004 */
    @Test
    void keepsLegacyRetryEligibilityServerSideWithoutChangingTheV1JsonShape() throws Exception {
        String taskId = createAllTask();
        repository.replaceMaterialInventory(taskId, documents(), false);
        jdbcTemplate.update("""
                UPDATE generation_task
                SET status = 'FAILED', structured_processing_status = 'FAILED', structured_coverage_status = 'PENDING'
                WHERE id = ?
                """, taskId);
        String workId = UUID.randomUUID().toString();
        String attemptId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO structured_generation_work_item
                (id, task_id, identity_key, skill_name, operation_name, status, ordinal_start, ordinal_end,
                 material_key, source_label, allowed_evidence_keys_json)
                VALUES (?, ?, ?, 'requirement-material-quality-review', 'REQUIREMENT_MATERIAL_REVIEW', 'FAILED',
                        65, 96, 'material', '材料', JSON_ARRAY('unit'))
                """, workId, taskId, "a".repeat(64));
        jdbcTemplate.update("""
                INSERT INTO structured_generation_attempt
                (id, work_item_id, attempt_number, status, failure_type, completed_at,
                 validation_error_code, validation_error_path, validation_error_message)
                VALUES (?, ?, 1, 'FAILED', 'business_validation_failed', CURRENT_TIMESTAMP(6),
                        'REVIEW_FACT_DIRECT_EVIDENCE_UNSUPPORTED', '$.requirement_facts[6].function',
                        '正式需求事实未在引用材料单元中直接出现')
                """, attemptId, workId);

        GenerationTaskDetail detail = repository.findDetail(taskId).orElseThrow();
        assertThat(detail.structuredResult().retryEligibility().canRetry()).isTrue();
        JsonNode response = objectMapper.readTree(objectMapper.writeValueAsString(
                GenerationTaskDetailResponse.from(detail)));

        JsonNode eligibility = response.path("structuredResult").path("retryEligibility");
        assertThat(eligibility.isMissingNode()).isTrue();
        assertThat(response.toString()).doesNotContain(
                workId, attemptId, "business_validation_failed", "REVIEW_FACT_DIRECT_EVIDENCE_UNSUPPORTED",
                "$.requirement_facts[6].function", "unit");
    }

    /** [Req-ID]: REQ-ESR-005 */
    @Test
    void keepsLegacySingleUnitCapacityRetryServerSideWithoutChangingTheV1JsonShape() throws Exception {
        String taskId = createAllTask();
        repository.replaceMaterialInventory(taskId, documents(), false);
        jdbcTemplate.update("""
                UPDATE generation_task
                SET status = 'FAILED', structured_processing_status = 'FAILED', structured_coverage_status = 'PENDING'
                WHERE id = ?
                """, taskId);
        String workId = UUID.randomUUID().toString();
        String attemptId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO structured_generation_work_item
                (id, task_id, identity_key, skill_name, operation_name, status, ordinal_start, ordinal_end,
                 material_key, source_label, allowed_evidence_keys_json)
                VALUES (?, ?, ?, 'requirement-material-quality-review', 'REQUIREMENT_MATERIAL_REVIEW', 'FAILED',
                        1, 1, 'opaque-material-key', '材料', JSON_ARRAY('requirement-unit'))
                """, workId, taskId, "b".repeat(64));
        jdbcTemplate.update("""
                INSERT INTO structured_generation_attempt
                (id, work_item_id, attempt_number, status, failure_type, completed_at)
                VALUES (?, ?, 1, 'FAILED', 'response_too_large', CURRENT_TIMESTAMP(6))
                """, attemptId, workId);

        GenerationTaskDetail detail = repository.findDetail(taskId).orElseThrow();
        assertThat(detail.structuredResult().retryEligibility().canRetry()).isTrue();
        JsonNode response = objectMapper.readTree(objectMapper.writeValueAsString(
                GenerationTaskDetailResponse.from(detail)));

        JsonNode eligibility = response.path("structuredResult").path("retryEligibility");
        assertThat(eligibility.isMissingNode()).isTrue();
        assertThat(response.toString()).doesNotContain(
                workId, attemptId, "response_too_large", "requirement-unit", "opaque-material-key");
    }

    @Test
    void returnsBrowserSafeFrozenProgressAndJsonContractWithoutInternalIdentifiers() throws Exception {
        String taskId = createAllTask();
        repository.replaceMaterialInventory(taskId, documents(), false);
        completeAllAuditWork(taskId);
        repository.persistFeatureReviewConclusions(taskId, conclusions());
        repository.persistFrozenFeatureTargets(taskId, frozenTargets());
        repository.transitionTask(taskId, GenerationTaskStatus.AUDITING);
        repository.transitionTask(taskId, GenerationTaskStatus.GENERATING);

        GenerationTaskDetail detail = repository.findDetail(taskId).orElseThrow();
        GenerationTaskBusinessProgress progress = detail.businessProgress();

        assertThat(progress.currentBusinessStage()).isEqualTo("测试用例生成");
        assertThat(progress.featureCandidateTotal()).isEqualTo(6);
        assertThat(progress.functionListMissingCount()).isEqualTo(1);
        assertThat(progress.requirementMissingCount()).isEqualTo(1);
        assertThat(progress.conflictCount()).isZero();
        assertThat(progress.splitCount()).isEqualTo(1);
        assertThat(progress.mergeCount()).isEqualTo(1);
        assertThat(progress.insufficientEvidenceCount()).isEqualTo(1);
        assertThat(progress.frozenComplete()).isTrue();
        assertThat(progress.frozenFeatureTotal()).isEqualTo(5);
        assertThat(progress.generationEligibleFrozenFeatureCount()).isEqualTo(4);
        assertThat(progress.generationIneligibleFrozenFeatureCount()).isEqualTo(1);
        assertThat(progress.expectedTestCaseTotal()).isEqualTo(8);
        assertThat(progress.acceptedTestCaseCount()).isZero();
        assertThat(progress.coverageStatus()).isEqualTo("进行中");

        JsonNode response = objectMapper.readTree(objectMapper.writeValueAsString(GenerationTaskDetailResponse.from(detail)));
        JsonNode jsonProgress = response.path("businessProgress");
        assertThat(jsonProgress.path("frozenComplete").asBoolean()).isTrue();
        assertThat(jsonProgress.path("expectedTestCaseTotal").asInt()).isEqualTo(8);
        assertThat(response.toString()).doesNotContain("knowledge-", "documentId", "unitId", "cursor", "prompt",
                "rawMarkdown", "candidate-");
        assertThat(response.has("structuredResult")).isFalse();
    }

    @Test
    void legacyDetailWithoutStructuredAxesDoesNotReadStructuredMaterialTotal() {
        AtomicBoolean materialTotalRead = new AtomicBoolean();

        assertThat(repository.structuredResult("legacy-task", null, null, () -> {
            materialTotalRead.set(true);
            throw new AssertionError("legacy detail must not require structured scope");
        })).isNull();
        assertThat(materialTotalRead).isFalse();
    }

    @Test
    void returnsOnlyReaderSafeValidatedStructuredProjectionWithoutInternalEnums() throws Exception {
        String taskId = createAllTask();
        repository.replaceMaterialInventory(taskId, documents(), false);
        jdbcTemplate.update("""
                UPDATE generation_task
                SET structured_processing_status = 'COMPLETED', structured_coverage_status = 'PARTIAL'
                WHERE id = ?
                """, taskId);

        String factWorkId = completedWork(taskId, "requirement-material-quality-review", "REQUIREMENT_MATERIAL_REVIEW", "需求来源");
        jdbcTemplate.update("""
                INSERT INTO structured_requirement_fact
                (work_item_id, task_id, fact_key, function_name, roles_json, trigger_conditions_json, inputs_json, business_rules_json,
                 outputs_json, permissions_json, state_changes_json, exception_handling_json, external_dependencies_json)
                VALUES (?, ?, 'fact-internal-key', '订单提交', CAST('[\"用户\"]' AS JSON), CAST('[]' AS JSON), CAST('[]' AS JSON),
                        CAST('[]' AS JSON), CAST('[]' AS JSON), CAST('[]' AS JSON), CAST('[]' AS JSON), CAST('[]' AS JSON), CAST('[]' AS JSON))
                """, factWorkId, taskId);

        String reviewWorkId = completedWork(taskId, "requirement-material-quality-review", "REQUIREMENT_MATERIAL_REVIEW",
                "需求来源 https://private.example/secret");
        jdbcTemplate.update("""
                INSERT INTO structured_review_finding
                (work_item_id, task_id, finding_key, root_cause_kind, issue_type, description, test_design_impact,
                 current_project_recommendation, design_center_guideline_recommendation, handling_level,
                 affected_unit_keys_json, affected_scope_summary, bad_source_evidence_key, bad_source_quote,
                 proposed_good_status, proposed_good_text)
                VALUES (?, ?, 'finding-internal-key', 'MISSING_EXCEPTION_HANDLING', '完整性',
                        'java.lang.IllegalStateException: secret\n    at com.example.Secret.run(Secret.java:1)',
                        '补充异常场景', '账号被禁用/锁定/未激活时补齐需求说明', '建立审查准则', 'BLOCKING',
                        CAST('[\"requirement-unit\"]' AS JSON), '账号异常状态范围', 'requirement-unit',
                        '材料未说明账号禁用后的处理', 'PENDING_CONFIRMATION', '待需求方确认：补充账号禁用后的处理规则')
                """, reviewWorkId, taskId);

        String functionListWorkId = completedWork(taskId, "feature-scope-reconciliation", "FEATURE_SCOPE_EXTRACT", "功能清单");
        jdbcTemplate.update("""
                INSERT INTO structured_function_list_item (work_item_id, task_id, item_key, path_text, description)
                VALUES (?, ?, 'function-list-internal-key', '订单/提交', '提交订单')
                """, functionListWorkId, taskId);
        jdbcTemplate.update("""
                INSERT INTO structured_function_source_outcome
                (work_item_id, task_id, unit_key, source_ordinal, kee_disposition, java_final_decision, reason_code)
                VALUES
                (?, ?, 'candidate-unit-accepted', 1, 'LINKED', 'ACCEPTED', 'candidate_linked'),
                (?, ?, 'candidate-unit-pending', 2, 'LINKED', 'PENDING_CONFIRMATION', 'candidate_linked'),
                (?, ?, 'candidate-unit-unresolved', 3, 'UNRESOLVED', 'PENDING_CONFIRMATION', 'model_omitted_unit')
                """, functionListWorkId, taskId, functionListWorkId, taskId, functionListWorkId, taskId);
        jdbcTemplate.update("""
                INSERT INTO structured_function_candidate
                (work_item_id, task_id, candidate_ref, path_text, description, target_quote, recommended_status,
                 java_final_decision, reason_code, missing_information_json, function_item_key)
                VALUES
                (?, ?, ?, '订单/提交', '提交订单', '提交订单', 'ACCEPTED', 'ACCEPTED', 'grounded_function',
                 CAST('[]' AS JSON), 'function-list-internal-key'),
                (?, ?, ?, '订单/撤销', '撤销订单', '撤销订单', 'PENDING_CONFIRMATION', 'PENDING_CONFIRMATION',
                 'insufficient_detail', CAST('["撤销条件待确认"]' AS JSON), NULL)
                """, functionListWorkId, taskId, "a".repeat(64), functionListWorkId, taskId, "b".repeat(64));
        jdbcTemplate.update("""
                INSERT INTO structured_function_outcome_candidate (work_item_id, unit_key, candidate_ref)
                VALUES (?, 'candidate-unit-accepted', ?), (?, 'candidate-unit-pending', ?)
                """, functionListWorkId, "a".repeat(64), functionListWorkId, "b".repeat(64));
        String uncommittedCandidateWorkId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO structured_generation_work_item
                (id, task_id, identity_key, skill_name, operation_name, status, source_label)
                VALUES (?, ?, ?, 'feature-scope-reconciliation', 'FEATURE_SCOPE_EXTRACT', 'RUNNING', '未验收窗口')
                """, uncommittedCandidateWorkId, taskId, "c".repeat(64));
        jdbcTemplate.update("""
                INSERT INTO structured_function_source_outcome
                (work_item_id, task_id, unit_key, source_ordinal, kee_disposition, java_final_decision, reason_code)
                VALUES (?, ?, 'uncommitted-unit', 1, 'UNRESOLVED', 'PENDING_CONFIRMATION', 'must_not_project')
                """, uncommittedCandidateWorkId, taskId);
        jdbcTemplate.update("""
                INSERT INTO structured_function_candidate
                (work_item_id, task_id, candidate_ref, path_text, description, target_quote, recommended_status,
                 java_final_decision, reason_code, missing_information_json)
                VALUES (?, ?, ?, '未验收/候选', '不得展示', '不得展示', 'PENDING_CONFIRMATION',
                        'PENDING_CONFIRMATION', 'must_not_project', CAST('["不得展示"]' AS JSON))
                """, uncommittedCandidateWorkId, taskId, "d".repeat(64));
        String reconciliationWorkId = completedWork(taskId, "feature-scope-reconciliation", "FEATURE_SCOPE_RECONCILIATION", "核对");
        jdbcTemplate.update("""
                INSERT INTO structured_feature_reconciliation
                (work_item_id, task_id, reconciliation_key, classification, scope_recommendation, confirmation_status)
                VALUES (?, ?, 'reconciliation-internal-key', 'EXACT_MATCH',
                        '合并 fli-bc5dafcd3684fbf0005736a8110f1ef6adc1af19c63a3e8728e992cb534d0b95 与 fact-1724e7041424efc97c0cc3dc53109f39 后保持范围',
                        'CONFIRMED')
                """, reconciliationWorkId, taskId);
        bind(reconciliationWorkId, "reconciliation-internal-key", "RECONCILIATION", "FUNCTION_LIST_ITEM", "function-list-internal-key");
        bind(reconciliationWorkId, "reconciliation-internal-key", "RECONCILIATION", "REQUIREMENT_FACT", "fact-internal-key");

        String testcaseWorkId = completedWork(taskId, "functional-testcase-design", "FUNCTIONAL_TESTCASE_DESIGN", "测试点");
        jdbcTemplate.update("""
                INSERT INTO structured_test_point
                (work_item_id, task_id, test_point_key, function_key, function_name, test_point_type, basis, description, missing_information_json,
                 formal_coverage_satisfied)
                VALUES (?, ?, 'test-point-internal-key', 'function-internal-key', '订单提交', 'NORMAL_BEHAVIOR', 'FORMAL_REQUIREMENT', '提交成功',
                        CAST('[\"无\"]' AS JSON), TRUE)
                """, testcaseWorkId, taskId);
        jdbcTemplate.update("""
                INSERT INTO structured_test_case
                (work_item_id, task_id, case_key, name_text, title, priority, preconditions_json,
                 hardware_configuration_json, software_configuration_json, test_configuration_json, parameter_configuration_json,
                 inputs_json, expected_results_json, evaluation_criteria, result_evaluation_criteria,
                 termination_conditions_json, result_collection, author_name, author_date, case_status, missing_information_json)
                VALUES (?, ?, 'case-internal-key', '订单提交正常场景', '正常提交订单', 'HIGH', CAST('[\"用户已登录\"]' AS JSON),
                        CAST('[\"办公电脑\"]' AS JSON), CAST('[\"浏览器\"]' AS JSON), CAST('[\"测试环境\"]' AS JSON),
                        CAST('[\"订单数据已准备\"]' AS JSON),
                        CAST('[{\"content\":\"订单\",\"nature\":\"VALID\",\"source\":\"MANUAL\",\"method\":\"EQUIVALENCE_PARTITIONING\",\"authenticity\":\"SIMULATED\",\"sequence\":\"先填写后提交\"}]' AS JSON),
                        CAST('[\"订单创建成功\"]' AS JSON), '满足前提和约束且未触发终止条件，逐步执行并记录结果。',
                        '全部预期结果满足则通过，任一不满足则不通过。', CAST('[\"系统服务终止\"]' AS JSON),
                        '记录实际结果、提示信息及必要证据。', '测试人员', '2026-08-22', 'PENDING_CONFIRMATION',
                        CAST('[\"接口超时阈值\"]' AS JSON))
                """, testcaseWorkId, taskId);
        jdbcTemplate.update("""
                INSERT INTO structured_test_case_step
                (work_item_id, case_key, step_no, action_text, expected_text, evaluation_criteria, termination_or_error, result_collection)
                VALUES (?, 'case-internal-key', 1, '提交订单', '订单创建成功', '实际结果满足本步骤预期结果。', '',
                        '记录实际结果、提示信息及必要证据。')
                """, testcaseWorkId);
        bind(testcaseWorkId, "test-point-internal-key", "TEST_POINT", "REQUIREMENT_FACT", "fact-internal-key");
        bind(testcaseWorkId, "case-internal-key", "TEST_CASE", "REQUIREMENT_FACT", "fact-internal-key");

        String otherTaskId = createAllTask();
        String otherFactWorkId = completedWork(otherTaskId, "requirement-material-quality-review",
                "REQUIREMENT_MATERIAL_REVIEW", "其他任务需求");
        jdbcTemplate.update("""
                INSERT INTO structured_requirement_fact
                (work_item_id, task_id, fact_key, function_name, roles_json, trigger_conditions_json, inputs_json, business_rules_json,
                 outputs_json, permissions_json, state_changes_json, exception_handling_json, external_dependencies_json)
                VALUES (?, ?, 'fact-internal-key', '恶意跨任务需求', CAST('[]' AS JSON), CAST('[]' AS JSON), CAST('[]' AS JSON),
                        CAST('[]' AS JSON), CAST('[]' AS JSON), CAST('[]' AS JSON), CAST('[]' AS JSON), CAST('[]' AS JSON), CAST('[]' AS JSON))
                """, otherFactWorkId, otherTaskId);
        String otherFunctionWorkId = completedWork(otherTaskId, "feature-scope-reconciliation",
                "FEATURE_SCOPE_EXTRACT", "其他任务功能清单");
        jdbcTemplate.update("""
                INSERT INTO structured_function_list_item (work_item_id, task_id, item_key, path_text, description)
                VALUES (?, ?, 'function-list-internal-key', '恶意跨任务功能', '不得进入当前任务投影')
                """, otherFunctionWorkId, otherTaskId);

        var workbookRows = repository.structuredWorkbookRequest(taskId);
        assertThat(workbookRows.reviewRows()).anySatisfy(row -> {
            assertThat(row.affectedScope()).isEqualTo("账号异常状态范围");
            assertThat(row.badSourceExample()).isEqualTo("材料未说明账号禁用后的处理");
            assertThat(row.proposedGoodExample()).contains("待需求方确认");
        });
        assertThat(workbookRows.reviewRows()).anySatisfy(row -> {
            assertThat(row.source()).isEqualTo(com.testcaseagent.export.StructuredReviewRow.Source.FUNCTION_CANDIDATE_AUDIT);
            assertThat(row.subject()).isEqualTo("订单/撤销");
            assertThat(row.classification()).isEqualTo("待确认功能候选");
            assertThat(row.summary()).contains("撤销条件待确认");
        });
        assertThat(workbookRows.testCaseRows()).singleElement().satisfies(row -> {
            assertThat(row.name()).isEqualTo("订单提交正常场景");
            assertThat(row.priority()).isEqualTo(com.testcaseagent.export.StructuredTestCaseRow.Priority.HIGH);
            assertThat(row.inputs()).singleElement().satisfies(input -> {
                assertThat(input.content()).isEqualTo("订单");
                assertThat(input.method()).isEqualTo(
                        com.testcaseagent.export.StructuredTestCaseRow.TestMethod.EQUIVALENCE_PARTITIONING);
            });
            assertThat(row.steps()).singleElement().satisfies(step ->
                    assertThat(step.evaluationCriteria()).isEqualTo("实际结果满足本步骤预期结果。"));
            assertThat(row.authoringInformation().author()).isEqualTo("测试人员");
        });

        JsonNode response = objectMapper.readTree(objectMapper.writeValueAsString(
                GenerationTaskDetailResponse.from(repository.findDetail(taskId).orElseThrow())));
        JsonNode structured = response.path("structuredResult");
        assertThat(structured.path("processingStatus").asText()).isEqualTo("COMPLETED");
        assertThat(structured.path("coverageStatus").asText()).isEqualTo("PARTIAL");
        assertThat(structured.path("pendingCandidateCaseCount").asInt()).isEqualTo(1);
        assertThat(structured.has("functionCandidateSummary")).isFalse();
        assertThat(structured.path("phaseProgress").path("materialTraversal").path("total").asInt()).isEqualTo(2);
        assertThat(structured.path("phaseProgress").path("materialTraversal").path("completed").asInt()).isEqualTo(2);
        assertThat(structured.path("phaseProgress").path("requirementReview").path("total").asInt()).isEqualTo(2);
        assertThat(structured.path("phaseProgress").path("requirementReview").path("completed").asInt()).isEqualTo(2);
        assertThat(structured.path("phaseProgress").path("featureReconciliation").path("total").asInt()).isEqualTo(3);
        assertThat(structured.path("phaseProgress").path("featureReconciliation").path("completed").asInt()).isEqualTo(2);
        assertThat(structured.path("phaseProgress").path("testcaseDesign").path("total").asInt()).isEqualTo(1);
        assertThat(structured.path("phaseProgress").path("testcaseDesign").path("completed").asInt()).isEqualTo(1);
        assertThat(structured.path("reviewFindings").get(0).path("handlingLevel").asText()).isEqualTo("BLOCKING");
        assertThat(structured.path("reconciliations").get(0).path("confirmationStatus").asText()).isEqualTo("CONFIRMED");
        assertThat(structured.path("testPoints").get(0).path("basis").asText()).isEqualTo("FORMAL_REQUIREMENT");
        assertThat(structured.path("testPoints").get(0).path("testcases").get(0).path("status").asText())
                .isEqualTo("PENDING_CONFIRMATION");
        assertThat(structured.has("workflowVersion")).isFalse();
        assertThat(structured.has("retryEligibility")).isFalse();
        assertThat(structured.has("validationFailure")).isFalse();
        assertThat(structured.has("testabilityFeedback")).isFalse();
        assertThat(structured.has("v2Collections")).isFalse();
        JsonNode testcase = structured.path("testPoints").get(0).path("testcases").get(0);
        assertThat(testcase.path("name").asText()).isEqualTo("订单提交正常场景");
        assertThat(testcase.path("priority").asText()).isEqualTo("高");
        assertThat(testcase.path("initialization").path("hardwareConfiguration").get(0).asText()).isEqualTo("办公电脑");
        assertThat(testcase.path("inputs").get(0).path("nature").asText()).isEqualTo("有效");
        assertThat(testcase.path("inputs").get(0).path("method").asText()).isEqualTo("等价类划分");
        assertThat(testcase.path("steps").get(0).path("evaluationCriteria").asText())
                .isEqualTo("实际结果满足本步骤预期结果。");
        assertThat(testcase.path("authoringInformation").path("author").asText()).isEqualTo("测试人员");
        assertThat(structured.path("reviewFindings").get(0).path("affectedScope").asText()).isEqualTo("账号异常状态范围");
        assertThat(structured.path("reviewFindings").get(0).path("proposedGoodExample").asText()).contains("待需求方确认");
        assertThat(response.toString()).contains("订单/提交", "订单提交", "账号被禁用/锁定/未激活时补齐需求说明",
                "外部链接已隐藏", "内部诊断信息已隐藏", "对应功能清单项", "对应需求事实");
        assertThat(response.toString()).doesNotContain("fact-internal-key", "finding-internal-key", "reconciliation-internal-key",
                "test-point-internal-key", "function-internal-key", "case-internal-key", "private.example", "Secret.java",
                "fli-bc5dafcd3684fbf0005736a8110f1ef6adc1af19c63a3e8728e992cb534d0b95",
                "fact-1724e7041424efc97c0cc3dc53109f39", "<internal-path>",
                "<external-url>", "<internal-stack>",
                "accepted_result_sha256", "reference_key", "raw_json", "恶意跨任务需求", "恶意跨任务功能");
        assertThat(response.toString()).doesNotContain("candidate-unit-", "candidate_linked", "model_omitted_unit",
                "insufficient_detail", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "uncommitted-unit", "must_not_project", "未验收/候选", "不得展示");
    }

    @Test
    void publishesAtMostOneConcurrentStructuredArtifactRegeneration() throws Exception {
        String taskId = createAllTask();
        jdbcTemplate.update("""
                UPDATE generation_task SET status = 'COMPLETED', structured_processing_status = 'COMPLETED',
                    structured_coverage_status = 'COMPLETE', artifact_id = 'artifact-old', artifact_sha256 = ?, artifact_path = 'old.xlsx'
                WHERE id = ?
                """, "0".repeat(64), taskId);
        assertThat(repository.structuredArtifactRegenerationBaseline(taskId)).isEqualTo("artifact-old");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> replaceAfter(start, taskId,
                    new WorkbookArtifact("artifact-a", "a".repeat(64), Path.of("a.xlsx"))));
            Future<Boolean> second = executor.submit(() -> replaceAfter(start, taskId,
                    new WorkbookArtifact("artifact-b", "b".repeat(64), Path.of("b.xlsx"))));
            start.countDown();

            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject("SELECT artifact_id FROM generation_task WHERE id = ?", String.class, taskId))
                .isIn("artifact-a", "artifact-b");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM structured_review_finding WHERE task_id = ?", Integer.class, taskId))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM structured_test_case WHERE task_id = ?", Integer.class, taskId))
                .isZero();
    }

    @Test
    void rejectsArtifactRegenerationForMissingOrIneligibleTasks() {
        assertThatThrownBy(() -> repository.structuredArtifactRegenerationBaseline("missing-task"))
                .isInstanceOf(GenerationTaskNotFoundException.class);
        assertThatThrownBy(() -> repository.structuredArtifactRegenerationBaseline(createAllTask()))
                .isInstanceOf(IllegalStateException.class);
    }

    private boolean replaceAfter(CountDownLatch start, String taskId, WorkbookArtifact artifact) throws InterruptedException {
        start.await();
        try {
            repository.replaceStructuredArtifact(taskId, "artifact-old", artifact);
            return true;
        } catch (IllegalStateException conflict) {
            return false;
        }
    }

    private String completedWork(String taskId, String skillName, String operationName, String sourceLabel) {
        String workItemId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO structured_generation_work_item
                (id, task_id, identity_key, skill_name, operation_name, status, source_label, accepted_result_sha256)
                VALUES (?, ?, ?, ?, ?, 'COMPLETED', ?, ?)
                """, workItemId, taskId, UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "").substring(0, 32), skillName, operationName, sourceLabel,
                "a".repeat(64));
        return workItemId;
    }

    private void bind(String workItemId, String subjectKey, String subjectType, String referenceType, String referenceKey) {
        jdbcTemplate.update("""
                INSERT INTO structured_reference_binding (work_item_id, subject_key, subject_type, reference_type, reference_key)
                VALUES (?, ?, ?, ?, ?)
                """, workItemId, subjectKey, subjectType, referenceType, referenceKey);
    }

    private String createAllTask() {
        String taskId = UUID.randomUUID().toString();
        repository.createTask(taskId, new CreateGenerationTaskRequest(
                GenerationTaskMode.ALL, "all", FewShotPolicy.NONE, "1.0", "1.0", "audit-agent",
                new RequirementScope("requirement-kb", "system-1", "version-1", "admission_material", "project-1",
                        List.of(new RequirementDocumentCoordinate("function-document"),
                                new RequirementDocumentCoordinate("requirement-document"))),
                new ExampleScope("example-kb", List.of("example-1")), "requirements_spec", "detail contract test"));
        return taskId;
    }

    private String createV2Task() {
        String taskId = UUID.randomUUID().toString();
        var function = new ApprovedFunctionScope.ApprovedFunction(
                "function-v2", "提交订单", "订单/提交", "提交订单");
        repository.createTask(taskId, new CreateGenerationTaskRequest(
                GenerationTaskMode.ALL, "function-v2", List.of("function-v2"),
                java.util.Map.of("function-v2", "订单/提交"), FewShotPolicy.NONE, "2.0", "2.0", "agent-v2",
                new RequirementScope("requirement-kb-v2", "system-v2", "version-v2", "admission_material",
                        "project-v2", List.of(new RequirementDocumentCoordinate(
                                "requirement-document-v2", "work_order_plan"))),
                new ExampleScope("example-kb-v2", List.of("example-v2")), List.of("work_order_plan"),
                "V2 task detail", new GenerationContractVersions("2.0", "2.0", "2.0"),
                new ApprovedFunctionScope("approved-v2", List.of(function))));
        return taskId;
    }

    private String insertV2Outcome(String taskId, String pointKey, String outcome,
            boolean formalCoverage, List<String> missing) throws Exception {
        String workId = completedWork(taskId, "functional-testcase-design",
                "FUNCTIONAL_TESTCASE_DESIGN_V2", "提交订单");
        jdbcTemplate.update("""
                INSERT INTO structured_test_point
                (work_item_id, task_id, test_point_key, function_key, function_name, test_point_type,
                 basis, description, missing_information_json, formal_coverage_satisfied)
                VALUES (?, ?, ?, 'function-v2', '提交订单', 'normal_behavior', 'formal_requirement',
                        '验证订单提交', CAST(? AS JSON), ?)
                """, workId, taskId, pointKey, objectMapper.writeValueAsString(missing), formalCoverage);
        jdbcTemplate.update("""
                INSERT INTO v2_generation_outcome
                (work_item_id, task_id, test_point_key, function_key, generation_outcome,
                 missing_information_json, formal_coverage_satisfied)
                VALUES (?, ?, ?, 'function-v2', ?, CAST(? AS JSON), ?)
                """, workId, taskId, pointKey, outcome, objectMapper.writeValueAsString(missing), formalCoverage);
        return workId;
    }

    private void insertV2Case(String taskId, String workId, String caseKey, String status,
            List<String> missing) throws Exception {
        jdbcTemplate.update("""
                INSERT INTO structured_test_case
                (work_item_id, task_id, case_key, name_text, title, priority, preconditions_json,
                 hardware_configuration_json, software_configuration_json, test_configuration_json,
                 parameter_configuration_json, inputs_json, expected_results_json, evaluation_criteria,
                 result_evaluation_criteria, termination_conditions_json, result_collection,
                 author_name, author_date, case_status, missing_information_json)
                VALUES (?, ?, ?, '提交订单', '验证提交订单', 'MEDIUM', JSON_ARRAY(), JSON_ARRAY(), JSON_ARRAY(),
                        JSON_ARRAY(), JSON_ARRAY(),
                        JSON_ARRAY(JSON_OBJECT('content','订单号','nature','valid','source','manual',
                          'method','equivalence_partitioning','authenticity','simulated','sequence','')),
                        JSON_ARRAY('订单可以提交'), '逐步执行', '符合预期则通过', JSON_ARRAY(), '记录结果',
                        NULL, NULL, ?, CAST(? AS JSON))
                """, workId, taskId, caseKey, status, objectMapper.writeValueAsString(missing));
        jdbcTemplate.update("""
                INSERT INTO structured_test_case_step
                (work_item_id, case_key, step_no, action_text, expected_text, evaluation_criteria,
                 termination_or_error, result_collection)
                VALUES (?, ?, 1, '提交订单', '订单可以提交', '符合预期', '', '记录结果')
                """, workId, caseKey);
        bind(workId, caseKey, "TEST_CASE", "REQUIREMENT_FACT", "fact-v2");
    }

    /**
     * Simulates retained V1 audit rows beside a V2 task. They remain readable history, but must never restore the
     * retired two-cases-per-feature estimate on the V2 projection. [Req-ID]: REQ-TGV2-007
     */
    private void seedLegacyAuditProjectionForV2Task(String taskId) {
        repository.replaceMaterialInventory(taskId, List.of(new MaterialInventoryDocument(
                "requirement-document-v2", "knowledge-v2", "REQUIREMENT", 1, true,
                List.of(new MaterialInventoryUnit("requirement-document-v2", "REQUIREMENT", "legacy-unit-v2",
                        0, 1, "历史兼容材料", 0, 6)))), false);
        completeWork(taskId, List.of(candidate("legacy-candidate-v2-1", "requirement-document-v2", "legacy-unit-v2", 1)));
        completeWork(taskId, List.of(candidate("legacy-candidate-v2-2", "requirement-document-v2", "legacy-unit-v2", 2)));
        repository.persistFeatureReviewConclusions(taskId, List.of(
                conclusion("legacy-conclusion-v2-1", 1, FeatureReviewConclusionType.MATCHED,
                        "历史兼容结论", "legacy-candidate-v2-1", "legacy-candidate-v2-2")));
        repository.persistFrozenFeatureTargets(taskId, List.of(new FrozenFeatureTarget(
                "legacy-frozen-v2-1", 1, "历史冻结功能", true,
                new FrozenFeatureSource("legacy-conclusion-v2-1", FeatureReviewConclusionType.MATCHED,
                        List.of("legacy-candidate-v2-1", "legacy-candidate-v2-2"), "历史兼容结论"))));
    }

    private static DataSource countingDataSource(
            DataSource delegate, AtomicInteger preparedStatements, List<Integer> fetchSizes) {
        return new DelegatingDataSource(delegate) {
            @Override
            public Connection getConnection() throws java.sql.SQLException {
                return countingConnection(super.getConnection(), preparedStatements, fetchSizes);
            }

            @Override
            public Connection getConnection(String username, String password) throws java.sql.SQLException {
                return countingConnection(super.getConnection(username, password), preparedStatements, fetchSizes);
            }
        };
    }

    private static Connection countingConnection(
            Connection delegate, AtomicInteger preparedStatements, List<Integer> fetchSizes) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (ignored, method, arguments) -> {
                    try {
                        Object result = method.invoke(delegate, arguments);
                        if (method.getName().equals("prepareStatement") && result instanceof PreparedStatement statement) {
                            preparedStatements.incrementAndGet();
                            return recordingPreparedStatement(statement, fetchSizes);
                        }
                        return result;
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }

    private static PreparedStatement recordingPreparedStatement(
            PreparedStatement delegate, List<Integer> fetchSizes) {
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (ignored, method, arguments) -> {
                    if (method.getName().equals("setFetchSize") && arguments != null && arguments.length == 1) {
                        fetchSizes.add((Integer) arguments[0]);
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }

    private static List<MaterialInventoryDocument> documents() {
        return List.of(
                new MaterialInventoryDocument("function-document", "knowledge-function", "FUNCTION_LIST", 1, true,
                        List.of(new MaterialInventoryUnit("function-document", "FUNCTION_LIST", "function-unit", 0, 1,
                                "功能清单", 0, 4))),
                new MaterialInventoryDocument("requirement-document", "knowledge-requirement", "REQUIREMENT", 1, true,
                        List.of(new MaterialInventoryUnit("requirement-document", "REQUIREMENT", "requirement-unit", 0, 1,
                                "需求材料", 0, 4))));
    }

    private void completeAllAuditWork(String taskId) {
        completeWork(taskId, List.of(candidate("candidate-function-1", "function-document", "function-unit", 1),
                candidate("candidate-function-2", "function-document", "function-unit", 2),
                candidate("candidate-function-3", "function-document", "function-unit", 3),
                candidate("candidate-function-4", "function-document", "function-unit", 4)));
        completeWork(taskId, List.of(candidate("candidate-requirement-1", "requirement-document", "requirement-unit", 1)));
        completeWork(taskId, List.of(candidate("candidate-requirement-2", "requirement-document", "requirement-unit", 1)));
    }

    private void completeWork(String taskId, List<FeatureSourceCandidate> candidates) {
        AuditWorkClaim claim = repository.claimNextAuditWork(taskId, "worker", Duration.ofMinutes(1)).orElseThrow();
        List<FeatureSourceCandidate> adjusted = candidates.stream().map(candidate -> new FeatureSourceCandidate(
                candidate.occurrenceId(), candidate.kind(), claim.documentId(), claim.unitId(), candidate.ordinal(),
                candidate.modelSequence(), candidate.featureText(), candidate.category(), candidate.evidenceText(),
                claim.passNumber(), candidate.sourceRowPosition())).toList();
        repository.persistScanAndCompleteAuditWork(claim, adjusted, List.of(), true);
    }

    private static FeatureSourceCandidate candidate(String id, String documentId, String unitId, int rowPosition) {
        return new FeatureSourceCandidate(id, FeatureCandidateKind.FUNCTION_LIST, documentId, unitId, 1, rowPosition,
                "功能" + rowPosition, "功能项", "业务证据", 1, rowPosition);
    }

    private static List<FeatureReviewConclusion> conclusions() {
        return List.of(
                conclusion("conclusion-1", 1, FeatureReviewConclusionType.FUNCTION_LIST_MISSING, "功能清单遗漏", "candidate-function-1"),
                conclusion("conclusion-2", 2, FeatureReviewConclusionType.REQUIREMENT_MISSING, "需求未覆盖", "candidate-function-2"),
                conclusion("conclusion-3", 3, FeatureReviewConclusionType.SPLIT, "需要拆分",
                        "candidate-function-3", "candidate-requirement-1"),
                conclusion("conclusion-4", 4, FeatureReviewConclusionType.MERGE, "需要合并", "candidate-function-4"),
                conclusion("conclusion-5", 5, FeatureReviewConclusionType.INSUFFICIENT_EVIDENCE, "证据不足", "candidate-requirement-2"));
    }

    private static FeatureReviewConclusion conclusion(
            String id, int sequence, FeatureReviewConclusionType type, String decision, String... candidateIds) {
        return new FeatureReviewConclusion(id, sequence, type, decision, "业务证据", List.of(candidateIds));
    }

    private static List<FrozenFeatureTarget> frozenTargets() {
        return List.of(
                target("frozen-1", 1, FeatureReviewConclusionType.FUNCTION_LIST_MISSING, "功能清单遗漏", "candidate-function-1", true),
                target("frozen-2", 2, FeatureReviewConclusionType.REQUIREMENT_MISSING, "需求未覆盖", "candidate-function-2", true),
                target("frozen-3", 3, FeatureReviewConclusionType.SPLIT, "需要拆分", true,
                        "candidate-function-3", "candidate-requirement-1"),
                target("frozen-4", 4, FeatureReviewConclusionType.MERGE, "需要合并", true, "candidate-function-4"),
                target("frozen-5", 5, FeatureReviewConclusionType.INSUFFICIENT_EVIDENCE, "证据不足", "candidate-requirement-2", false));
    }

    private static FrozenFeatureTarget target(
            String id, int sequence, FeatureReviewConclusionType type, String decision, String candidateId, boolean eligible) {
        return target(id, sequence, type, decision, eligible, candidateId);
    }

    private static FrozenFeatureTarget target(
            String id, int sequence, FeatureReviewConclusionType type, String decision, boolean eligible, String... candidateIds) {
        return new FrozenFeatureTarget(id, sequence, "冻结功能" + sequence, eligible,
                new FrozenFeatureSource("conclusion-" + sequence, type, List.of(candidateIds), decision));
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
