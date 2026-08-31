package com.testcaseagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testcaseagent.featureaudit.AuditWorkClaim;
import com.testcaseagent.featureaudit.FeatureCandidateKind;
import com.testcaseagent.featureaudit.FeatureReviewConclusion;
import com.testcaseagent.featureaudit.FeatureReviewConclusionType;
import com.testcaseagent.featureaudit.FeatureSourceCandidate;
import com.testcaseagent.featureaudit.FrozenFeatureSource;
import com.testcaseagent.featureaudit.FrozenFeatureTarget;
import com.testcaseagent.featureaudit.MaterialInventoryDocument;
import com.testcaseagent.featureaudit.MaterialInventoryPage;
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
 * Proves task-owned material inventory and reclaimable MySQL audit work through repository seams.
 *
 * [Req-ID]: REQ-BFA-001, REQ-BFA-005, REQ-BFA-006
 */
@Testcontainers
@SpringBootTest(classes = TestCaseAgentApplication.class, properties = "app.knowledge-agent.enabled=false")
@Import(MaterialInventoryPersistenceIntegrationTest.RepositoryDependencies.class)
class MaterialInventoryPersistenceIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("material_inventory_persistence_test")
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
        jdbcTemplate.update("DELETE FROM v2_requirement_fact_quote");
        jdbcTemplate.update("DELETE FROM v2_testability_feedback_quote");
        jdbcTemplate.update("DELETE FROM v2_generation_outcome");
        jdbcTemplate.update("DELETE FROM v2_work_publication");
        jdbcTemplate.update("DELETE FROM v2_requirement_fact");
        jdbcTemplate.update("DELETE FROM v2_testability_feedback");
        jdbcTemplate.update("DELETE FROM v2_approved_function");
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
        jdbcTemplate.update("DELETE FROM generation_attempt");
        jdbcTemplate.update("DELETE FROM generation_batch");
        jdbcTemplate.update("DELETE FROM generation_task");
    }

    @Test
    void acceptsAnIdenticalMaterialReplayOnceAndRejectsAConflictingReplay() {
        String taskId = createTask();
        MaterialInventoryUnit original = unit("unit-1", "content one");

        repository.persistMaterialInventory(taskId, List.of(original));
        repository.persistMaterialInventory(taskId, List.of(original));

        assertThat(repository.materialInventory(taskId)).containsExactly(original);
        assertThatThrownBy(() -> repository.persistMaterialInventory(taskId,
                List.of(new MaterialInventoryUnit("document-1", "FUNCTION_LIST", "unit-1", 3, 1,
                        "changed content", 10, 40))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflicts");
        assertThat(repository.materialInventory(taskId)).containsExactly(original);
    }

    @Test
    void reclaimsOnlyExpiredUnfinishedWorkAndNeverReclaimsCompletedWork() throws Exception {
        String taskId = createTask();
        MaterialInventoryUnit firstUnit = unit("unit-1", "content one");
        MaterialInventoryUnit secondUnit = unit("unit-2", "content two");
        repository.persistMaterialInventory(taskId, List.of(firstUnit, secondUnit));
        repository.createAuditWorkIfAbsent("work-one", taskId, firstUnit, 1, "FEATURE_LIST_SCAN");
        repository.createAuditWorkIfAbsent("work-two", taskId, secondUnit, 1, "FEATURE_LIST_SCAN");

        AuditWorkClaim firstClaim = repository.claimNextAuditWork("worker-one", Duration.ofMillis(1)).orElseThrow();
        Thread.sleep(25);
        AuditWorkClaim reclaimed = repository.claimNextAuditWork("worker-two", Duration.ofSeconds(30)).orElseThrow();

        assertThat(reclaimed.workId()).isEqualTo(firstClaim.workId());
        assertThat(reclaimed.attemptNumber()).isEqualTo(2);
        repository.completeAuditWork(reclaimed);

        AuditWorkClaim secondClaim = repository.claimNextAuditWork("worker-three", Duration.ofSeconds(30)).orElseThrow();
        assertThat(secondClaim.workId()).isEqualTo("work-two");
        repository.completeAuditWork(secondClaim);

        assertThat(repository.claimNextAuditWork("worker-four", Duration.ofSeconds(30))).isEmpty();
    }

    @Test
    void restoresTheMostRecentFailedAttemptSummaryWhenDurablyReclaimingTheSameWork() {
        String taskId = createTask();
        MaterialInventoryUnit unit = unit("unit-1", "content one");
        repository.persistMaterialInventory(taskId, List.of(unit));
        repository.createAuditWorkIfAbsent("work-one", taskId, unit, 1, "FEATURE_LIST_SCAN");

        AuditWorkClaim first = repository.claimNextAuditWork("worker-one", Duration.ofSeconds(30)).orElseThrow();
        repository.failAuditWork(first, "Expected strict scan Markdown with heading ## 需求与功能清单审查发现");
        AuditWorkClaim second = repository.claimNextAuditWork("worker-two", Duration.ofSeconds(30)).orElseThrow();
        repository.failAuditWork(second, "Expected strict scan Markdown with only <br> HTML in table cells");
        AuditWorkClaim third = repository.claimNextAuditWork("worker-three", Duration.ofSeconds(30)).orElseThrow();

        assertThat(second.attemptNumber()).isEqualTo(2);
        assertThat(second.previousFailureSummary())
                .isEqualTo("Expected strict scan Markdown with heading ## 需求与功能清单审查发现");
        assertThat(third.attemptNumber()).isEqualTo(3);
        assertThat(third.previousFailureSummary())
                .isEqualTo("Expected strict scan Markdown with only <br> HTML in table cells");
    }

    @Test
    void atomicallyOpensOneFunctionListPassAndTwoRequirementPassesOnlyAfterEveryDocumentIsComplete() {
        String taskId = createTask();
        MaterialInventoryDocument functionList = document("function-doc", "function-doc", "FUNCTION_LIST",
                new MaterialInventoryUnit("function-doc", "FUNCTION_LIST", "function-unit", 0, 1, "功能", 0, 2));
        MaterialInventoryDocument workOrder = document("work-order-doc", "work-order-doc", "WORK_ORDER_PLAN",
                new MaterialInventoryUnit("work-order-doc", "WORK_ORDER_PLAN", "work-unit", 0, 1, "需求", 0, 2));

        repository.replaceMaterialInventory(taskId, List.of(functionList, workOrder), false);

        assertThat(repository.materialInventoryDocuments(taskId)).containsExactly(functionList, workOrder);
        assertThat(repository.hasCompleteMaterialInventory(taskId, new RequirementScope(
                "requirement-kb", "system-1", "version-1", "admission_material", "project-1",
                List.of(new RequirementDocumentCoordinate("function-doc"), new RequirementDocumentCoordinate("work-order-doc")))))
                .isTrue();
        AuditWorkClaim firstPassClaim = repository.claimNextAuditWork("worker-one", Duration.ofSeconds(30)).orElseThrow();
        AuditWorkClaim secondPassClaim = repository.claimNextAuditWork("worker-two", Duration.ofSeconds(30)).orElseThrow();
        List<AuditWorkClaim> initialClaims = List.of(firstPassClaim, secondPassClaim);
        assertThat(initialClaims)
                .extracting(claim -> claim.documentId() + "/" + claim.passNumber() + "/" + claim.stage())
                .containsExactlyInAnyOrder("function-doc/1/FEATURE_LIST_SCAN", "work-order-doc/1/REQUIREMENT_SCAN");
        assertThat(repository.claimNextAuditWork("worker-blocked", Duration.ofSeconds(30))).isEmpty();

        AuditWorkClaim requirementPassOne = initialClaims.stream()
                .filter(claim -> "work-order-doc".equals(claim.documentId())).findFirst().orElseThrow();
        repository.completeAuditWork(requirementPassOne);

        AuditWorkClaim requirementPassTwo = repository.claimNextAuditWork("worker-three", Duration.ofSeconds(30)).orElseThrow();
        assertThat(requirementPassTwo.documentId() + "/" + requirementPassTwo.passNumber() + "/" + requirementPassTwo.stage())
                .isEqualTo("work-order-doc/2/REQUIREMENT_SCAN");
    }

    /** [Req-ID]: REQ-TGV2-003 */
    @Test
    void stagesV2PagesWithoutPublishingAndAtomicallyOpensOnlyACompleteFrozenInventory() {
        RequirementScope scope = new RequirementScope(
                "requirement-kb", "system-1", "version-1", "admission_material", "project-1", List.of(
                new RequirementDocumentCoordinate("function-doc", "function_list"),
                new RequirementDocumentCoordinate("work-order-doc", "work_order_plan")));
        String taskId = createV2Task(scope);
        MaterialInventoryPage functionPage = page("function-doc", "FUNCTION_LIST", 1,
                List.of(new MaterialInventoryUnit(
                        "function-doc", "FUNCTION_LIST", "function-unit", 0, 1, "功能", 0, 2)));
        MaterialInventoryPage requirementPage = page("work-order-doc", "WORK_ORDER_PLAN", 2, List.of(
                new MaterialInventoryUnit("work-order-doc", "WORK_ORDER_PLAN", "work-1", 0, 1, "需求一", 0, 3),
                new MaterialInventoryUnit("work-order-doc", "WORK_ORDER_PLAN", "work-2", 1, 2, "需求二", 4, 7)));

        repository.stageMaterialInventoryPage(taskId, functionPage);

        assertThat(repository.hasCompleteMaterialInventory(taskId, scope)).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM material_inventory_document WHERE task_id = ? AND complete = FALSE",
                Integer.class, taskId)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM material_audit_work WHERE task_id = ?", Integer.class, taskId)).isZero();

        repository.stageMaterialInventoryPage(taskId, functionPage);
        repository.stageMaterialInventoryPage(taskId, requirementPage);
        repository.publishStagedMaterialInventory(taskId, scope);
        repository.publishStagedMaterialInventory(taskId, scope);

        assertThat(repository.hasCompleteMaterialInventory(taskId, scope)).isTrue();
        assertThat(repository.formalRequirementMaterials(taskId)).singleElement()
                .extracting(material -> material.totalUnits()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM material_audit_work WHERE task_id = ?", Integer.class, taskId)).isZero();
    }

    /** [Req-ID]: REQ-TGV2-003 */
    @Test
    void rejectsAGappedStagingInventoryWithoutOpeningTheCompletionGate() {
        RequirementScope scope = new RequirementScope(
                "requirement-kb", "system-1", "version-1", "admission_material", "project-1",
                List.of(new RequirementDocumentCoordinate("work-order-doc", "work_order_plan")));
        String taskId = createV2Task(scope);
        repository.stageMaterialInventoryPage(taskId, page("work-order-doc", "WORK_ORDER_PLAN", 2, List.of(
                new MaterialInventoryUnit(
                        "work-order-doc", "WORK_ORDER_PLAN", "work-2", 1, 2, "需求二", 4, 7))));

        assertThatThrownBy(() -> repository.publishStagedMaterialInventory(taskId, scope))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incomplete");

        assertThat(repository.hasCompleteMaterialInventory(taskId, scope)).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT complete FROM material_inventory_document WHERE task_id = ?", Boolean.class, taskId))
                .isFalse();
    }

    @Test
    void readsLargeFormalMaterialsAsDescriptorsAndBoundedPlanningNeighborhoods() {
        String taskId = createTask();
        List<MaterialInventoryUnit> units = java.util.stream.IntStream.rangeClosed(1, 53)
                .mapToObj(ordinal -> new MaterialInventoryUnit("document-1", "WORK_ORDER_PLAN",
                        "unit-" + ordinal, ordinal - 1, ordinal, "需求单元" + ordinal,
                        ordinal * 10L, ordinal * 10L + 5))
                .toList();
        repository.replaceMaterialInventory(taskId, List.of(new MaterialInventoryDocument(
                "document-1", "knowledge-1", "WORK_ORDER_PLAN", 53, true, units)), false);

        assertThat(repository.formalRequirementMaterials(taskId)).singleElement().satisfies(material -> {
            assertThat(material.documentId()).isEqualTo("document-1");
            assertThat(material.totalUnits()).isEqualTo(53);
            assertThat(material.firstOrdinal()).isOne();
            assertThat(material.lastOrdinal()).isEqualTo(53);
        });
        assertThat(repository.materialInventoryPlanningSlice(taskId, "document-1", 13, 1, 53))
                .extracting(MaterialInventoryUnit::ordinal)
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(9, 32).boxed().toList());
        assertThat(repository.materialInventoryPlanningSlice(taskId, "document-1", 45, 1, 53))
                .extracting(MaterialInventoryUnit::ordinal)
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(41, 53).boxed().toList());
    }

    @Test
    void rejectsAGappedFormalInventoryInsteadOfPlanningATruncatedMaterial() {
        String taskId = createTask();
        List<MaterialInventoryUnit> units = java.util.stream.IntStream.rangeClosed(1, 3)
                .mapToObj(ordinal -> new MaterialInventoryUnit("document-1", "WORK_ORDER_PLAN",
                        "unit-" + ordinal, ordinal - 1, ordinal, "需求单元" + ordinal,
                        ordinal * 10L, ordinal * 10L + 5))
                .toList();
        repository.replaceMaterialInventory(taskId, List.of(new MaterialInventoryDocument(
                "document-1", "knowledge-1", "WORK_ORDER_PLAN", 3, true, units)), false);
        jdbcTemplate.update("DELETE FROM material_audit_work WHERE task_id = ? AND unit_id = 'unit-2'", taskId);
        jdbcTemplate.update("DELETE FROM material_inventory_unit WHERE task_id = ? AND ordinal = 2", taskId);

        assertThatThrownBy(() -> repository.formalRequirementMaterials(taskId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incomplete");
    }

    @Test
    void acceptsOnlyAnIdenticalCompleteReplayUnlessTheCallerExplicitlyRestarts() {
        String taskId = createTask();
        MaterialInventoryDocument original = document("document-1", "document-1", "FUNCTION_LIST",
                new MaterialInventoryUnit("document-1", "FUNCTION_LIST", "unit-1", 0, 1, "first", 0, 5));
        MaterialInventoryDocument replacement = document("document-1", "document-1", "FUNCTION_LIST",
                new MaterialInventoryUnit("document-1", "FUNCTION_LIST", "unit-2", 0, 1, "replacement", 0, 11));

        repository.replaceMaterialInventory(taskId, List.of(original), false);
        repository.replaceMaterialInventory(taskId, List.of(original), false);
        assertThatThrownBy(() -> repository.replaceMaterialInventory(taskId, List.of(replacement), false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("conflicts");
        assertThat(repository.materialInventoryDocuments(taskId)).containsExactly(original);

        repository.replaceMaterialInventory(taskId, List.of(replacement), true);

        assertThat(repository.materialInventoryDocuments(taskId)).containsExactly(replacement);
        AuditWorkClaim restartedWork = repository.claimNextAuditWork("restart-worker", Duration.ofSeconds(30)).orElseThrow();
        assertThat(restartedWork.unitId()).isEqualTo("unit-2");
        assertThat(repository.claimNextAuditWork("restart-worker-two", Duration.ofSeconds(30))).isEmpty();
    }

    @Test
    void scopesIdenticalCandidateAndConclusionIdsToEachTaskAndRejectsAConflictingSingleTaskReplay() {
        String firstTask = createTask();
        String secondTask = createTask();
        MaterialInventoryUnit unit = unit("unit-1", "content one");
        repository.persistMaterialInventory(firstTask, List.of(unit));
        repository.persistMaterialInventory(secondTask, List.of(unit));
        repository.createAuditWorkIfAbsent("first-work", firstTask, unit, 1, "FEATURE_LIST_SCAN");
        repository.createAuditWorkIfAbsent("second-work", secondTask, unit, 1, "FEATURE_LIST_SCAN");
        FeatureSourceCandidate candidate = new FeatureSourceCandidate("same-source-id", FeatureCandidateKind.FUNCTION_LIST,
                "document-1", "unit-1", 1, 1, "订单查询", "功能项", "documentId=document-1; unitId=unit-1", 1, 1);

        repository.persistScanAndCompleteAuditWork(repository.claimNextAuditWork(firstTask, "first", Duration.ofSeconds(30)).orElseThrow(),
                List.of(candidate), List.of(), true);
        repository.persistScanAndCompleteAuditWork(repository.claimNextAuditWork(secondTask, "second", Duration.ofSeconds(30)).orElseThrow(),
                List.of(candidate), List.of(), true);
        FeatureReviewConclusion firstConclusion = conclusion("same-conclusion-id", "same-source-id", "已匹配");
        FeatureReviewConclusion secondConclusion = conclusion("same-conclusion-id", "same-source-id", "已匹配");
        repository.persistFeatureReviewConclusions(firstTask, List.of(firstConclusion));
        repository.persistFeatureReviewConclusions(secondTask, List.of(secondConclusion));

        assertThat(repository.featureAuditCounts(firstTask).coveredCandidateCount()).isOne();
        assertThat(repository.featureAuditCounts(secondTask).coveredCandidateCount()).isOne();
        assertThatThrownBy(() -> repository.persistFeatureReviewConclusions(firstTask,
                List.of(conclusion("changed-id", "same-source-id", "changed explanation"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("conflicts");
    }

    @Test
    void explicitMaterialReplacementDeletesTaskOwnedConclusionJunctionBeforeReplacingInventory() {
        String taskId = createTask();
        MaterialInventoryDocument original = document("document-1", "document-1", "FUNCTION_LIST",
                new MaterialInventoryUnit("document-1", "FUNCTION_LIST", "unit-1", 0, 1, "first", 0, 5));
        MaterialInventoryDocument replacement = document("document-1", "document-1", "FUNCTION_LIST",
                new MaterialInventoryUnit("document-1", "FUNCTION_LIST", "unit-2", 0, 1, "replacement", 0, 11));
        repository.replaceMaterialInventory(taskId, List.of(original), false);
        AuditWorkClaim claim = repository.claimNextAuditWork(taskId, "worker", Duration.ofSeconds(30)).orElseThrow();
        FeatureSourceCandidate candidate = new FeatureSourceCandidate("source-id", FeatureCandidateKind.FUNCTION_LIST,
                "document-1", "unit-1", 1, 1, "订单查询", "功能项", "documentId=document-1; unitId=unit-1", 1, 1);
        repository.persistScanAndCompleteAuditWork(claim, List.of(candidate), List.of(), true);
        repository.persistFeatureReviewConclusions(taskId, List.of(conclusion("conclusion-id", "source-id", "已匹配")));

        repository.replaceMaterialInventory(taskId, List.of(replacement), true);

        assertThat(repository.materialInventoryDocuments(taskId)).containsExactly(replacement);
        assertThat(repository.featureAuditCounts(taskId).candidateCount()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM feature_review_conclusion_candidate WHERE task_id = ?", Integer.class, taskId))
                .isZero();
    }

    @Test
    void persistsOnlyAnIdenticalTaskScopedFrozenFeatureReplay() {
        String taskId = createTask();
        MaterialInventoryDocument document = document("document-1", "document-1", "FUNCTION_LIST",
                new MaterialInventoryUnit("document-1", "FUNCTION_LIST", "unit-1", 0, 1, "订单查询", 0, 4));
        repository.replaceMaterialInventory(taskId, List.of(document), false);
        AuditWorkClaim claim = repository.claimNextAuditWork(taskId, "freeze-worker", Duration.ofSeconds(30)).orElseThrow();
        FeatureSourceCandidate candidate = new FeatureSourceCandidate("candidate-1", FeatureCandidateKind.FUNCTION_LIST,
                "document-1", "unit-1", 1, 1, "订单查询", "功能项", "documentId=document-1; unitId=unit-1", 1, 1);
        repository.persistScanAndCompleteAuditWork(claim, List.of(candidate), List.of(), true);
        repository.persistFeatureReviewConclusions(taskId, List.of(conclusion("conclusion-1", "candidate-1", "订单查询")));
        FrozenFeatureTarget frozen = new FrozenFeatureTarget("ff-stable", 1, "订单查询", true,
                new FrozenFeatureSource("conclusion-1", FeatureReviewConclusionType.MATCHED, List.of("candidate-1"), "订单查询"));

        repository.persistFrozenFeatureTargets(taskId, List.of(frozen));
        repository.persistFrozenFeatureTargets(taskId, List.of(frozen));

        assertThat(repository.frozenFeatureTargets(taskId)).containsExactly(frozen);
        assertThatThrownBy(() -> repository.persistFrozenFeatureTargets(taskId,
                List.of(new FrozenFeatureTarget("ff-stable", 1, "订单查询-变更", true, frozen.source()))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Frozen feature replay conflicts");
    }

    @Test
    void rejectsPartiallyOverlappingConclusionGroupsWithoutPersistingTheFirstGroup() {
        String taskId = createTask();
        MaterialInventoryUnit unit = unit("unit-1", "content one");
        repository.persistMaterialInventory(taskId, List.of(unit));
        repository.createAuditWorkIfAbsent("work", taskId, unit, 1, "FEATURE_LIST_SCAN");
        repository.persistScanAndCompleteAuditWork(repository.claimNextAuditWork(taskId, "worker", Duration.ofSeconds(30)).orElseThrow(),
                List.of(candidate("candidate-a", 1), candidate("candidate-b", 2), candidate("candidate-c", 3)), List.of(), true);

        assertThatThrownBy(() -> repository.persistFeatureReviewConclusions(taskId, List.of(
                conclusion("conclusion-one", 1, List.of("candidate-a", "candidate-b")),
                conclusion("conclusion-two", 2, List.of("candidate-b", "candidate-c")))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("coverage conflicts");

        assertThat(repository.featureAuditCounts(taskId).conclusionCount()).isZero();
        assertThat(repository.featureAuditCounts(taskId).coveredCandidateCount()).isZero();
    }

    private String createTask() {
        String taskId = UUID.randomUUID().toString();
        repository.createTask(taskId, new CreateGenerationTaskRequest(
                GenerationTaskMode.ALL, "all", FewShotPolicy.NONE, "1.0", "1.0", "audit-agent",
                new RequirementScope("requirement-kb", "system-1", "version-1", "admission_material", "project-1",
                        List.of(new RequirementDocumentCoordinate("document-1"))),
                new ExampleScope("example-kb", List.of("example-1")), "requirements_spec", "material audit test"));
        return taskId;
    }

    private String createV2Task(RequirementScope scope) {
        String taskId = UUID.randomUUID().toString();
        ApprovedFunctionScope approved = new ApprovedFunctionScope("scope-v2", List.of(
                new ApprovedFunctionScope.ApprovedFunction("function-a", "提交申请", "业务/提交申请", "")));
        repository.createTask(taskId, new CreateGenerationTaskRequest(
                GenerationTaskMode.ALL, "function-a", List.of("function-a"),
                java.util.Map.of("function-a", "业务/提交申请"), FewShotPolicy.NONE,
                "2.0", "2.0", "audit-agent", scope,
                new ExampleScope("example-kb", List.of("example-1")),
                scope.documents().stream().map(RequirementDocumentCoordinate::materialTypeKey).toList(), "V2 material",
                new GenerationContractVersions("2.0", "2.0", "2.0"), approved));
        return taskId;
    }

    private static MaterialInventoryPage page(
            String documentId, String role, int totalUnits, List<MaterialInventoryUnit> units) {
        return new MaterialInventoryPage(documentId, documentId, role, totalUnits, true, units);
    }

    private static MaterialInventoryUnit unit(String unitId, String content) {
        return new MaterialInventoryUnit("document-1", "FUNCTION_LIST", unitId, 3,
                unitId.equals("unit-1") ? 1 : 2, content, 10, 40);
    }

    private static MaterialInventoryDocument document(String documentId, String knowledgeId, String role,
            MaterialInventoryUnit unit) {
        return new MaterialInventoryDocument(documentId, knowledgeId, role, 1, true, List.of(unit));
    }

    private static FeatureReviewConclusion conclusion(String id, String candidateId, String explanation) {
        return new FeatureReviewConclusion(id, 1, FeatureReviewConclusionType.MATCHED, explanation,
                "candidateIds=" + candidateId + "; documentId=document-1; unitId=unit-1", List.of(candidateId));
    }

    private static FeatureReviewConclusion conclusion(String id, int sequence, List<String> candidateIds) {
        return new FeatureReviewConclusion(id, sequence, FeatureReviewConclusionType.MATCHED, "已匹配",
                "candidateIds=" + String.join(",", candidateIds) + "; documentId=document-1; unitId=unit-1", candidateIds);
    }

    private static FeatureSourceCandidate candidate(String id, int rowPosition) {
        return new FeatureSourceCandidate(id, FeatureCandidateKind.FUNCTION_LIST, "document-1", "unit-1", 1,
                rowPosition, "订单查询" + rowPosition, "功能项", "documentId=document-1; unitId=unit-1", 1, rowPosition);
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
