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
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
    ObjectMapper objectMapper;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM structured_reference_binding");
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
    void returnsOnlyReaderSafeValidatedStructuredProjectionWithExactWireEnums() throws Exception {
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
        assertThat(structured.path("phaseProgress").path("materialTraversal").path("total").asInt()).isEqualTo(2);
        assertThat(structured.path("phaseProgress").path("materialTraversal").path("completed").asInt()).isEqualTo(2);
        assertThat(structured.path("phaseProgress").path("requirementReview").path("total").asInt()).isEqualTo(2);
        assertThat(structured.path("phaseProgress").path("requirementReview").path("completed").asInt()).isEqualTo(2);
        assertThat(structured.path("phaseProgress").path("featureReconciliation").path("total").asInt()).isEqualTo(2);
        assertThat(structured.path("phaseProgress").path("featureReconciliation").path("completed").asInt()).isEqualTo(2);
        assertThat(structured.path("phaseProgress").path("testcaseDesign").path("total").asInt()).isEqualTo(1);
        assertThat(structured.path("phaseProgress").path("testcaseDesign").path("completed").asInt()).isEqualTo(1);
        assertThat(structured.path("reviewFindings").get(0).path("handlingLevel").asText()).isEqualTo("BLOCKING");
        assertThat(structured.path("reconciliations").get(0).path("confirmationStatus").asText()).isEqualTo("CONFIRMED");
        assertThat(structured.path("testPoints").get(0).path("basis").asText()).isEqualTo("FORMAL_REQUIREMENT");
        assertThat(structured.path("testPoints").get(0).path("testcases").get(0).path("status").asText())
                .isEqualTo("PENDING_CONFIRMATION");
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
