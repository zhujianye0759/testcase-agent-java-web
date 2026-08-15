package com.testcaseagent.task;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.time.Duration;
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
