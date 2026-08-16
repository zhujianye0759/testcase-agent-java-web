package com.testcaseagent.featureaudit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
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
 * [Req-ID]: REQ-KSI-001, REQ-KSI-002, REQ-KSI-003, REQ-BFA-001, REQ-BFA-002, REQ-BFA-003, REQ-BFA-004, REQ-BFA-006, REQ-BFA-007
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
                | 1 | 订单查询与提交订单 | 匹配 | candidateIds=%s; groupAnchorId=%s; documentId=function-doc; unitId=function-unit |
                | 2 | 订单查询与提交订单 | 匹配 | candidateIds=%s; groupAnchorId=%s; documentId=requirement-doc; unitId=requirement-unit |
                """.formatted(functionId, functionId, requirementId, functionId))));

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
        assertThat(finalInvocation.prompt()).contains(functionId, requirementId, "documentId=function-doc", "unitId=requirement-unit",
                "机器 token 必须是独立分号段", "groupAnchorId=<全量 candidateId>", "不得与 `<br>` 或说明文字粘连",
                "至少两个互异纯文本业务路径", "其他分类必须为单一纯文本且不得含 `<br>`", "不同层级值不是冲突", "同一层级或同一语义字段互斥", "归为证据不足")
                .doesNotContain("example-kb", "example-doc");
        inOrder(repository).verify(repository).persistScanAndCompleteAuditWork(eq(functionClaim), any(), any(), eq(true));
    }

    @Test
    void reconcilesBoundedPagesWithGlobalContextAndAtomicallyMergesACrossPageGroup() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        List<FeatureSourceCandidate> candidates = java.util.stream.IntStream.rangeClosed(1, 17)
                .mapToObj(FeatureAuditServiceTest::candidate).toList();

        when(repository.hasCompleteMaterialInventory("task-1", request.requirementScope())).thenReturn(true);
        when(repository.materialInventory("task-1")).thenReturn(List.of());
        when(repository.claimNextAuditWork(eq("task-1"), any(), any())).thenReturn(Optional.empty());
        when(repository.featureSourceCandidates("task-1")).thenReturn(candidates);
        when(repository.featureAuditCounts("task-1")).thenReturn(
                new GenerationTaskRepository.FeatureAuditCounts(1, 1, 0, 17, 0, 0),
                new GenerationTaskRepository.FeatureAuditCounts(1, 1, 0, 17, 16, 17));
        when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(
                result(boundedReconciliationResponse(candidates.subList(0, 16), "candidate-01", "candidate-17")),
                result(boundedReconciliationResponse(candidates.subList(16, 17), "candidate-01", "candidate-17")));

        FeatureAuditResult audit = service.audit("task-1", request);

        assertThat(audit.complete()).isTrue();
        ArgumentCaptor<FeatureReconciliationInvocation> invocations = ArgumentCaptor.forClass(FeatureReconciliationInvocation.class);
        verify(knowledgeAgentPort, times(2)).reconcileFeatures(invocations.capture());
        assertThat(invocations.getAllValues()).allSatisfy(invocation -> assertThat(invocation.prompt())
                .contains("candidate-01", "candidate-17", "全量候选项仅作比较上下文"));
        ArgumentCaptor<List<FeatureReviewConclusion>> persisted = ArgumentCaptor.forClass(List.class);
        verify(repository).persistFeatureReviewConclusions(eq("task-1"), persisted.capture());
        assertThat(persisted.getValue()).hasSize(16);
        assertThat(persisted.getValue()).anySatisfy(conclusion -> assertThat(conclusion.candidateIds())
                .containsExactly("candidate-01", "candidate-17"));
    }

    @Test
    void retriesARejectedAnchoredGroupBeforeOneAtomicPersist() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        List<FeatureSourceCandidate> candidates = List.of(candidate(1), candidate(2));

        when(repository.hasCompleteMaterialInventory("task-1", request.requirementScope())).thenReturn(true);
        when(repository.materialInventory("task-1")).thenReturn(List.of());
        when(repository.claimNextAuditWork(eq("task-1"), any(), any())).thenReturn(Optional.empty());
        when(repository.featureSourceCandidates("task-1")).thenReturn(candidates);
        when(repository.featureAuditCounts("task-1")).thenReturn(
                new GenerationTaskRepository.FeatureAuditCounts(1, 1, 0, 2, 0, 0),
                new GenerationTaskRepository.FeatureAuditCounts(1, 1, 0, 2, 2, 2));
        String inconsistent = scanResponse("""
                | 1 | 同一功能 | 匹配 | candidateIds=candidate-01; groupAnchorId=candidate-01; documentId=requirement-doc; unitId=requirement-unit |
                | 2 | 不同功能 | 重复 | candidateIds=candidate-02; groupAnchorId=candidate-01; documentId=requirement-doc; unitId=requirement-unit |
                """);
        when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(result(inconsistent), result(inconsistent),
                result(boundedReconciliationResponse(candidates, "candidate-01", "outside-group")));

        assertThat(service.audit("task-1", request).complete()).isTrue();

        ArgumentCaptor<FeatureReconciliationInvocation> invocations = ArgumentCaptor.forClass(FeatureReconciliationInvocation.class);
        verify(knowledgeAgentPort, times(3)).reconcileFeatures(invocations.capture());
        assertThat(invocations.getAllValues().get(1).prompt()).contains("重试纠正要求：", "固定格式基线", "groupAnchorId");
        verify(repository).persistFeatureReviewConclusions(eq("task-1"), any());
    }

    @Test
    void failsClosedWithoutPersistingWhenTheSecondBoundedPageIsMalformedThreeTimes() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        List<FeatureSourceCandidate> candidates = java.util.stream.IntStream.rangeClosed(1, 17)
                .mapToObj(FeatureAuditServiceTest::candidate).toList();

        when(repository.hasCompleteMaterialInventory("task-1", request.requirementScope())).thenReturn(true);
        when(repository.materialInventory("task-1")).thenReturn(List.of());
        when(repository.claimNextAuditWork(eq("task-1"), any(), any())).thenReturn(Optional.empty());
        when(repository.featureSourceCandidates("task-1")).thenReturn(candidates);
        when(repository.featureAuditCounts("task-1")).thenReturn(new GenerationTaskRepository.FeatureAuditCounts(1, 1, 0, 17, 0, 0));
        when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(
                result(boundedReconciliationResponse(candidates.subList(0, 16), "candidate-01", "outside-group")),
                result("unexpected raw final response"), result("unexpected raw final response"), result("unexpected raw final response"));

        Throwable failure = catchThrowable(() -> service.audit("task-1", request));
        assertThat(failure).isInstanceOf(IllegalStateException.class)
                .hasMessage("Final reconciliation page did not meet the strict contract after three attempts");
        assertThat(failure.getMessage()).doesNotContain("unexpected raw final response");

        verify(knowledgeAgentPort, times(4)).reconcileFeatures(any());
        verify(repository, never()).persistFeatureReviewConclusions(any(), any());
    }

    @Test
    void failsClosedAfterThreeRetriesForUnknownAndNonSelfAnchors() {
        assertRejectedFinalReconciliation(List.of(candidate(1)), scanResponse("""
                | 1 | 功能1 | 匹配 | candidateIds=candidate-01; groupAnchorId=unknown-anchor; documentId=requirement-doc; unitId=requirement-unit |
                """));
        assertRejectedFinalReconciliation(List.of(candidate(1), candidate(2)), scanResponse("""
                | 1 | 同一功能 | 匹配 | candidateIds=candidate-01; groupAnchorId=candidate-02; documentId=requirement-doc; unitId=requirement-unit |
                | 2 | 同一功能 | 匹配 | candidateIds=candidate-02; groupAnchorId=candidate-01; documentId=requirement-doc; unitId=requirement-unit |
                """));
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
    void retriesTheSameAuditWorkWithSafeMarkdownCorrectionFeedbackOnlyAfterTheFirstAttempt() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        MaterialInventoryUnit unit = new MaterialInventoryUnit("function-doc", "FUNCTION_LIST", "function-unit", 0, 1,
                "功能", 0, 2);
        AuditWorkClaim firstAttempt = claim("function-work", "function-doc", "function-unit", 1, "FEATURE_LIST_SCAN", 1);
        AuditWorkClaim secondAttempt = claim("function-work", "function-doc", "function-unit", 1, "FEATURE_LIST_SCAN", 2,
                "Expected strict scan Markdown with heading ## 需求与功能清单审查发现");
        AuditWorkClaim thirdAttempt = claim("function-work", "function-doc", "function-unit", 1, "FEATURE_LIST_SCAN", 3,
                "Expected strict scan Markdown with only <br> HTML in table cells");
        GenerationTaskRepository.FeatureAuditCounts completeCounts =
                new GenerationTaskRepository.FeatureAuditCounts(1, 1, 0, 1, 1, 1);

        when(repository.hasCompleteMaterialInventory("task-1", request.requirementScope())).thenReturn(true);
        when(repository.materialInventory("task-1")).thenReturn(List.of(unit));
        when(repository.claimNextAuditWork(eq("task-1"), any(), any())).thenReturn(Optional.of(firstAttempt),
                Optional.of(secondAttempt), Optional.of(thirdAttempt), Optional.empty());
        when(repository.featureAuditCounts("task-1")).thenReturn(completeCounts);
        when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(
                result("错误标题"),
                result("<div>不允许的 HTML</div>"),
                result(scanResponse("""
                        | 1 | 订单查询 | 功能项 | documentId=function-doc; unitId=function-unit; 来源文字 |
                        """)));

        service.audit("task-1", request);

        ArgumentCaptor<FeatureReconciliationInvocation> invocations = ArgumentCaptor.forClass(FeatureReconciliationInvocation.class);
        verify(knowledgeAgentPort, times(3)).reconcileFeatures(invocations.capture());
        assertThat(invocations.getAllValues().get(0).prompt()).doesNotContain("上一轮未通过固定 Markdown 格式校验");
        assertThat(invocations.getAllValues().get(1).prompt()).contains("上一轮未通过固定 Markdown 格式校验",
                "删除标题前的任何分析、说明、结论或引导语", "第一行必须精确为 ## 需求与功能清单审查发现");
        assertThat(invocations.getAllValues().get(2).prompt()).contains("上一轮未通过固定 Markdown 格式校验", "只允许使用 <br>");
    }

    @Test
    void retainsTheCompleteFormatBaselineAcrossAlternatingStrictMarkdownFailures() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        MaterialInventoryUnit unit = new MaterialInventoryUnit("function-doc", "FUNCTION_LIST", "function-unit", 0, 1,
                "功能", 0, 2);
        AuditWorkClaim firstAttempt = claim("alternating-work", "function-doc", "function-unit", 1, "FEATURE_LIST_SCAN", 1);
        AuditWorkClaim secondAttempt = claim("alternating-work", "function-doc", "function-unit", 1, "FEATURE_LIST_SCAN", 2,
                "Candidate evidence has duplicate or malformed coordinate tokens");
        AuditWorkClaim thirdAttempt = claim("alternating-work", "function-doc", "function-unit", 1, "FEATURE_LIST_SCAN", 3,
                "Expected strict scan Markdown with Markdown tables instead of JSON or code fences");
        GenerationTaskRepository.FeatureAuditCounts completeCounts =
                new GenerationTaskRepository.FeatureAuditCounts(1, 1, 0, 1, 1, 1);

        when(repository.hasCompleteMaterialInventory("task-1", request.requirementScope())).thenReturn(true);
        when(repository.materialInventory("task-1")).thenReturn(List.of(unit));
        when(repository.claimNextAuditWork(eq("task-1"), any(), any())).thenReturn(Optional.of(firstAttempt),
                Optional.of(secondAttempt), Optional.of(thirdAttempt), Optional.empty());
        when(repository.featureAuditCounts("task-1")).thenReturn(completeCounts);
        when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(
                result(scanResponse("""
                        | 1 | 订单查询 | 功能项 | documentId=function-doc; documentId=function-doc; unitId=function-unit; 来源文字 |
                        """)),
                result("```markdown\n" + scanResponse("""
                        | 1 | 订单查询 | 功能项 | documentId=function-doc; unitId=function-unit; 来源文字 |
                        """) + "\n```"),
                result(scanResponse("""
                        | 1 | 订单查询 | 功能项 | documentId=function-doc; unitId=function-unit; 来源文字 |
                        """)));

        service.audit("task-1", request);

        ArgumentCaptor<FeatureReconciliationInvocation> invocations = ArgumentCaptor.forClass(FeatureReconciliationInvocation.class);
        verify(knowledgeAgentPort, times(3)).reconcileFeatures(invocations.capture());
        assertThat(invocations.getAllValues().get(0).prompt()).doesNotContain("固定格式基线");
        String secondFeedback = retryFeedback(invocations.getAllValues().get(1).prompt());
        String thirdFeedback = retryFeedback(invocations.getAllValues().get(2).prompt());
        assertThat(secondFeedback).contains("固定格式基线", "精确两张 Markdown 表", "标题、表头和分隔行必须与本次提示完全一致",
                "每个非空数据行必须恰好四列", "不得返回 JSON 或代码围栏", "仅允许 <br>", "第二张表必须为零数据行",
                "documentId 坐标标记", "unitId 坐标标记", "各出现一次", "第二个坐标后必须以分号接证据正文", "两个坐标标记必须各出现一次且格式正确")
                .doesNotContain("<exact>", "function-doc", "function-unit", "Candidate evidence has duplicate or malformed coordinate tokens");
        assertThat(thirdFeedback).contains("固定格式基线", "精确两张 Markdown 表", "标题、表头和分隔行必须与本次提示完全一致",
                "每个非空数据行必须恰好四列", "不得返回 JSON 或代码围栏", "仅允许 <br>", "第二张表必须为零数据行",
                "documentId 坐标标记", "unitId 坐标标记", "各出现一次", "第二个坐标后必须以分号接证据正文", "不得返回 JSON 或代码围栏")
                .doesNotContain("<exact>", "function-doc", "function-unit", "Expected strict scan Markdown", "Candidate evidence has duplicate or malformed coordinate tokens");
    }

    @Test
    void tellsAReclaimedHeadingFailureToRemoveAllLeadingProseBeforeTheExactFirstHeading() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        MaterialInventoryUnit unit = new MaterialInventoryUnit("function-doc", "FUNCTION_LIST", "function-unit", 0, 1,
                "功能", 0, 2);
        String leadingProse = "根据技能指令和材料单元内容分析：";
        AuditWorkClaim firstAttempt = claim("heading-work", "function-doc", "function-unit", 1, "FEATURE_LIST_SCAN", 1);
        AuditWorkClaim secondAttempt = claim("heading-work", "function-doc", "function-unit", 1, "FEATURE_LIST_SCAN", 2,
                "Expected strict scan Markdown with heading ## 需求与功能清单审查发现");
        GenerationTaskRepository.FeatureAuditCounts completeCounts =
                new GenerationTaskRepository.FeatureAuditCounts(1, 1, 0, 1, 1, 1);

        when(repository.hasCompleteMaterialInventory("task-1", request.requirementScope())).thenReturn(true);
        when(repository.materialInventory("task-1")).thenReturn(List.of(unit));
        when(repository.claimNextAuditWork(eq("task-1"), any(), any())).thenReturn(Optional.of(firstAttempt),
                Optional.of(secondAttempt), Optional.empty());
        when(repository.featureAuditCounts("task-1")).thenReturn(completeCounts);
        when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(
                result(leadingProse + "\n" + scanResponse("""
                        | 1 | 订单查询 | 功能项 | documentId=function-doc; unitId=function-unit; 来源文字 |
                        """)),
                result(scanResponse("""
                        | 1 | 订单查询 | 功能项 | documentId=function-doc; unitId=function-unit; 来源文字 |
                        """)));

        service.audit("task-1", request);

        ArgumentCaptor<FeatureReconciliationInvocation> invocations = ArgumentCaptor.forClass(FeatureReconciliationInvocation.class);
        verify(knowledgeAgentPort, times(2)).reconcileFeatures(invocations.capture());
        String secondFeedback = retryFeedback(invocations.getAllValues().get(1).prompt());
        assertThat(secondFeedback).contains("输出第一个字符必须是 #", "第一行必须精确为 ## 需求与功能清单审查发现",
                "第一标题前不得有分析、说明、结论或引导语", "删除标题前的任何分析、说明、结论或引导语")
                .doesNotContain(leadingProse, "function-doc", "function-unit", "Expected strict scan Markdown");
    }

    @Test
    void tellsAReclaimedSecondHeadingFailureToOutputOnlyTwoContiguousTablesWithoutSelfReview() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        MaterialInventoryUnit unit = new MaterialInventoryUnit("requirement-doc", "REQUIREMENT", "requirement-unit", 0, 1,
                "需求", 0, 2);
        FeatureSourceCandidate acceptedFirstPass = new FeatureSourceCandidate("first-pass", FeatureCandidateKind.REQUIREMENT,
                "requirement-doc", "requirement-unit", 1, 1, "订单查询", "功能项",
                "documentId=requirement-doc; unitId=requirement-unit", 1, 1);
        AuditWorkClaim firstAttempt = claim("second-heading-work", "requirement-doc", "requirement-unit", 2, "REQUIREMENT_SCAN", 1);
        AuditWorkClaim secondAttempt = claim("second-heading-work", "requirement-doc", "requirement-unit", 2, "REQUIREMENT_SCAN", 2,
                "Candidate evidence has duplicate or malformed coordinate tokens");
        AuditWorkClaim thirdAttempt = claim("second-heading-work", "requirement-doc", "requirement-unit", 2, "REQUIREMENT_SCAN", 3,
                "Expected strict scan Markdown with heading ## 测试用例");
        GenerationTaskRepository.FeatureAuditCounts completeCounts =
                new GenerationTaskRepository.FeatureAuditCounts(1, 1, 0, 1, 1, 1);
        String selfReview = "Wait, the instruction says the tentative row should be withdrawn.";
        String repeatedTable = "## 需求与功能清单审查发现";

        when(repository.hasCompleteMaterialInventory("task-1", request.requirementScope())).thenReturn(true);
        when(repository.materialInventory("task-1")).thenReturn(List.of(unit));
        when(repository.claimNextAuditWork(eq("task-1"), any(), any())).thenReturn(Optional.of(firstAttempt),
                Optional.of(secondAttempt), Optional.of(thirdAttempt), Optional.empty());
        when(repository.featureSourceCandidates("task-1", "requirement-doc", "requirement-unit", 1))
                .thenReturn(List.of(acceptedFirstPass));
        when(repository.featureAuditCounts("task-1")).thenReturn(completeCounts);
        when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(
                result(scanResponse("""
                        | 1 | 订单查询 | 功能项 | documentId=requirement-doc; documentId=requirement-doc; unitId=requirement-unit; 来源文字 |
                        """)),
                result(secondHeadingFailureResponse(selfReview, repeatedTable)),
                result(scanResponse("")));

        service.audit("task-1", request);

        ArgumentCaptor<FeatureReconciliationInvocation> invocations = ArgumentCaptor.forClass(FeatureReconciliationInvocation.class);
        verify(knowledgeAgentPort, times(3)).reconcileFeatures(invocations.capture());
        String thirdFeedback = retryFeedback(invocations.getAllValues().get(2).prompt());
        assertThat(thirdFeedback).contains("整份输出只能包含两张表", "第一表最后一行后的下一非空行必须直接是 ## 测试用例",
                "不得输出思考过程、Wait/Let's、复核说明、自我纠错、重复标题或重复表", "若判断无新增项，直接返回零数据行第一表",
                "不得先列暂定行再解释/撤销", "重复标题或重复表")
                .doesNotContain(selfReview, "暂定新增项", "requirement-doc", "requirement-unit", "Expected strict scan Markdown");
    }

    @Test
    void replacesUnknownPriorFailureWithGenericFeedbackWithoutLeakingItsRawText() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        MaterialInventoryUnit unit = new MaterialInventoryUnit("function-doc", "FUNCTION_LIST", "function-unit", 0, 1,
                "功能", 0, 2);
        String unsafeFailure = "https://internal.example/a?api_key=not-for-model C:\\private\\file documentId=raw-document unitId=raw-unit candidateIds=raw";
        AuditWorkClaim secondAttempt = claim("function-work", "function-doc", "function-unit", 1, "FEATURE_LIST_SCAN", 2,
                unsafeFailure);
        GenerationTaskRepository.FeatureAuditCounts completeCounts =
                new GenerationTaskRepository.FeatureAuditCounts(1, 1, 0, 1, 1, 1);

        when(repository.hasCompleteMaterialInventory("task-1", request.requirementScope())).thenReturn(true);
        when(repository.materialInventory("task-1")).thenReturn(List.of(unit));
        when(repository.claimNextAuditWork(eq("task-1"), any(), any())).thenReturn(Optional.of(secondAttempt), Optional.empty());
        when(repository.featureAuditCounts("task-1")).thenReturn(completeCounts);
        when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(result(scanResponse("""
                | 1 | 订单查询 | 功能项 | documentId=function-doc; unitId=function-unit; 来源文字 |
                """)));

        service.audit("task-1", request);

        ArgumentCaptor<FeatureReconciliationInvocation> invocation = ArgumentCaptor.forClass(FeatureReconciliationInvocation.class);
        verify(knowledgeAgentPort).reconcileFeatures(invocation.capture());
        assertThat(invocation.getValue().prompt()).contains("固定格式基线", "精确两张 Markdown 表")
                .doesNotContain("本次重点", unsafeFailure, "https://internal.example", "not-for-model", "C:\\private\\file", "raw-document", "raw-unit", "candidateIds=raw");
    }

    @Test
    void usesGenericFeedbackWhenAReclaimedAttemptHasNullOrBlankFailureSummary() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        MaterialInventoryUnit unit = new MaterialInventoryUnit("function-doc", "FUNCTION_LIST", "function-unit", 0, 1,
                "功能", 0, 2);
        AuditWorkClaim nullSummaryAttempt = claim("function-work-null", "function-doc", "function-unit", 1, "FEATURE_LIST_SCAN", 2, null);
        AuditWorkClaim blankSummaryAttempt = claim("function-work-blank", "function-doc", "function-unit", 1, "FEATURE_LIST_SCAN", 2, "  ");
        GenerationTaskRepository.FeatureAuditCounts completeCounts =
                new GenerationTaskRepository.FeatureAuditCounts(2, 2, 0, 2, 2, 2);

        when(repository.hasCompleteMaterialInventory("task-1", request.requirementScope())).thenReturn(true);
        when(repository.materialInventory("task-1")).thenReturn(List.of(unit));
        when(repository.claimNextAuditWork(eq("task-1"), any(), any())).thenReturn(Optional.of(nullSummaryAttempt),
                Optional.of(blankSummaryAttempt), Optional.empty());
        when(repository.featureAuditCounts("task-1")).thenReturn(completeCounts);
        when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(
                result(scanResponse("""
                        | 1 | 订单查询 | 功能项 | documentId=function-doc; unitId=function-unit; 来源文字 |
                        """)),
                result(scanResponse("""
                        | 1 | 订单查询 | 功能项 | documentId=function-doc; unitId=function-unit; 来源文字 |
                        """)));

        service.audit("task-1", request);

        ArgumentCaptor<FeatureReconciliationInvocation> invocations = ArgumentCaptor.forClass(FeatureReconciliationInvocation.class);
        verify(knowledgeAgentPort, times(2)).reconcileFeatures(invocations.capture());
        assertThat(invocations.getAllValues()).allSatisfy(invocation -> assertThat(invocation.prompt())
                .contains("固定格式基线", "精确两张 Markdown 表").doesNotContain("本次重点"));
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
    void failsFastAfterSkillPreparationFailureWithoutClaimingTheNextAuditWork() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        MaterialInventoryUnit unit = new MaterialInventoryUnit("function-doc", "FUNCTION_LIST", "function-unit", 0, 1, "功能", 0, 2);
        AuditWorkClaim claim = claim("function-work", "function-doc", "function-unit", 1, "FEATURE_LIST_SCAN");
        AuditWorkClaim laterClaim = claim("function-work-later", "function-doc", "function-unit", 1, "FEATURE_LIST_SCAN");

        when(repository.hasCompleteMaterialInventory("task-1", request.requirementScope())).thenReturn(true);
        when(repository.materialInventory("task-1")).thenReturn(List.of(unit));
        when(repository.claimNextAuditWork(eq("task-1"), any(), any())).thenReturn(Optional.of(claim), Optional.of(laterClaim), Optional.empty());
        doThrow(new KnowledgeAgentSkillPreparationException("Skill unavailable", false, null))
                .when(knowledgeAgentPort).prepareReconciliationSession(any());
        assertThatThrownBy(() -> service.audit("task-1", request))
                .isInstanceOf(KnowledgeAgentSkillPreparationException.class)
                .hasMessageContaining("Skill unavailable");
        verify(repository).failAuditWork(eq(claim), eq("Skill unavailable"), eq(false));
        verify(repository, times(1)).claimNextAuditWork(eq("task-1"), any(), any());
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
        return claim(workId, documentId, unitId, pass, stage, 1);
    }

    private static AuditWorkClaim claim(String workId, String documentId, String unitId, int pass, String stage, int attemptNumber) {
        return claim(workId, documentId, unitId, pass, stage, attemptNumber, null);
    }

    private static AuditWorkClaim claim(
            String workId, String documentId, String unitId, int pass, String stage, int attemptNumber, String previousFailureSummary) {
        return new AuditWorkClaim(workId, workId + "-attempt-" + attemptNumber, attemptNumber, "task-1", documentId, unitId,
                pass, stage, Instant.now().plusSeconds(30), previousFailureSummary);
    }

    private static KnowledgeAgentInvocationResult result(String markdown) {
        return new KnowledgeAgentInvocationResult("session", List.of(), markdown);
    }

    private static String retryFeedback(String prompt) {
        String marker = "重领纠正要求：";
        int start = prompt.lastIndexOf(marker);
        assertThat(start).isGreaterThanOrEqualTo(0);
        return prompt.substring(start + marker.length());
    }

    private static String secondHeadingFailureResponse(String selfReview, String repeatedTable) {
        return """
                ## 需求与功能清单审查发现
                | 序号 | 对象/功能点 | 问题分类 | 证据对照 |
                |---|---|---|---|
                | 1 | 暂定新增项 | 功能项 | documentId=requirement-doc; unitId=requirement-unit; 来源文字 |

                %s
                %s
                | 序号 | 对象/功能点 | 问题分类 | 证据对照 |
                |---|---|---|---|
                ## 测试用例
                | 用例名称 | 功能模块 | 前提约束 | 执行步骤 | 预期结果 | 对应需求内容 |
                |---|---|---|---|---|---|
                """.formatted(selfReview, repeatedTable);
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

    private static FeatureSourceCandidate candidate(int sequence) {
        String candidateId = "candidate-%02d".formatted(sequence);
        return new FeatureSourceCandidate(candidateId, FeatureCandidateKind.REQUIREMENT, "requirement-doc", "requirement-unit",
                1, sequence, "功能" + sequence, "功能项", "documentId=requirement-doc; unitId=requirement-unit", 1, sequence);
    }

    private static String boundedReconciliationResponse(
            List<FeatureSourceCandidate> targets, String groupedFirstId, String groupedLastId) {
        StringBuilder rows = new StringBuilder();
        for (FeatureSourceCandidate target : targets) {
            boolean grouped = target.occurrenceId().equals(groupedFirstId) || target.occurrenceId().equals(groupedLastId);
            rows.append("| ").append(target.modelSequence()).append(" | ")
                    .append(grouped ? "跨页归组功能" : target.featureText()).append(" | 匹配 | candidateIds=")
                    .append(target.occurrenceId()).append("; groupAnchorId=")
                    .append(grouped ? groupedFirstId : target.occurrenceId())
                    .append("; documentId=").append(target.documentId()).append("; unitId=").append(target.unitId())
                    .append(" |\n");
        }
        return scanResponse(rows.toString());
    }

    private static void assertRejectedFinalReconciliation(List<FeatureSourceCandidate> candidates, String invalidResponse) {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        when(repository.hasCompleteMaterialInventory("task-1", request.requirementScope())).thenReturn(true);
        when(repository.materialInventory("task-1")).thenReturn(List.of());
        when(repository.claimNextAuditWork(eq("task-1"), any(), any())).thenReturn(Optional.empty());
        when(repository.featureSourceCandidates("task-1")).thenReturn(candidates);
        when(repository.featureAuditCounts("task-1")).thenReturn(
                new GenerationTaskRepository.FeatureAuditCounts(1, 1, 0, candidates.size(), 0, 0));
        when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(result(invalidResponse), result(invalidResponse), result(invalidResponse));

        assertThatThrownBy(() -> service.audit("task-1", request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Final reconciliation page did not meet the strict contract after three attempts");
        verify(knowledgeAgentPort, times(3)).reconcileFeatures(any());
        verify(repository, never()).persistFeatureReviewConclusions(any(), any());
    }

    private static CreateGenerationTaskRequest request() {
        return new CreateGenerationTaskRequest(GenerationTaskMode.ALL, "all", FewShotPolicy.NONE, "1", "1", "audit-agent",
                new RequirementScope("requirement-kb", "system", "version", "admission", "project", List.of(
                        new RequirementDocumentCoordinate("function-doc"), new RequirementDocumentCoordinate("requirement-doc"))),
                new ExampleScope("example-kb", List.of("example-doc")), "requirements_spec", "audit");
    }
}
