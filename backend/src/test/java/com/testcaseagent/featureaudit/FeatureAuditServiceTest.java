package com.testcaseagent.featureaudit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.testcaseagent.fewshot.ExampleScope;
import com.testcaseagent.knowledgeagent.FeatureReconciliationInvocation;
import com.testcaseagent.knowledgeagent.KnowledgeAgentInvocationResult;
import com.testcaseagent.knowledgeagent.KnowledgeAgentPort;
import com.testcaseagent.knowledgeagent.KnowledgeAgentSkillPreparationException;
import com.testcaseagent.scope.RequirementDocumentCoordinate;
import com.testcaseagent.scope.RequirementScope;
import com.testcaseagent.task.CreateGenerationTaskRequest;
import com.testcaseagent.task.GenerationTaskRepository;
import com.testcaseagent.testcase.FewShotPolicy;
import com.testcaseagent.testcase.GenerationTaskMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * [Req-ID]: REQ-KSI-001, REQ-KSI-002, REQ-KSI-003, REQ-BFA-001, REQ-BFA-002, REQ-BFA-003, REQ-BFA-004
 */
class FeatureAuditServiceTest {

    @Test
    void scansEveryClaimBeforeOneStrictFinalReconciliationWithoutExamples() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        MaterialInventoryUnit function = new MaterialInventoryUnit("function-doc", "FUNCTION_LIST", "function-unit", 0, 1,
                "功能清单", 0, 4);
        MaterialInventoryUnit requirement = new MaterialInventoryUnit("requirement-doc", "REQUIREMENT", "requirement-unit", 0, 1,
                "需求", 0, 2);
        AuditWorkClaim functionClaim = claim("function-work", "function-doc", "function-unit", 1, "FEATURE_LIST_SCAN");
        AuditWorkClaim requirementPassOne = claim("requirement-work-1", "requirement-doc", "requirement-unit", 1, "REQUIREMENT_SCAN");
        AuditWorkClaim requirementPassTwo = claim("requirement-work-2", "requirement-doc", "requirement-unit", 2, "REQUIREMENT_SCAN");
        String functionId = FeatureCandidateScanner.occurrenceId(function, 1, 1);
        String requirementId = FeatureCandidateScanner.occurrenceId(requirement, 1, 1);

        when(repository.hasCompleteMaterialInventory("task-1", request.requirementScope())).thenReturn(true);
        when(repository.materialInventory("task-1")).thenReturn(List.of(function, requirement));
        when(repository.claimNextAuditWork(eq("task-1"), any(), any())).thenReturn(Optional.of(functionClaim),
                Optional.of(requirementPassOne), Optional.of(requirementPassTwo), Optional.empty());
        when(repository.featureSourceCandidates("task-1", "requirement-doc", "requirement-unit", 1)).thenReturn(List.of(
                new FeatureSourceCandidate(requirementId, FeatureCandidateKind.REQUIREMENT, "requirement-doc", "requirement-unit",
                        1, 1, "提交订单", "功能项", "documentId=requirement-doc; unitId=requirement-unit", 1, 1)));
        when(repository.featureSourceCandidates("task-1")).thenReturn(List.of(
                new FeatureSourceCandidate(functionId, FeatureCandidateKind.FUNCTION_LIST, "function-doc", "function-unit", 1, 1,
                        "订单查询", "功能项", "documentId=function-doc; unitId=function-unit", 1, 1),
                new FeatureSourceCandidate(requirementId, FeatureCandidateKind.REQUIREMENT, "requirement-doc", "requirement-unit", 1, 1,
                        "提交订单", "功能项", "documentId=requirement-doc; unitId=requirement-unit", 1, 1)));
        when(repository.featureAuditCounts("task-1")).thenReturn(
                new GenerationTaskRepository.FeatureAuditCounts(3, 3, 0, 2, 0, 0),
                new GenerationTaskRepository.FeatureAuditCounts(3, 3, 0, 2, 1, 2));
        when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(result(scanResponse("""
                | 1 | 订单查询 | 功能项 | documentId=function-doc; unitId=function-unit |
                """)), result(scanResponse("""
                | 1 | 提交订单 | 功能项 | documentId=requirement-doc; unitId=requirement-unit |
                """)), result(scanResponse("""
                | 1 | 提交订单 | 功能项 | documentId=requirement-doc; unitId=requirement-unit |
                """)), result(scanResponse("""
                | 1 | 订单查询与提交订单 | 匹配 | candidateIds=%s,%s; documentId=function-doc; unitId=function-unit; documentId=requirement-doc; unitId=requirement-unit |
                """.formatted(functionId, requirementId))));

        FeatureAuditResult result = service.audit("task-1", request);

        assertThat(result.complete()).isTrue();
        assertThat(result.candidateCount()).isEqualTo(2);
        verify(repository).persistScanAndCompleteAuditWork(functionClaim, List.of(
                new FeatureSourceCandidate(functionId, FeatureCandidateKind.FUNCTION_LIST, "function-doc", "function-unit", 1, 1,
                        "订单查询", "功能项", "documentId=function-doc; unitId=function-unit", 1, 1)), List.of(), true);
        verify(repository).persistScanAndCompleteAuditWork(requirementPassTwo, List.of(), List.of(
                new FeatureSourceCandidate(FeatureCandidateScanner.occurrenceId(requirement, 2, 1), FeatureCandidateKind.REQUIREMENT,
                        "requirement-doc", "requirement-unit", 1, 1, "提交订单", "功能项",
                        "documentId=requirement-doc; unitId=requirement-unit", 2, 1)), true);
        ArgumentCaptor<FeatureReconciliationInvocation> invocation = ArgumentCaptor.forClass(FeatureReconciliationInvocation.class);
        verify(knowledgeAgentPort, times(4)).reconcileFeatures(invocation.capture());
        verify(knowledgeAgentPort, times(4)).prepareReconciliationSession(any());
        verify(knowledgeAgentPort, times(4)).closePreparedSession();
        FeatureReconciliationInvocation finalInvocation = invocation.getAllValues().get(3);
        assertThat(finalInvocation.prompt()).contains(functionId, requirementId, "documentId=function-doc", "unitId=requirement-unit")
                .doesNotContain("example-kb", "example-doc");
        inOrder(repository).verify(repository).persistScanAndCompleteAuditWork(eq(functionClaim), any(), any(), eq(true));
    }

    @Test
    void recordsAFailedClaimWithoutMarkingAuditComplete() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        MaterialInventoryUnit unit = new MaterialInventoryUnit("function-doc", "FUNCTION_LIST", "function-unit", 0, 1, "功能", 0, 2);
        AuditWorkClaim claim = claim("function-work", "function-doc", "function-unit", 1, "FEATURE_LIST_SCAN");

        when(repository.hasCompleteMaterialInventory("task-1", request.requirementScope())).thenReturn(true);
        when(repository.materialInventory("task-1")).thenReturn(List.of(unit));
        when(repository.claimNextAuditWork(eq("task-1"), any(), any())).thenReturn(Optional.of(claim), Optional.empty());
        when(knowledgeAgentPort.reconcileFeatures(any())).thenThrow(new IllegalStateException("remote terminal failure"));
        when(repository.featureAuditCounts("task-1")).thenReturn(new GenerationTaskRepository.FeatureAuditCounts(1, 0, 1, 0, 0, 0));

        FeatureAuditResult result = service.audit("task-1", request);

        verify(repository).failAuditWork(eq(claim), eq("remote terminal failure"), eq(true));
        verify(knowledgeAgentPort).prepareReconciliationSession(any());
        verify(knowledgeAgentPort).closePreparedSession();
        assertThat(result.complete()).isFalse();
        assertThat(result.permanentlyFailedAuditWork()).isOne();
    }

    @Test
    void stopsBeforeTheNextBoundedAuditWorkWhenCancellationIsRequested() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        MaterialInventoryUnit unit = new MaterialInventoryUnit("function-doc", "FUNCTION_LIST", "function-unit", 0, 1,
                "功能", 0, 2);
        AuditWorkClaim firstClaim = claim("function-work-1", "function-doc", "function-unit", 1, "FEATURE_LIST_SCAN");

        when(repository.hasCompleteMaterialInventory("task-1", request.requirementScope())).thenReturn(true);
        when(repository.materialInventory("task-1")).thenReturn(List.of(unit));
        when(repository.isCancellationRequested("task-1")).thenReturn(false, false, true);
        when(repository.claimNextAuditWork(eq("task-1"), any(), any())).thenReturn(Optional.of(firstClaim));
        when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(result(scanResponse("""
                | 1 | 订单查询 | 功能项 | documentId=function-doc; unitId=function-unit |
                """)));

        assertThatThrownBy(() -> service.audit("task-1", request))
                .isInstanceOf(CancellationException.class);

        verify(repository).persistScanAndCompleteAuditWork(eq(firstClaim), any(), any(), eq(true));
        verify(repository, never()).featureAuditCounts("task-1");
        verify(knowledgeAgentPort, times(1)).reconcileFeatures(any());
        verify(knowledgeAgentPort).closePreparedSession();
    }

    @Test
    void doesNotTreatAnEmptyCandidateLedgerAsACompleteAllAudit() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        when(repository.hasCompleteMaterialInventory("task-1", request.requirementScope())).thenReturn(true);
        when(repository.materialInventory("task-1")).thenReturn(List.of());
        when(repository.claimNextAuditWork(eq("task-1"), any(), any())).thenReturn(Optional.empty());
        when(repository.featureAuditCounts("task-1")).thenReturn(new GenerationTaskRepository.FeatureAuditCounts(0, 0, 0, 0, 0, 0));

        assertThat(service.audit("task-1", request).complete()).isFalse();
        verify(knowledgeAgentPort, never()).prepareReconciliationSession(any());
        verify(knowledgeAgentPort, never()).closePreparedSession();
    }

    @Test
    void doesNotPrepareAConversationWhenAllAuditWorkAndCoverageAreAlreadyDurable() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();

        when(repository.hasCompleteMaterialInventory("task-1", request.requirementScope())).thenReturn(true);
        when(repository.materialInventory("task-1")).thenReturn(List.of());
        when(repository.claimNextAuditWork(eq("task-1"), any(), any())).thenReturn(Optional.empty());
        when(repository.featureAuditCounts("task-1")).thenReturn(new GenerationTaskRepository.FeatureAuditCounts(2, 2, 0, 2, 2, 2));

        assertThat(service.audit("task-1", request).complete()).isTrue();
        verify(knowledgeAgentPort, never()).prepareReconciliationSession(any());
        verify(knowledgeAgentPort, never()).reconcileFeatures(any());
        verify(knowledgeAgentPort, never()).closePreparedSession();
    }

    @Test
    void recordsSkillPreparationFailureAsImmediatelyPermanentAuditWork() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        MaterialInventoryUnit unit = new MaterialInventoryUnit("function-doc", "FUNCTION_LIST", "function-unit", 0, 1, "功能", 0, 2);
        AuditWorkClaim claim = claim("function-work", "function-doc", "function-unit", 1, "FEATURE_LIST_SCAN");

        when(repository.hasCompleteMaterialInventory("task-1", request.requirementScope())).thenReturn(true);
        when(repository.materialInventory("task-1")).thenReturn(List.of(unit));
        when(repository.claimNextAuditWork(eq("task-1"), any(), any())).thenReturn(Optional.of(claim), Optional.empty());
        doThrow(new KnowledgeAgentSkillPreparationException("Skill unavailable", false, null))
                .when(knowledgeAgentPort).prepareReconciliationSession(any());
        when(repository.featureAuditCounts("task-1")).thenReturn(new GenerationTaskRepository.FeatureAuditCounts(1, 0, 1, 0, 0, 0));

        assertThat(service.audit("task-1", request).permanentlyFailedAuditWork()).isOne();
        verify(repository).failAuditWork(eq(claim), eq("Skill unavailable"), eq(false));
        verify(knowledgeAgentPort, never()).reconcileFeatures(any());
    }

    @Test
    void rejectsPrefixAndCrossPairEvidenceReferences() {
        FeatureSourceCandidate candidate = new FeatureSourceCandidate("candidate", FeatureCandidateKind.FUNCTION_LIST,
                "doc", "unit", 1, 1, "订单", "功能项", "documentId=doc; unitId=unit", 1, 1);
        FeatureReviewConclusion conclusion = new FeatureReviewConclusion("conclusion", 1, FeatureReviewConclusionType.MATCHED,
                "订单", "candidateIds=candidate; documentId=doc2; unitId=unit", List.of("candidate"));

        assertThatThrownBy(() -> FeatureAuditService.requireFormalEvidenceReferences(List.of(conclusion), List.of(candidate)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("documentId and unitId");
    }

    @Test
    void rejectsEnglishConclusionCategoryAliasesOutsideTheChinesePromptContract() {
        assertThatThrownBy(() -> new FeatureConclusionMarkdownParser().parse(scanResponse("""
                | 1 | 订单查询 | MATCHED | candidateIds=candidate; documentId=doc; unitId=unit |
                """), Set.of("candidate")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("known terminal conclusion type");
    }

    private static AuditWorkClaim claim(String workId, String documentId, String unitId, int pass, String stage) {
        return new AuditWorkClaim(workId, workId + "-attempt", 1, "task-1", documentId, unitId, pass, stage, Instant.now().plusSeconds(30));
    }

    private static KnowledgeAgentInvocationResult result(String markdown) {
        return new KnowledgeAgentInvocationResult("session", List.of(), markdown);
    }

    private static String scanResponse(String rows) {
        return ("""
                ## 需求与功能清单审查发现
                | 序号 | 对象/功能点 | 问题分类 | 证据对照 |
                |---|---|---|---|
                """ + rows + """

                ## 测试用例
                | 用例名称 | 功能模块 | 前提约束 | 执行步骤 | 预期结果 | 对应需求内容 |
                |---|---|---|---|---|---|
                """).stripIndent();
    }

    private static CreateGenerationTaskRequest request() {
        return new CreateGenerationTaskRequest(GenerationTaskMode.ALL, "all", FewShotPolicy.NONE, "1", "1", "audit-agent",
                new RequirementScope("requirement-kb", "system", "version", "admission", "project", List.of(
                        new RequirementDocumentCoordinate("function-doc"), new RequirementDocumentCoordinate("requirement-doc"))),
                new ExampleScope("example-kb", List.of("example-doc")), "requirements_spec", "audit");
    }
}
