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
                "默认每个目标 candidateId 都必须令 groupAnchorId 等于自身 candidateId",
                "仅当对象/功能点和问题分类与既有 anchor 行逐字完全相同时，才允许复用更早的 groupAnchorId",
                "只要任一不同，必须 self-anchor",
                "不得因为同一 unitId、documentId、大模块或问题分类而批量复用 groupAnchorId",
                "按 NFKC、首尾 strip、连续空白折叠成一个空格并转为小写后相同的任一业务路径",
                "禁止将同一路径 self-anchor 成多个结论",
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
                result(boundedReconciliationResponse(candidates.subList(0, 8), "candidate-01", "candidate-17")),
                result(boundedReconciliationResponse(candidates.subList(8, 16), "candidate-01", "candidate-17")),
                result(boundedReconciliationResponse(candidates.subList(16, 17), "candidate-01", "candidate-17")));

        FeatureAuditResult audit = service.audit("task-1", request);

        assertThat(audit.complete()).isTrue();
        ArgumentCaptor<FeatureReconciliationInvocation> invocations = ArgumentCaptor.forClass(FeatureReconciliationInvocation.class);
        verify(knowledgeAgentPort, times(3)).reconcileFeatures(invocations.capture());
        assertThat(invocations.getAllValues()).allSatisfy(invocation -> assertThat(invocation.prompt())
                .contains("candidate-01", "candidate-17", "全量候选项仅作比较上下文"));
        assertThat(invocations.getAllValues()).allSatisfy(invocation -> assertThat(targetCandidateIds(invocation.prompt()))
                .hasSizeLessThanOrEqualTo(8));
        assertThat(invocations.getAllValues().get(2).prompt()).contains(
                "已接受的跨页结论", "candidateId=candidate-01", "groupAnchorId=candidate-01", "businessPath=跨页归组功能",
                "逐字复制其 issueCategory 与 businessPath");
        ArgumentCaptor<List<FeatureReviewConclusion>> persisted = ArgumentCaptor.forClass(List.class);
        verify(repository).persistFeatureReviewConclusions(eq("task-1"), persisted.capture());
        assertThat(persisted.getValue()).hasSize(16);
        assertThat(persisted.getValue()).anySatisfy(conclusion -> assertThat(conclusion.candidateIds())
                .containsExactly("candidate-01", "candidate-17"));
    }

    @Test
    void projectsOneRepresentativeConclusionToEverySameNormalizedFeatureTextMember() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        List<FeatureSourceCandidate> candidates = new java.util.ArrayList<>(java.util.stream.IntStream.rangeClosed(1, 8)
                .mapToObj(FeatureAuditServiceTest::candidate).toList());
        FeatureSourceCandidate first = candidates.get(0);
        first = new FeatureSourceCandidate(first.occurrenceId(), first.kind(), first.documentId(), first.unitId(),
                first.ordinal(), first.modelSequence(), first.featureText(), first.category(),
                "documentId=requirement-doc; unitId=requirement-unit; 代表成员持久证据", first.passNumber(),
                first.sourceRowPosition());
        candidates.set(0, first);
        candidates.set(7, new FeatureSourceCandidate("candidate-08", FeatureCandidateKind.REQUIREMENT,
                "requirement-doc-duplicate", "requirement-unit-duplicate", 1, 8, first.featureText(), "功能项",
                "documentId=requirement-doc-duplicate; unitId=requirement-unit-duplicate; 重复成员持久证据", 1, 8));

        when(repository.hasCompleteMaterialInventory("task-1", request.requirementScope())).thenReturn(true);
        when(repository.materialInventory("task-1")).thenReturn(List.of());
        when(repository.claimNextAuditWork(eq("task-1"), any(), any())).thenReturn(Optional.empty());
        when(repository.featureSourceCandidates("task-1")).thenReturn(candidates);
        when(repository.featureAuditCounts("task-1")).thenReturn(
                new GenerationTaskRepository.FeatureAuditCounts(1, 1, 0, 8, 0, 0),
                new GenerationTaskRepository.FeatureAuditCounts(1, 1, 0, 8, 7, 8));
        StringBuilder representativeRows = new StringBuilder();
        for (FeatureSourceCandidate representative : candidates.subList(0, 7)) {
            String row = finalReconciliationRow(representative, representative.featureText(), "匹配",
                    representative.occurrenceId());
            if (representative.occurrenceId().equals("candidate-01")) {
                row = row.replace("unitId=requirement-unit |", "unitId=requirement-unit; 代表模型专属证据 |");
            }
            representativeRows.append(row);
        }
        when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(result(scanResponse(representativeRows.toString())));

        assertThat(service.audit("task-1", request).complete()).isTrue();

        ArgumentCaptor<FeatureReconciliationInvocation> invocation = ArgumentCaptor.forClass(FeatureReconciliationInvocation.class);
        verify(knowledgeAgentPort).reconcileFeatures(invocation.capture());
        assertThat(targetCandidateIds(invocation.getValue().prompt())).containsExactly(
                "candidateId=candidate-01", "candidateId=candidate-02", "candidateId=candidate-03", "candidateId=candidate-04",
                "candidateId=candidate-05", "candidateId=candidate-06", "candidateId=candidate-07");
        ArgumentCaptor<List<FeatureReviewConclusion>> persisted = ArgumentCaptor.forClass(List.class);
        verify(repository).persistFeatureReviewConclusions(eq("task-1"), persisted.capture());
        assertThat(persisted.getValue()).hasSize(7);
        assertThat(persisted.getValue().get(0).candidateIds()).containsExactly("candidate-01", "candidate-08");
        assertThat(persisted.getValue()).anySatisfy(conclusion -> {
            assertThat(conclusion.candidateIds()).containsExactly("candidate-01", "candidate-08");
            assertThat(conclusion.evidenceText()).contains("代表成员持久证据", "重复成员持久证据")
                    .doesNotContain("代表模型专属证据")
                    .doesNotContain("candidateIds=candidate-08; groupAnchorId=candidate-01; 代表成员持久证据");
            assertThat(conclusion.evidenceText()).contains("documentId=requirement-doc-duplicate",
                    "unitId=requirement-unit-duplicate", "candidateIds=candidate-08", "groupAnchorId=candidate-01",
                    "candidateIds=candidate-08; groupAnchorId=candidate-01; 重复成员持久证据");
        });
    }

    @Test
    void compensatesOnlyMissingTargetsWithOneStrictCallPerRepresentativeAfterPageRetriesAreExhausted() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        List<FeatureSourceCandidate> candidates = java.util.stream.IntStream.rangeClosed(1, 8)
                .mapToObj(FeatureAuditServiceTest::candidate).toList();
        StringBuilder incompleteRows = new StringBuilder();
        for (FeatureSourceCandidate candidate : candidates.subList(0, 7)) {
            incompleteRows.append(finalReconciliationRow(candidate, candidate.featureText(), "匹配", candidate.occurrenceId()));
        }

        when(repository.hasCompleteMaterialInventory("task-1", request.requirementScope())).thenReturn(true);
        when(repository.materialInventory("task-1")).thenReturn(List.of());
        when(repository.claimNextAuditWork(eq("task-1"), any(), any())).thenReturn(Optional.empty());
        when(repository.featureSourceCandidates("task-1")).thenReturn(candidates);
        when(repository.featureAuditCounts("task-1")).thenReturn(
                new GenerationTaskRepository.FeatureAuditCounts(1, 1, 0, 8, 0, 0),
                new GenerationTaskRepository.FeatureAuditCounts(1, 1, 0, 8, 8, 8));
        when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(result(scanResponse(incompleteRows.toString())),
                result(scanResponse(incompleteRows.toString())), result(scanResponse(incompleteRows.toString())),
                result(scanResponse(finalReconciliationRow(candidates.get(0), candidates.get(0).featureText(), "匹配", "candidate-01"))),
                result(scanResponse(finalReconciliationRow(candidates.get(1), candidates.get(1).featureText(), "匹配", "candidate-02"))),
                result(scanResponse(finalReconciliationRow(candidates.get(2), candidates.get(2).featureText(), "匹配", "candidate-03"))),
                result(scanResponse(finalReconciliationRow(candidates.get(3), candidates.get(3).featureText(), "匹配", "candidate-04"))),
                result(scanResponse(finalReconciliationRow(candidates.get(4), candidates.get(4).featureText(), "匹配", "candidate-05"))),
                result(scanResponse(finalReconciliationRow(candidates.get(5), candidates.get(5).featureText(), "匹配", "candidate-06"))),
                result(scanResponse(finalReconciliationRow(candidates.get(6), candidates.get(6).featureText(), "匹配", "candidate-07"))),
                result(scanResponse(finalReconciliationRow(candidates.get(7), candidates.get(7).featureText(), "匹配", "candidate-08"))));

        assertThat(service.audit("task-1", request).complete()).isTrue();
        ArgumentCaptor<FeatureReconciliationInvocation> invocations = ArgumentCaptor.forClass(FeatureReconciliationInvocation.class);
        verify(knowledgeAgentPort, times(11)).reconcileFeatures(invocations.capture());
        assertThat(invocations.getAllValues().subList(3, 11))
                .allSatisfy(invocation -> assertThat(targetCandidateIds(invocation.prompt())).hasSize(1));
        verify(repository).persistFeatureReviewConclusions(eq("task-1"), any());
    }

    @Test
    // [Req-ID]: REQ-BFA-007
    void individuallyRechecksEveryRepresentativeAfterThreeBusinessPathStructureFailures() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        List<FeatureSourceCandidate> candidates = java.util.stream.IntStream.rangeClosed(1, 8)
                .mapToObj(FeatureAuditServiceTest::candidate).toList();
        String invalid = scanResponse(finalReconciliationRow(candidates.get(0), candidates.get(0).featureText() + "<br>章节一",
                "匹配", candidates.get(0).occurrenceId()) + candidates.subList(1, 8).stream()
                .map(candidate -> finalReconciliationRow(candidate, candidate.featureText(), "匹配", candidate.occurrenceId()))
                .collect(java.util.stream.Collectors.joining()));

        stubSingleBatchAudit(repository, request, candidates);
        KnowledgeAgentInvocationResult[] responses = individualRecheckSequence(candidates,
                result(invalid), result(invalid), result(invalid));
        when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(responses[0],
                java.util.Arrays.copyOfRange(responses, 1, responses.length));

        assertThat(service.audit("task-1", request).complete()).isTrue();
        ArgumentCaptor<FeatureReconciliationInvocation> invocations = ArgumentCaptor.forClass(FeatureReconciliationInvocation.class);
        verify(knowledgeAgentPort, times(11)).reconcileFeatures(invocations.capture());
        assertThat(invocations.getAllValues().subList(3, 11))
                .allSatisfy(invocation -> assertThat(targetCandidateIds(invocation.prompt())).hasSize(1));
        verify(repository).persistFeatureReviewConclusions(eq("task-1"), any());
    }

    @Test
    // [Req-ID]: REQ-BFA-007
    void individuallyRechecksAfterMixedApprovedPageFailureCategories() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        List<FeatureSourceCandidate> candidates = java.util.stream.IntStream.rangeClosed(1, 8)
                .mapToObj(FeatureAuditServiceTest::candidate).toList();
        String missing = scanResponse(candidates.subList(0, 7).stream()
                .map(candidate -> finalReconciliationRow(candidate, candidate.featureText(), "匹配", candidate.occurrenceId()))
                .collect(java.util.stream.Collectors.joining()));
        String complete = candidates.stream()
                .map(candidate -> finalReconciliationRow(candidate, candidate.featureText(), "匹配", candidate.occurrenceId()))
                .collect(java.util.stream.Collectors.joining());
        String wrongBinding = scanResponse(complete.replace("documentId=requirement-doc; unitId=requirement-unit",
                "documentId=wrong-doc; unitId=wrong-unit"));
        String invalidPath = scanResponse(finalReconciliationRow(candidates.get(0), candidates.get(0).featureText() + "<br>章节一",
                "匹配", candidates.get(0).occurrenceId()) + complete.substring(complete.indexOf("\n") + 1));

        stubSingleBatchAudit(repository, request, candidates);
        KnowledgeAgentInvocationResult[] responses = individualRecheckSequence(candidates,
                result(missing), result(wrongBinding), result(invalidPath));
        when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(responses[0],
                java.util.Arrays.copyOfRange(responses, 1, responses.length));

        assertThat(service.audit("task-1", request).complete()).isTrue();
        verify(knowledgeAgentPort, times(11)).reconcileFeatures(any());
        verify(repository).persistFeatureReviewConclusions(eq("task-1"), any());
    }

    @Test
    void failsClosedWhenOneMissingTargetCompensationExhaustsItsOwnRetryBudget() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        List<FeatureSourceCandidate> candidates = java.util.stream.IntStream.rangeClosed(1, 8)
                .mapToObj(FeatureAuditServiceTest::candidate).toList();
        StringBuilder incompleteRows = new StringBuilder();
        for (FeatureSourceCandidate candidate : candidates.subList(0, 7)) {
            incompleteRows.append(finalReconciliationRow(candidate, candidate.featureText(), "匹配", candidate.occurrenceId()));
        }

        when(repository.hasCompleteMaterialInventory("task-1", request.requirementScope())).thenReturn(true);
        when(repository.materialInventory("task-1")).thenReturn(List.of());
        when(repository.claimNextAuditWork(eq("task-1"), any(), any())).thenReturn(Optional.empty());
        when(repository.featureSourceCandidates("task-1")).thenReturn(candidates);
        when(repository.featureAuditCounts("task-1")).thenReturn(
                new GenerationTaskRepository.FeatureAuditCounts(1, 1, 0, 8, 0, 0));
        when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(result(scanResponse(incompleteRows.toString())),
                result(scanResponse(incompleteRows.toString())), result(scanResponse(incompleteRows.toString())),
                result(scanResponse("")), result(scanResponse("")), result(scanResponse("")));

        Throwable failure = catchThrowable(() -> service.audit("task-1", request));

        assertThat(failure).isInstanceOf(FinalReconciliationPageException.class);
        FinalReconciliationPageException pageFailure = (FinalReconciliationPageException) failure;
        assertThat(pageFailure.category()).isEqualTo(FinalReconciliationPageException.Category.MISSING_TARGET);
        assertThat(pageFailure.safeSummary()).isEqualTo("最终双向核对第 1/1 个功能审核批次第 1/8 个目标连续 3 次未通过：目标覆盖不完整");
        verify(knowledgeAgentPort, times(6)).reconcileFeatures(any());
        verify(repository, never()).persistFeatureReviewConclusions(any(), any());
    }

    @Test
    void doesNotIndividuallyRecheckWhenAnyPageAttemptUsesANonApprovedCategory() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        List<FeatureSourceCandidate> candidates = java.util.stream.IntStream.rangeClosed(1, 8)
                .mapToObj(FeatureAuditServiceTest::candidate).toList();
        StringBuilder incompleteRows = new StringBuilder();
        for (FeatureSourceCandidate candidate : candidates.subList(0, 7)) {
            incompleteRows.append(finalReconciliationRow(candidate, candidate.featureText(), "匹配", candidate.occurrenceId()));
        }

        when(repository.hasCompleteMaterialInventory("task-1", request.requirementScope())).thenReturn(true);
        when(repository.materialInventory("task-1")).thenReturn(List.of());
        when(repository.claimNextAuditWork(eq("task-1"), any(), any())).thenReturn(Optional.empty());
        when(repository.featureSourceCandidates("task-1")).thenReturn(candidates);
        when(repository.featureAuditCounts("task-1")).thenReturn(
                new GenerationTaskRepository.FeatureAuditCounts(1, 1, 0, 8, 0, 0));
        when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(result("not Markdown"),
                result(scanResponse(incompleteRows.toString())), result(scanResponse(incompleteRows.toString())));

        Throwable failure = catchThrowable(() -> service.audit("task-1", request));

        assertThat(failure).isInstanceOf(FinalReconciliationPageException.class);
        assertThat(((FinalReconciliationPageException) failure).category())
                .isEqualTo(FinalReconciliationPageException.Category.MISSING_TARGET);
        verify(knowledgeAgentPort, times(3)).reconcileFeatures(any());
        verify(repository, never()).persistFeatureReviewConclusions(any(), any());
    }

    @Test
    // [Req-ID]: REQ-BFA-007
    void neverIndividuallyRechecksAnyNonApprovedFinalReconciliationCategory() {
        for (String failure : List.of(
                "Expected strict final reconciliation Markdown with a valid header",
                "Each groupAnchorId must reference a retained global candidate",
                "Each normalized business path must retain one groupAnchorId and conclusion type",
                "An unrecognized fixed final reconciliation contract")) {
            GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
            KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
            FeatureConclusionMarkdownParser parser = mock(FeatureConclusionMarkdownParser.class);
            FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort, new FeatureCandidateScanner(),
                    new RequirementCandidateScanner(), parser);
            CreateGenerationTaskRequest request = request();
            List<FeatureSourceCandidate> candidates = List.of(candidate(1));
            stubSingleBatchAudit(repository, request, candidates);
            when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(result("ignored"));
            when(parser.parse(any(), any())).thenThrow(new IllegalArgumentException(failure));

            assertThatThrownBy(() -> service.audit("task-1", request))
                    .isInstanceOf(FinalReconciliationPageException.class);
            verify(knowledgeAgentPort, times(3)).reconcileFeatures(any());
            verify(repository, never()).persistFeatureReviewConclusions(any(), any());
        }
    }

    @Test
    void rejectsAChapterNumberAppendedToANonSplitBusinessPathWithPreciseRetryFeedback() {
        FeatureSourceCandidate candidate = candidate(1);
        String invalidResponse = scanResponse(finalReconciliationRow(
                candidate, candidate.featureText() + "<br>4.2.2.3", "未发现问题", candidate.occurrenceId()));

        assertRejectedFinalReconciliation(List.of(candidate), invalidResponse,
                "章节号、条款号和目录编号只能写在证据对照列，绝不能写入对象/功能点");
    }

    @Test
    void acceptsTheCorrectedSingleBusinessPathOnTheSecondAttemptWithoutSingletonCompensation() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        FeatureSourceCandidate candidate = candidate(1);
        String invalidResponse = scanResponse(finalReconciliationRow(
                candidate, candidate.featureText() + "<br>4.2.2.3", "未发现问题", candidate.occurrenceId()));
        String correctedResponse = scanResponse(finalReconciliationRow(
                candidate, candidate.featureText(), "未发现问题", candidate.occurrenceId()));

        when(repository.hasCompleteMaterialInventory("task-1", request.requirementScope())).thenReturn(true);
        when(repository.materialInventory("task-1")).thenReturn(List.of());
        when(repository.claimNextAuditWork(eq("task-1"), any(), any())).thenReturn(Optional.empty());
        when(repository.featureSourceCandidates("task-1")).thenReturn(List.of(candidate));
        when(repository.featureAuditCounts("task-1")).thenReturn(
                new GenerationTaskRepository.FeatureAuditCounts(1, 1, 0, 1, 0, 0),
                new GenerationTaskRepository.FeatureAuditCounts(1, 1, 0, 1, 1, 1));
        when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(result(invalidResponse), result(correctedResponse));

        assertThat(service.audit("task-1", request).complete()).isTrue();

        ArgumentCaptor<FeatureReconciliationInvocation> invocations = ArgumentCaptor.forClass(FeatureReconciliationInvocation.class);
        verify(knowledgeAgentPort, times(2)).reconcileFeatures(invocations.capture());
        assertThat(invocations.getAllValues().get(1).prompt()).contains(
                "章节号、条款号和目录编号只能写在证据对照列，绝不能写入对象/功能点",
                "不得复制证据中 <br> 后的章节号或条款号");
        ArgumentCaptor<List<FeatureReviewConclusion>> persisted = ArgumentCaptor.forClass(List.class);
        verify(repository).persistFeatureReviewConclusions(eq("task-1"), persisted.capture());
        assertThat(persisted.getValue()).singleElement().satisfies(conclusion -> {
            assertThat(conclusion.explanation()).isEqualTo(candidate.featureText());
            assertThat(conclusion.candidateIds()).containsExactly(candidate.occurrenceId());
            assertThat(conclusion.evidenceText()).contains("groupAnchorId=" + candidate.occurrenceId());
        });
    }

    @Test
    void failsClosedWhenAGroupedRepresentativeDoesNotBindItsOwnMaterialCoordinate() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        List<FeatureSourceCandidate> candidates = new java.util.ArrayList<>(java.util.stream.IntStream.rangeClosed(1, 8)
                .mapToObj(FeatureAuditServiceTest::candidate).toList());
        candidates.set(7, new FeatureSourceCandidate("candidate-08", FeatureCandidateKind.REQUIREMENT,
                "requirement-doc-duplicate", "requirement-unit-duplicate", 1, 8, candidates.get(0).featureText(), "功能项",
                "documentId=requirement-doc-duplicate; unitId=requirement-unit-duplicate", 1, 8));
        StringBuilder rows = new StringBuilder();
        for (FeatureSourceCandidate representative : candidates.subList(0, 7)) {
            rows.append(finalReconciliationRow(representative, representative.featureText(), "匹配", representative.occurrenceId()));
        }
        String wrongBinding = scanResponse(rows.toString()).replace("documentId=requirement-doc; unitId=requirement-unit",
                "documentId=wrong-document; unitId=wrong-unit");

        when(repository.hasCompleteMaterialInventory("task-1", request.requirementScope())).thenReturn(true);
        when(repository.materialInventory("task-1")).thenReturn(List.of());
        when(repository.claimNextAuditWork(eq("task-1"), any(), any())).thenReturn(Optional.empty());
        when(repository.featureSourceCandidates("task-1")).thenReturn(candidates);
        when(repository.featureAuditCounts("task-1")).thenReturn(
                new GenerationTaskRepository.FeatureAuditCounts(1, 1, 0, 8, 0, 0));
        when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(result(wrongBinding), result(wrongBinding), result(wrongBinding));

        Throwable failure = catchThrowable(() -> service.audit("task-1", request));
        assertThat(failure).isInstanceOf(FinalReconciliationPageException.class);
        verify(knowledgeAgentPort, times(6)).reconcileFeatures(any());
        verify(repository, never()).persistFeatureReviewConclusions(any(), any());
    }

    @Test
    void retriesARepresentativeBindingWithOnlyItsOwnTargetCoordinatesWhenSamePathNeighborHasDifferentCoordinates() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        List<FeatureSourceCandidate> candidates = new java.util.ArrayList<>(java.util.stream.IntStream.rangeClosed(1, 8)
                .mapToObj(FeatureAuditServiceTest::candidate).toList());
        FeatureSourceCandidate representative = new FeatureSourceCandidate("candidate-01", FeatureCandidateKind.REQUIREMENT,
                "representative-doc", "representative-unit", 1, 1, "同名功能", "功能项",
                "documentId=representative-doc; unitId=representative-unit; 代表证据", 1, 1);
        FeatureSourceCandidate neighbor = new FeatureSourceCandidate("candidate-08", FeatureCandidateKind.REQUIREMENT,
                "neighbor-doc", "neighbor-unit", 1, 8, "同名功能", "功能项",
                "documentId=neighbor-doc; unitId=neighbor-unit; 邻居证据", 1, 8);
        candidates.set(0, representative);
        candidates.set(7, neighbor);
        StringBuilder rows = new StringBuilder();
        for (FeatureSourceCandidate target : candidates.subList(0, 7)) {
            rows.append(finalReconciliationRow(target, target.featureText(), "匹配", target.occurrenceId()));
        }
        String neighborBoundResponse = scanResponse(rows.toString().replace(
                "documentId=representative-doc; unitId=representative-unit",
                "documentId=neighbor-doc; unitId=neighbor-unit"));
        String correctedResponse = scanResponse(rows.toString());

        when(repository.hasCompleteMaterialInventory("task-1", request.requirementScope())).thenReturn(true);
        when(repository.materialInventory("task-1")).thenReturn(List.of());
        when(repository.claimNextAuditWork(eq("task-1"), any(), any())).thenReturn(Optional.empty());
        when(repository.featureSourceCandidates("task-1")).thenReturn(candidates);
        when(repository.featureAuditCounts("task-1")).thenReturn(
                new GenerationTaskRepository.FeatureAuditCounts(1, 1, 0, 8, 0, 0),
                new GenerationTaskRepository.FeatureAuditCounts(1, 1, 0, 8, 7, 8));
        when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(result(neighborBoundResponse), result(correctedResponse));

        assertThat(service.audit("task-1", request).complete()).isTrue();

        ArgumentCaptor<FeatureReconciliationInvocation> invocations = ArgumentCaptor.forClass(FeatureReconciliationInvocation.class);
        verify(knowledgeAgentPort, times(2)).reconcileFeatures(invocations.capture());
        String targetBlock = invocations.getAllValues().get(0).prompt().substring(
                invocations.getAllValues().get(0).prompt().indexOf("本页目标候选："));
        assertThat(targetBlock).contains("candidateId=candidate-01; documentId=representative-doc; unitId=representative-unit");
        assertThat(targetBlock).doesNotContain("candidateId=candidate-08");
        assertThat(invocations.getAllValues().get(0).prompt()).contains(
                "禁止从全量候选上下文中同名或同路径的邻居复制 documentId 或 unitId");
        assertThat(invocations.getAllValues().get(1).prompt()).contains(
                "本页每个代表目标的 candidateId、documentId 和 unitId 必须逐字复制该代表目标绑定行",
                "禁止从全量候选上下文中同名或同路径的邻居复制 documentId 或 unitId");
        ArgumentCaptor<List<FeatureReviewConclusion>> persisted = ArgumentCaptor.forClass(List.class);
        verify(repository).persistFeatureReviewConclusions(eq("task-1"), persisted.capture());
        assertThat(persisted.getValue()).anySatisfy(conclusion -> {
            assertThat(conclusion.candidateIds()).containsExactly("candidate-01", "candidate-08");
            assertThat(conclusion.evidenceText()).contains("documentId=representative-doc", "unitId=representative-unit",
                    "candidateIds=candidate-01", "documentId=neighbor-doc", "unitId=neighbor-unit", "candidateIds=candidate-08");
        });
    }

    @Test
    void doesNotCompensateAfterAMissingTargetFailureIsFollowedByRepresentativeBindingFailures() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        List<FeatureSourceCandidate> candidates = java.util.stream.IntStream.rangeClosed(1, 8)
                .mapToObj(FeatureAuditServiceTest::candidate).toList();
        StringBuilder incompleteRows = new StringBuilder();
        for (FeatureSourceCandidate target : candidates.subList(0, 7)) {
            incompleteRows.append(finalReconciliationRow(target, target.featureText(), "匹配", target.occurrenceId()));
        }
        StringBuilder completeRows = new StringBuilder(incompleteRows);
        completeRows.append(finalReconciliationRow(candidates.get(7), candidates.get(7).featureText(), "匹配",
                candidates.get(7).occurrenceId()));
        String wrongBinding = scanResponse(completeRows.toString().replace(
                "documentId=requirement-doc; unitId=requirement-unit", "documentId=wrong-doc; unitId=wrong-unit"));

        when(repository.hasCompleteMaterialInventory("task-1", request.requirementScope())).thenReturn(true);
        when(repository.materialInventory("task-1")).thenReturn(List.of());
        when(repository.claimNextAuditWork(eq("task-1"), any(), any())).thenReturn(Optional.empty());
        when(repository.featureSourceCandidates("task-1")).thenReturn(candidates);
        when(repository.featureAuditCounts("task-1")).thenReturn(
                new GenerationTaskRepository.FeatureAuditCounts(1, 1, 0, 8, 0, 0));
        when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(result(scanResponse(incompleteRows.toString())),
                result(wrongBinding), result(wrongBinding));

        Throwable failure = catchThrowable(() -> service.audit("task-1", request));

        assertThat(failure).isInstanceOf(FinalReconciliationPageException.class);
        verify(knowledgeAgentPort, times(6)).reconcileFeatures(any());
        verify(repository, never()).persistFeatureReviewConclusions(any(), any());
    }

    @Test
    void doesNotProjectASplitRepresentativeOntoASingleRepeatedFeaturePath() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        List<FeatureSourceCandidate> candidates = new java.util.ArrayList<>(java.util.stream.IntStream.rangeClosed(1, 8)
                .mapToObj(FeatureAuditServiceTest::candidate).toList());
        candidates.set(7, new FeatureSourceCandidate("candidate-08", FeatureCandidateKind.REQUIREMENT,
                "requirement-doc-duplicate", "requirement-unit-duplicate", 1, 8, candidates.get(0).featureText(), "功能项",
                "documentId=requirement-doc-duplicate; unitId=requirement-unit-duplicate", 1, 8));
        StringBuilder rows = new StringBuilder();
        rows.append(finalReconciliationRow(candidates.get(0), candidates.get(0).featureText() + "<br>额外功能", "拆分",
                candidates.get(0).occurrenceId()));
        for (FeatureSourceCandidate representative : candidates.subList(1, 7)) {
            rows.append(finalReconciliationRow(representative, representative.featureText(), "匹配", representative.occurrenceId()));
        }
        String splitRepresentative = scanResponse(rows.toString());

        when(repository.hasCompleteMaterialInventory("task-1", request.requirementScope())).thenReturn(true);
        when(repository.materialInventory("task-1")).thenReturn(List.of());
        when(repository.claimNextAuditWork(eq("task-1"), any(), any())).thenReturn(Optional.empty());
        when(repository.featureSourceCandidates("task-1")).thenReturn(candidates);
        when(repository.featureAuditCounts("task-1")).thenReturn(
                new GenerationTaskRepository.FeatureAuditCounts(1, 1, 0, 8, 0, 0));
        when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(result(splitRepresentative), result(splitRepresentative),
                result(splitRepresentative));

        Throwable failure = catchThrowable(() -> service.audit("task-1", request));
        assertThat(failure).isInstanceOf(FinalReconciliationPageException.class);
        verify(knowledgeAgentPort, times(6)).reconcileFeatures(any());
        verify(repository, never()).persistFeatureReviewConclusions(any(), any());
    }

    @Test
    void retriesEveryCrossPageConclusionDriftWithItsExactAcceptedBinding() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        List<FeatureSourceCandidate> candidates = java.util.stream.IntStream.rangeClosed(1, 16)
                .mapToObj(FeatureAuditServiceTest::candidate).toList();

        when(repository.hasCompleteMaterialInventory("task-1", request.requirementScope())).thenReturn(true);
        when(repository.materialInventory("task-1")).thenReturn(List.of());
        when(repository.claimNextAuditWork(eq("task-1"), any(), any())).thenReturn(Optional.empty());
        when(repository.featureSourceCandidates("task-1")).thenReturn(candidates);
        when(repository.featureAuditCounts("task-1")).thenReturn(
                new GenerationTaskRepository.FeatureAuditCounts(1, 1, 0, 16, 0, 0),
                new GenerationTaskRepository.FeatureAuditCounts(1, 1, 0, 16, 8, 16));

        StringBuilder acceptedFirstPage = new StringBuilder();
        StringBuilder driftedSecondPage = new StringBuilder();
        StringBuilder correctedSecondPage = new StringBuilder();
        for (int index = 0; index < 8; index++) {
            FeatureSourceCandidate accepted = candidates.get(index);
            FeatureSourceCandidate target = candidates.get(index + 8);
            String path = "共享业务路径" + (index + 1);
            acceptedFirstPage.append(finalReconciliationRow(accepted, path, "匹配", accepted.occurrenceId()));
            driftedSecondPage.append(finalReconciliationRow(target, path, "证据不足", target.occurrenceId()));
            correctedSecondPage.append(finalReconciliationRow(target, path, "匹配", accepted.occurrenceId()));
        }
        when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(
                result(scanResponse(acceptedFirstPage.toString())),
                result(scanResponse(driftedSecondPage.toString())),
                result(scanResponse(correctedSecondPage.toString())));

        assertThat(service.audit("task-1", request).complete()).isTrue();

        ArgumentCaptor<FeatureReconciliationInvocation> invocations = ArgumentCaptor.forClass(FeatureReconciliationInvocation.class);
        verify(knowledgeAgentPort, times(3)).reconcileFeatures(invocations.capture());
        String retryPrompt = invocations.getAllValues().get(2).prompt();
        for (int index = 0; index < 8; index++) {
            assertThat(retryPrompt).contains("targetCandidateId=candidate-%02d".formatted(index + 9),
                    "issueCategory=匹配; businessPath=共享业务路径" + (index + 1),
                    "groupAnchorId=candidate-%02d".formatted(index + 1));
        }
        ArgumentCaptor<List<FeatureReviewConclusion>> persisted = ArgumentCaptor.forClass(List.class);
        verify(repository).persistFeatureReviewConclusions(eq("task-1"), persisted.capture());
        assertThat(persisted.getValue()).hasSize(8);
    }

    @Test
    // [Req-ID]: REQ-BFA-007
    void retriesEveryCurrentTargetThatMisusesADifferentAcceptedAnchorWithItsOwnSelfAnchor() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        List<FeatureSourceCandidate> candidates = java.util.stream.IntStream.rangeClosed(1, 16)
                .mapToObj(FeatureAuditServiceTest::candidate).toList();
        stubTwoPageAudit(repository, request, candidates);

        when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(
                result(acceptedCrossPageAnchorRows(candidates)), result(misusedAcceptedAnchorRows(candidates)),
                result(correctedSelfAnchorRows(candidates)));

        assertThat(service.audit("task-1", request).complete()).isTrue();

        ArgumentCaptor<FeatureReconciliationInvocation> invocations = ArgumentCaptor.forClass(FeatureReconciliationInvocation.class);
        verify(knowledgeAgentPort, times(3)).reconcileFeatures(invocations.capture());
        String feedback = crossPageAnchorConflictFeedback(invocations.getAllValues().get(2).prompt());
        for (int index = 0; index < 4; index++) {
            assertThat(feedback).contains("targetCandidateId=candidate-%02d".formatted(index + 9),
                    "rejectedGroupAnchorId=candidate-%02d".formatted(index + 1),
                    "requiredSelfAnchorId=candidate-%02d".formatted(index + 9));
        }
        assertThat(feedback).contains("若不逐字复用已接受先例区的 issueCategory 与完整 businessPath",
                "groupAnchorId 必须逐字复制 requiredSelfAnchorId 的实际值", "若确属旧语义组")
                .doesNotContain("issueCategory=", "businessPath=");
        assertThat(feedback).doesNotContain("targetCandidateId=candidate-13", "targetCandidateId=candidate-14",
                "targetCandidateId=candidate-15", "targetCandidateId=candidate-16", "未接收路径", "证据不足");
        verify(repository).persistFeatureReviewConclusions(eq("task-1"), any());
    }

    @Test
    // [Req-ID]: REQ-BFA-007
    void doesNotAddCrossPageAnchorMisuseFeedbackWhenTheAcceptedAnchorTypeAndCompletePathMatch() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        List<FeatureSourceCandidate> candidates = java.util.stream.IntStream.rangeClosed(1, 9)
                .mapToObj(FeatureAuditServiceTest::candidate).toList();
        stubTwoPageAudit(repository, request, candidates);
        String accepted = acceptedCrossPageAnchorRows(candidates);
        FeatureSourceCandidate current = candidates.get(8);
        String matchingOldGroup = scanResponse(finalReconciliationRow(current, "已接受路径1", "匹配", "candidate-01"));
        when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(result(accepted), result(matchingOldGroup));

        assertThat(service.audit("task-1", request).complete()).isTrue();

        ArgumentCaptor<FeatureReconciliationInvocation> invocations = ArgumentCaptor.forClass(FeatureReconciliationInvocation.class);
        verify(knowledgeAgentPort, times(2)).reconcileFeatures(invocations.capture());
        assertThat(invocations.getAllValues().get(1).prompt()).doesNotContain("本批次跨批 anchor 误用目标");
        verify(repository).persistFeatureReviewConclusions(eq("task-1"), any());
    }

    @Test
    // [Req-ID]: REQ-BFA-007
    void failsClosedWithoutSingletonRechecksOrPersistenceAfterThreeCrossPageAnchorMisuses() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        List<FeatureSourceCandidate> candidates = java.util.stream.IntStream.rangeClosed(1, 16)
                .mapToObj(FeatureAuditServiceTest::candidate).toList();
        stubTwoPageAudit(repository, request, candidates);
        String misuse = misusedAcceptedAnchorRows(candidates);
        when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(
                result(acceptedCrossPageAnchorRows(candidates)), result(misuse), result(misuse), result(misuse));

        assertThatThrownBy(() -> service.audit("task-1", request))
                .isInstanceOf(FinalReconciliationPageException.class)
                .satisfies(failure -> assertThat(((FinalReconciliationPageException) failure).category())
                        .isEqualTo(FinalReconciliationPageException.Category.ANCHOR_CONFLICT));

        // One accepted preceding batch plus exactly three failed current-batch attempts proves zero singleton rechecks.
        verify(knowledgeAgentPort, times(4)).reconcileFeatures(any());
        verify(repository, never()).persistFeatureReviewConclusions(any(), any());
    }

    @Test
    void acceptsGreaterThanAsAPlainTextHierarchySeparatorInFinalReconciliation() {
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
        when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(result(scanResponse(
                finalReconciliationRow(candidates.get(0), "信息中心 > 月度例会 > 查看", "匹配", candidates.get(0).occurrenceId())
                        + finalReconciliationRow(candidates.get(1), "信息中心 > 月度例会 > 编辑", "匹配", candidates.get(1).occurrenceId()))));

        assertThat(service.audit("task-1", request).complete()).isTrue();

        verify(repository).persistFeatureReviewConclusions(eq("task-1"), any());
    }

    @Test
    void retriesARejectedAnchoredGroupBeforeOneAtomicPersist() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        List<FeatureSourceCandidate> candidates = List.of(candidate(1), candidate(2), candidate(3), candidate(4));

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
        assertThat(invocations.getAllValues().get(1).prompt()).contains(
                "重试纠正要求：",
                "固定格式基线",
                "groupAnchorId",
                "默认每个目标 candidateId 都必须令 groupAnchorId 等于自身 candidateId",
                "仅当对象/功能点和问题分类与既有 anchor 行逐字完全相同时，才允许复用更早的 groupAnchorId",
                "只要任一不同，必须 self-anchor",
                "不得因为同一 unitId、documentId、大模块或问题分类而批量复用 groupAnchorId");
        verify(repository).persistFeatureReviewConclusions(eq("task-1"), any());
    }

    @Test
    void rejectsNormalizedBusinessPathsThatWouldCreateDistinctFrozenConclusions() {
        assertRejectedFinalReconciliation(List.of(candidate(1), candidate(2)), scanResponse("""
                | 1 | 订单 查询 | 匹配 | candidateIds=candidate-01; groupAnchorId=candidate-01; documentId=requirement-doc; unitId=requirement-unit |
                | 2 | 订单　查询 | 匹配 | candidateIds=candidate-02; groupAnchorId=candidate-02; documentId=requirement-doc; unitId=requirement-unit |
                """));
        assertRejectedFinalReconciliation(List.of(candidate(1), candidate(2)), scanResponse("""
                | 1 | 订单查询 | 匹配 | candidateIds=candidate-01; groupAnchorId=candidate-01; documentId=requirement-doc; unitId=requirement-unit |
                | 2 | 订单查询 | 功能清单遗漏 | candidateIds=candidate-02; groupAnchorId=candidate-01; documentId=requirement-doc; unitId=requirement-unit |
                """));
        assertRejectedFinalReconciliation(List.of(candidate(1), candidate(2)), scanResponse("""
                | 1 | 创建订单<br>取消订单 | 拆分 | candidateIds=candidate-01; groupAnchorId=candidate-01; documentId=requirement-doc; unitId=requirement-unit |
                | 2 | 取消订单<br>查询订单 | 拆分 | candidateIds=candidate-02; groupAnchorId=candidate-02; documentId=requirement-doc; unitId=requirement-unit |
                """));
    }

    @Test
    void retriesCurrentBatchNormalizedPathConflictWithExactTargetIdsWithoutForcingASemanticOutcome() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        List<FeatureSourceCandidate> candidates = List.of(candidate(1), candidate(2), candidate(3), candidate(4));
        String conflict = scanResponse("""
                | 1 | 共享路径 | 匹配 | candidateIds=candidate-01; groupAnchorId=candidate-01; documentId=requirement-doc; unitId=requirement-unit |
                | 2 | 共享路径 | 功能清单遗漏 | candidateIds=candidate-02; groupAnchorId=candidate-02; documentId=requirement-doc; unitId=requirement-unit |
                | 3 | 一致路径 | 匹配 | candidateIds=candidate-03; groupAnchorId=candidate-03; documentId=requirement-doc; unitId=requirement-unit |
                | 4 | 一致路径 | 匹配 | candidateIds=candidate-04; groupAnchorId=candidate-03; documentId=requirement-doc; unitId=requirement-unit |
                """);
        stubSingleBatchAudit(repository, request, candidates);
        when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(result(conflict),
                result(boundedReconciliationResponse(candidates, "candidate-01", "candidate-02")));

        assertThat(service.audit("task-1", request).complete()).isTrue();
        ArgumentCaptor<FeatureReconciliationInvocation> invocations = ArgumentCaptor.forClass(FeatureReconciliationInvocation.class);
        verify(knowledgeAgentPort, times(2)).reconcileFeatures(invocations.capture());
        assertThat(invocations.getAllValues().get(1).prompt()).contains(
                "本批次归一化路径冲突目标", "targetCandidateId=candidate-01", "targetCandidateId=candidate-02",
                "不得由 Java 自动裁决").doesNotContain("targetCandidateId=candidate-03", "targetCandidateId=candidate-04");
        verify(repository).persistFeatureReviewConclusions(eq("task-1"), any());
    }

    @Test
    // [Req-ID]: REQ-BFA-007
    void retriesOneNormalizedPathConflictWithEveryTargetAndTheStableEarliestTargetRule() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        List<FeatureSourceCandidate> candidates = List.of(candidate(1), candidate(2));
        String conflict = scanResponse("""
                | 1 | 未回灌源路径甲 | 匹配 | candidateIds=candidate-01; groupAnchorId=candidate-01; documentId=requirement-doc; unitId=requirement-unit |
                | 2 | 未回灌源路径甲 | 匹配 | candidateIds=candidate-02; groupAnchorId=candidate-02; documentId=requirement-doc; unitId=requirement-unit |
                """);
        stubSingleBatchAudit(repository, request, candidates);
        when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(result(conflict),
                result(boundedReconciliationResponse(candidates, "candidate-01", "candidate-02")));

        assertThat(service.audit("task-1", request).complete()).isTrue();

        ArgumentCaptor<FeatureReconciliationInvocation> invocations = ArgumentCaptor.forClass(FeatureReconciliationInvocation.class);
        verify(knowledgeAgentPort, times(2)).reconcileFeatures(invocations.capture());
        String feedback = normalizedPathConflictFeedback(invocations.getAllValues().get(1).prompt());
        assertThat(feedback).contains("冲突组 1", "targetCandidateId=candidate-01", "targetCandidateId=candidate-02",
                "earliestTargetCandidateId=candidate-01", "requiredGroupAnchorId=candidate-01", "若仍判断为同一路径",
                "每行 groupAnchorId 必须逐字复制该组 requiredGroupAnchorId 的实际值", "分类和完整路径一致",
                "若不同", "正式证据", "不得由 Java 自动裁决")
                .doesNotContain("未回灌源路径甲", "groupAnchorId=earliestTargetCandidateId");
        verify(repository).persistFeatureReviewConclusions(eq("task-1"), any());
    }

    @Test
    // [Req-ID]: REQ-BFA-007
    void keepsTwoCurrentNormalizedPathConflictsSeparatedWithTheirOwnStableEarliestTargets() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        List<FeatureSourceCandidate> candidates = List.of(candidate(1), candidate(2), candidate(3), candidate(4));
        String conflict = scanResponse("""
                | 1 | 路径甲 | 匹配 | candidateIds=candidate-01; groupAnchorId=candidate-01; documentId=requirement-doc; unitId=requirement-unit |
                | 2 | 路径乙 | 匹配 | candidateIds=candidate-02; groupAnchorId=candidate-02; documentId=requirement-doc; unitId=requirement-unit |
                | 3 | 路径甲 | 功能清单遗漏 | candidateIds=candidate-03; groupAnchorId=candidate-03; documentId=requirement-doc; unitId=requirement-unit |
                | 4 | 路径乙 | 功能清单遗漏 | candidateIds=candidate-04; groupAnchorId=candidate-04; documentId=requirement-doc; unitId=requirement-unit |
                """);
        String corrected = scanResponse(
                finalReconciliationRow(candidates.get(0), "已区分路径甲", "匹配", "candidate-01")
                        + finalReconciliationRow(candidates.get(1), "已区分路径乙", "匹配", "candidate-02")
                        + finalReconciliationRow(candidates.get(2), "已区分路径丙", "功能清单遗漏", "candidate-03")
                        + finalReconciliationRow(candidates.get(3), "已区分路径丁", "功能清单遗漏", "candidate-04"));
        stubSingleBatchAudit(repository, request, candidates);
        when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(result(conflict), result(corrected));

        assertThat(service.audit("task-1", request).complete()).isTrue();

        ArgumentCaptor<FeatureReconciliationInvocation> invocations = ArgumentCaptor.forClass(FeatureReconciliationInvocation.class);
        verify(knowledgeAgentPort, times(2)).reconcileFeatures(invocations.capture());
        String feedback = normalizedPathConflictFeedback(invocations.getAllValues().get(1).prompt());
        int firstGroup = feedback.indexOf("冲突组 1");
        int secondGroup = feedback.indexOf("冲突组 2");
        assertThat(firstGroup).isGreaterThanOrEqualTo(0);
        assertThat(secondGroup).isGreaterThan(firstGroup);
        assertThat(feedback.substring(firstGroup, secondGroup)).contains("targetCandidateId=candidate-01",
                "targetCandidateId=candidate-03", "earliestTargetCandidateId=candidate-01", "requiredGroupAnchorId=candidate-01")
                .doesNotContain("candidate-02", "candidate-04");
        assertThat(feedback.substring(secondGroup)).contains("targetCandidateId=candidate-02",
                "targetCandidateId=candidate-04", "earliestTargetCandidateId=candidate-02", "requiredGroupAnchorId=candidate-02")
                .doesNotContain("candidate-01", "candidate-03", "groupAnchorId=earliestTargetCandidateId");
        verify(repository).persistFeatureReviewConclusions(eq("task-1"), any());
    }

    @Test
    // [Req-ID]: REQ-BFA-007
    void failsClosedWithoutIsolatedRechecksOrPersistenceAfterThreeNormalizedPathConflicts() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        List<FeatureSourceCandidate> candidates = List.of(candidate(1), candidate(2));
        String conflict = scanResponse("""
                | 1 | 同一路径 | 匹配 | candidateIds=candidate-01; groupAnchorId=candidate-01; documentId=requirement-doc; unitId=requirement-unit |
                | 2 | 同一路径 | 匹配 | candidateIds=candidate-02; groupAnchorId=candidate-02; documentId=requirement-doc; unitId=requirement-unit |
                """);
        stubSingleBatchAudit(repository, request, candidates);
        when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(result(conflict), result(conflict), result(conflict));

        assertThatThrownBy(() -> service.audit("task-1", request))
                .isInstanceOf(FinalReconciliationPageException.class)
                .satisfies(failure -> assertThat(((FinalReconciliationPageException) failure).category())
                        .isEqualTo(FinalReconciliationPageException.Category.NORMALIZED_PATH_CONFLICT));

        verify(knowledgeAgentPort, times(3)).reconcileFeatures(any());
        verify(repository, never()).persistFeatureReviewConclusions(any(), any());
    }

    @Test
    // [Req-ID]: REQ-BFA-007
    void doesNotAddCurrentConflictFeedbackForAnAlreadyConsistentNormalizedPathGroup() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        List<FeatureSourceCandidate> candidates = List.of(candidate(1), candidate(2));
        String consistent = scanResponse("""
                | 1 | 同一路径 | 匹配 | candidateIds=candidate-01; groupAnchorId=candidate-01; documentId=requirement-doc; unitId=requirement-unit |
                | 2 | 同一路径 | 匹配 | candidateIds=candidate-02; groupAnchorId=candidate-01; documentId=requirement-doc; unitId=requirement-unit |
                """);
        stubSingleBatchAudit(repository, request, candidates);
        when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(result(consistent));

        assertThat(service.audit("task-1", request).complete()).isTrue();

        ArgumentCaptor<FeatureReconciliationInvocation> invocation = ArgumentCaptor.forClass(FeatureReconciliationInvocation.class);
        verify(knowledgeAgentPort).reconcileFeatures(invocation.capture());
        assertThat(invocation.getValue().prompt()).doesNotContain("本批次归一化路径冲突目标");
        verify(repository).persistFeatureReviewConclusions(eq("task-1"), any());
    }

    @Test
    void rejectsDuplicateNormalizedPathsWithinOneSplitBeforePersistence() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        List<FeatureSourceCandidate> candidates = List.of(candidate(1));
        String invalidResponse = scanResponse("""
                | 1 | 订单 查询<br>订单　查询 | 拆分 | candidateIds=candidate-01; groupAnchorId=candidate-01; documentId=requirement-doc; unitId=requirement-unit |
                """);

        when(repository.hasCompleteMaterialInventory("task-1", request.requirementScope())).thenReturn(true);
        when(repository.materialInventory("task-1")).thenReturn(List.of());
        when(repository.claimNextAuditWork(eq("task-1"), any(), any())).thenReturn(Optional.empty());
        when(repository.featureSourceCandidates("task-1")).thenReturn(candidates);
        when(repository.featureAuditCounts("task-1")).thenReturn(
                new GenerationTaskRepository.FeatureAuditCounts(1, 1, 0, 1, 0, 0));
        when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(
                result(invalidResponse), result(invalidResponse), result(invalidResponse));

        assertThatThrownBy(() -> service.audit("task-1", request))
                .isInstanceOf(FinalReconciliationPageException.class);

        ArgumentCaptor<FeatureReconciliationInvocation> invocations = ArgumentCaptor.forClass(FeatureReconciliationInvocation.class);
        verify(knowledgeAgentPort, times(6)).reconcileFeatures(invocations.capture());
        assertThat(invocations.getAllValues().get(1).prompt()).contains(
                "重试纠正要求：", "业务路径结构必须符合拆分和非拆分结论的固定要求");
        verify(repository, never()).persistFeatureReviewConclusions(any(), any());
    }

    @Test
    void rejectsSplitWithOneBusinessPathBeforePersistence() {
        assertRejectedFinalReconciliation(
                List.of(candidate(1)),
                scanResponse("""
                        | 1 | 订单查询 | 拆分 | candidateIds=candidate-01; groupAnchorId=candidate-01; documentId=requirement-doc; unitId=requirement-unit |
                        """),
                "业务路径结构必须符合拆分和非拆分结论的固定要求");
    }

    @Test
    void rejectsSplitWithBlankBusinessPathBeforePersistence() {
        assertRejectedFinalReconciliation(
                List.of(candidate(1)),
                scanResponse("""
                        | 1 | 订单查询<br>  | 拆分 | candidateIds=candidate-01; groupAnchorId=candidate-01; documentId=requirement-doc; unitId=requirement-unit |
                        """),
                "业务路径结构必须符合拆分和非拆分结论的固定要求");
    }

    @Test
    void rejectsNonSplitPathContainingBreakBeforePersistence() {
        assertRejectedFinalReconciliation(
                List.of(candidate(1)),
                scanResponse("""
                        | 1 | 订单查询<br>提交订单 | 匹配 | candidateIds=candidate-01; groupAnchorId=candidate-01; documentId=requirement-doc; unitId=requirement-unit |
                        """),
                "章节号、条款号和目录编号只能写在证据对照列，绝不能写入对象/功能点");
    }

    @Test
    void rejectsACrossPageNormalizedPathThatIsSelfAnchoredAgain() {
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
                new GenerationTaskRepository.FeatureAuditCounts(1, 1, 0, 17, 0, 0));
        StringBuilder firstPageRows = new StringBuilder();
        for (FeatureSourceCandidate candidate : candidates.subList(0, 8)) {
            String path = candidate.occurrenceId().equals("candidate-01") ? "订单 查询" : candidate.featureText();
            firstPageRows.append(finalReconciliationRow(candidate, path, "匹配", candidate.occurrenceId()));
        }
        StringBuilder invalidSecondPageRows = new StringBuilder();
        for (FeatureSourceCandidate candidate : candidates.subList(8, 16)) {
            String path = candidate.occurrenceId().equals("candidate-09") ? "订单　查询" : candidate.featureText();
            invalidSecondPageRows.append(finalReconciliationRow(candidate, path, "匹配", candidate.occurrenceId()));
        }
        String invalidSecondPage = scanResponse(invalidSecondPageRows.toString());
        when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(
                result(scanResponse(firstPageRows.toString())), result(invalidSecondPage), result(invalidSecondPage), result(invalidSecondPage));

        assertThatThrownBy(() -> service.audit("task-1", request))
                .isInstanceOf(FinalReconciliationPageException.class);

        ArgumentCaptor<FeatureReconciliationInvocation> invocations = ArgumentCaptor.forClass(FeatureReconciliationInvocation.class);
        verify(knowledgeAgentPort, times(4)).reconcileFeatures(invocations.capture());
        assertThat(invocations.getAllValues().get(2).prompt()).contains(
                "重试纠正要求：",
                "按 NFKC、首尾 strip、连续空白折叠成一个空格并转为小写后相同的业务路径必须使用同一 groupAnchorId 和同一问题分类",
                "禁止将同一路径 self-anchor 成多个结论");
        verify(repository, never()).persistFeatureReviewConclusions(any(), any());
    }

    @Test
    void acceptsNormalizedBusinessPathsWithinOneEarlierAnchorAndMergesThem() {
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
        when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(result(scanResponse("""
                | 1 | 订单查询 | 匹配 | candidateIds=candidate-01; groupAnchorId=candidate-01; documentId=requirement-doc; unitId=requirement-unit |
                | 2 | 订单查询 | 匹配 | candidateIds=candidate-02; groupAnchorId=candidate-01; documentId=requirement-doc; unitId=requirement-unit |
                """)));

        assertThat(service.audit("task-1", request).complete()).isTrue();

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
                result(boundedReconciliationResponse(candidates.subList(0, 8), "candidate-01", "outside-group")),
                result("unexpected raw final response"), result("unexpected raw final response"), result("unexpected raw final response"));

        Throwable failure = catchThrowable(() -> service.audit("task-1", request));
        assertThat(failure).isInstanceOf(FinalReconciliationPageException.class);
        assertThat(failure.getMessage()).doesNotContain("unexpected raw final response");

        verify(knowledgeAgentPort, times(4)).reconcileFeatures(any());
        verify(repository, never()).persistFeatureReviewConclusions(any(), any());
    }

    @Test
    // [Req-ID]: REQ-CWR-004
    void reportsOnlySafePageObservationWhenTheSecondReconciliationPageExhaustsAllAttempts() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FeatureAuditService service = new FeatureAuditService(repository, knowledgeAgentPort);
        CreateGenerationTaskRequest request = request();
        List<FeatureSourceCandidate> candidates = java.util.stream.IntStream.rangeClosed(1, 9)
                .mapToObj(FeatureAuditServiceTest::candidate).toList();
        StringBuilder firstPageRows = new StringBuilder();
        for (FeatureSourceCandidate candidate : candidates.subList(0, 8)) {
            firstPageRows.append(finalReconciliationRow(candidate, candidate.featureText(), "匹配", candidate.occurrenceId()));
        }
        String unsafeResponse = "https://internal.invalid/path?secret=red-team-only&documentId=private-document";

        when(repository.hasCompleteMaterialInventory("task-1", request.requirementScope())).thenReturn(true);
        when(repository.materialInventory("task-1")).thenReturn(List.of());
        when(repository.claimNextAuditWork(eq("task-1"), any(), any())).thenReturn(Optional.empty());
        when(repository.featureSourceCandidates("task-1")).thenReturn(candidates);
        when(repository.featureAuditCounts("task-1")).thenReturn(
                new GenerationTaskRepository.FeatureAuditCounts(1, 1, 0, 9, 0, 0));
        when(knowledgeAgentPort.reconcileFeatures(any())).thenReturn(
                result(scanResponse(firstPageRows.toString())), result(unsafeResponse), result(unsafeResponse), result(unsafeResponse));

        Throwable failure = catchThrowable(() -> service.audit("task-1", request));

        assertThat(failure).isInstanceOf(FinalReconciliationPageException.class);
        FinalReconciliationPageException pageFailure = (FinalReconciliationPageException) failure;
        assertThat(pageFailure.pageNumber()).isEqualTo(2);
        assertThat(pageFailure.totalPages()).isEqualTo(2);
        assertThat(pageFailure.attempts()).isEqualTo(3);
        assertThat(pageFailure.category()).isEqualTo(FinalReconciliationPageException.Category.STRICT_MARKDOWN);
        assertThat(pageFailure.safeSummary()).isEqualTo("最终双向核对第 2/2 个功能审核批次连续 3 次未通过：Markdown 格式不符合约定");
        assertThat(pageFailure.getMessage()).doesNotContain("internal.invalid", "secret", "private-document");
        verify(repository, never()).persistFeatureReviewConclusions(any(), any());
    }

    @Test
    // [Req-ID]: REQ-CWR-004
    void mapsOnlyFixedFinalReconciliationFailuresToSafeCategories() {
        assertThat(FinalReconciliationPageException.exhausted(1, 4, 3,
                "Every retained candidate requires exactly one page conclusion").category())
                .isEqualTo(FinalReconciliationPageException.Category.MISSING_TARGET);
        assertThat(FinalReconciliationPageException.exhausted(1, 4, 3,
                "Grouped representative evidence must bind its exact documentId and unitId").category())
                .isEqualTo(FinalReconciliationPageException.Category.REPRESENTATIVE_BINDING);
        assertThat(FinalReconciliationPageException.exhausted(1, 4, 3,
                "Every anchored group must have one exact type and business path").category())
                .isEqualTo(FinalReconciliationPageException.Category.ANCHOR_CONFLICT);
        assertThat(FinalReconciliationPageException.exhausted(1, 4, 3,
                "Each grouped representative must retain its normalized business path").category())
                .isEqualTo(FinalReconciliationPageException.Category.BUSINESS_PATH_STRUCTURE);
        FinalReconciliationPageException unknown = FinalReconciliationPageException.exhausted(4, 4, 3,
                "https://internal.invalid/secret?token=red-team-only; candidateId=hidden");
        assertThat(unknown.category()).isEqualTo(FinalReconciliationPageException.Category.UNKNOWN_CONTRACT);
        assertThat(unknown.safeSummary()).isEqualTo("最终双向核对第 4/4 个功能审核批次连续 3 次未通过：固定合同未满足");
        assertThat(unknown.getMessage()).doesNotContain("internal.invalid", "red-team-only", "candidateId");
        assertThat(unknown.getCause()).isNull();
        unknown.addSuppressed(new IllegalStateException(
                "https://internal.invalid/secret?token=red-team-only; candidateId=hidden; documentId=hidden; unitId=hidden"));
        assertThat(unknown.getSuppressed()).isEmpty();
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

    private static void stubSingleBatchAudit(
            GenerationTaskRepository repository, CreateGenerationTaskRequest request, List<FeatureSourceCandidate> candidates) {
        when(repository.hasCompleteMaterialInventory("task-1", request.requirementScope())).thenReturn(true);
        when(repository.materialInventory("task-1")).thenReturn(List.of());
        when(repository.claimNextAuditWork(eq("task-1"), any(), any())).thenReturn(Optional.empty());
        when(repository.featureSourceCandidates("task-1")).thenReturn(candidates);
        when(repository.featureAuditCounts("task-1")).thenReturn(
                new GenerationTaskRepository.FeatureAuditCounts(1, 1, 0, candidates.size(), 0, 0),
                new GenerationTaskRepository.FeatureAuditCounts(1, 1, 0, candidates.size(), candidates.size(), candidates.size()));
    }

    private static KnowledgeAgentInvocationResult[] individualSuccesses(List<FeatureSourceCandidate> candidates) {
        return candidates.stream().map(candidate -> result(scanResponse(finalReconciliationRow(
                candidate, candidate.featureText(), "匹配", candidate.occurrenceId())))).toArray(KnowledgeAgentInvocationResult[]::new);
    }

    private static KnowledgeAgentInvocationResult[] individualRecheckSequence(
            List<FeatureSourceCandidate> candidates, KnowledgeAgentInvocationResult... pageFailures) {
        KnowledgeAgentInvocationResult[] successes = individualSuccesses(candidates);
        KnowledgeAgentInvocationResult[] responses = java.util.Arrays.copyOf(pageFailures, pageFailures.length + successes.length);
        System.arraycopy(successes, 0, responses, pageFailures.length, successes.length);
        return responses;
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

    private static String finalReconciliationRow(
            FeatureSourceCandidate candidate, String path, String issueCategory, String groupAnchorId) {
        return "| " + candidate.modelSequence() + " | " + path + " | " + issueCategory + " | candidateIds="
                + candidate.occurrenceId() + "; groupAnchorId=" + groupAnchorId + "; documentId="
                + candidate.documentId() + "; unitId=" + candidate.unitId() + " |\n";
    }

    private static List<String> targetCandidateIds(String prompt) {
        String targetBlock = prompt.substring(prompt.indexOf("本页目标候选：") + "本页目标候选：".length());
        return targetBlock.lines().filter(line -> line.startsWith("candidateId="))
                .map(line -> line.substring(0, line.indexOf(';'))).toList();
    }

    private static String normalizedPathConflictFeedback(String prompt) {
        return prompt.substring(prompt.lastIndexOf("本批次归一化路径冲突目标"));
    }

    private static String crossPageAnchorConflictFeedback(String prompt) {
        return prompt.substring(prompt.lastIndexOf("本批次跨批 anchor 误用目标"));
    }

    private static void stubTwoPageAudit(
            GenerationTaskRepository repository, CreateGenerationTaskRequest request, List<FeatureSourceCandidate> candidates) {
        when(repository.hasCompleteMaterialInventory("task-1", request.requirementScope())).thenReturn(true);
        when(repository.materialInventory("task-1")).thenReturn(List.of());
        when(repository.claimNextAuditWork(eq("task-1"), any(), any())).thenReturn(Optional.empty());
        when(repository.featureSourceCandidates("task-1")).thenReturn(candidates);
        when(repository.featureAuditCounts("task-1")).thenReturn(
                new GenerationTaskRepository.FeatureAuditCounts(1, 1, 0, candidates.size(), 0, 0),
                new GenerationTaskRepository.FeatureAuditCounts(1, 1, 0, candidates.size(), candidates.size(), candidates.size()));
    }

    private static String acceptedCrossPageAnchorRows(List<FeatureSourceCandidate> candidates) {
        StringBuilder rows = new StringBuilder();
        for (int index = 0; index < 8; index++) {
            FeatureSourceCandidate candidate = candidates.get(index);
            rows.append(finalReconciliationRow(candidate, "已接受路径" + (index + 1), "匹配", candidate.occurrenceId()));
        }
        return scanResponse(rows.toString());
    }

    private static String misusedAcceptedAnchorRows(List<FeatureSourceCandidate> candidates) {
        StringBuilder rows = new StringBuilder();
        for (int index = 0; index < 8; index++) {
            FeatureSourceCandidate candidate = candidates.get(index + 8);
            if (index < 4) {
                rows.append(finalReconciliationRow(candidate, "未接收路径" + (index + 1), "证据不足",
                        "candidate-%02d".formatted(index + 1)));
            } else {
                rows.append(finalReconciliationRow(candidate, "正确当前路径" + (index + 1), "匹配", candidate.occurrenceId()));
            }
        }
        return scanResponse(rows.toString());
    }

    private static String correctedSelfAnchorRows(List<FeatureSourceCandidate> candidates) {
        StringBuilder rows = new StringBuilder();
        for (int index = 8; index < candidates.size(); index++) {
            FeatureSourceCandidate candidate = candidates.get(index);
            rows.append(finalReconciliationRow(candidate, "修正当前路径" + (index + 1), "匹配", candidate.occurrenceId()));
        }
        return scanResponse(rows.toString());
    }

    private static void assertRejectedFinalReconciliation(List<FeatureSourceCandidate> candidates, String invalidResponse) {
        assertRejectedFinalReconciliation(candidates, invalidResponse, null);
    }

    private static void assertRejectedFinalReconciliation(
            List<FeatureSourceCandidate> candidates, String invalidResponse, String expectedRetryFeedback) {
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
                .isInstanceOf(FinalReconciliationPageException.class);
        if (expectedRetryFeedback == null) {
            verify(knowledgeAgentPort, times(3)).reconcileFeatures(any());
        } else {
            ArgumentCaptor<FeatureReconciliationInvocation> invocations = ArgumentCaptor.forClass(FeatureReconciliationInvocation.class);
            verify(knowledgeAgentPort, times(6)).reconcileFeatures(invocations.capture());
            String retrySection = invocations.getAllValues().get(1).prompt();
            retrySection = retrySection.substring(retrySection.lastIndexOf("重试纠正要求："));
            assertThat(retrySection).contains("重试纠正要求：", expectedRetryFeedback);
        }
        verify(repository, never()).persistFeatureReviewConclusions(any(), any());
    }

    private static CreateGenerationTaskRequest request() {
        return new CreateGenerationTaskRequest(GenerationTaskMode.ALL, "all", FewShotPolicy.NONE, "1", "1", "audit-agent",
                new RequirementScope("requirement-kb", "system", "version", "admission", "project", List.of(
                        new RequirementDocumentCoordinate("function-doc"), new RequirementDocumentCoordinate("requirement-doc"))),
                new ExampleScope("example-kb", List.of("example-doc")), "requirements_spec", "audit");
    }
}
