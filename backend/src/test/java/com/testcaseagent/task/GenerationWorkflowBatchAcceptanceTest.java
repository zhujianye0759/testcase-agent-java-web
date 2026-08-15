package com.testcaseagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testcaseagent.export.WorkbookExporter;
import com.testcaseagent.featureaudit.FeatureReviewConclusionType;
import com.testcaseagent.featureaudit.FeatureAuditService;
import com.testcaseagent.featureaudit.FrozenFeatureService;
import com.testcaseagent.featureaudit.FrozenFeatureSource;
import com.testcaseagent.featureaudit.FrozenFeatureTarget;
import com.testcaseagent.featureaudit.RequirementMaterialTraversalService;
import com.testcaseagent.fewshot.ExampleScope;
import com.testcaseagent.knowledgeagent.KnowledgeAgentInvocation;
import com.testcaseagent.knowledgeagent.KnowledgeAgentInvocationResult;
import com.testcaseagent.knowledgeagent.KnowledgeAgentPort;
import com.testcaseagent.scope.RequirementDocumentCoordinate;
import com.testcaseagent.scope.RequirementScope;
import com.testcaseagent.testcase.FewShotPolicy;
import com.testcaseagent.testcase.GenerationTaskMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Exercises the public claimed-task seam for a frozen ALL batch.
 *
 * [Req-ID]: REQ-CAG-001, REQ-CAG-002, REQ-CAG-003, REQ-KSI-001
 */
class GenerationWorkflowBatchAcceptanceTest {

    private static final String TASK_ID = "task-all";
    private static final String FEATURE_ID = "frozen-order-query";

    @Test
    void acceptsOnlyTheCurrentFrozenTargetAndSuppliesItsCandidateAllowListToTheAgent() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort agent = mock(KnowledgeAgentPort.class);
        CreateGenerationTaskRequest request = frozenAllRequest();
        GenerationTaskRepository.TaskExecutionWork work = new GenerationTaskRepository.TaskExecutionWork(
                TASK_ID, "batch-1", "attempt-1", FEATURE_ID, request);
        FrozenFeatureTarget target = frozenTarget();
        when(repository.request(TASK_ID)).thenReturn(request);
        when(repository.requireQueuedWork(TASK_ID)).thenReturn(work);
        when(repository.nextQueuedWork(TASK_ID)).thenReturn(Optional.of(work), Optional.empty());
        when(repository.frozenFeatureTargets(TASK_ID)).thenReturn(List.of(target));
        when(repository.acceptedMarkdownRows(TASK_ID)).thenReturn(new MarkdownTaskRows(List.of(), List.of()));
        when(agent.invoke(any())).thenReturn(new KnowledgeAgentInvocationResult("session-1", List.of(), validMarkdown()));

        workflow(repository, agent).executeClaimed(new TaskExecutionClaim(TASK_ID, 1));

        ArgumentCaptor<KnowledgeAgentInvocation> invocation = ArgumentCaptor.forClass(KnowledgeAgentInvocation.class);
        verify(agent).invoke(invocation.capture());
        verify(agent).prepareGenerationSession(any());
        verify(agent).closePreparedSession();
        assertThat(invocation.getValue().prompt())
                .contains("仅生成当前功能路径：订单管理/订单查询")
                .contains("candidateIds=candidate-a,candidate-b")
                .contains("订单查询_正向")
                .contains("订单查询_反向")
                .contains("功能模块精确填写为：订单管理/订单查询")
                .contains("依据通用经验，待确认");
        verify(repository).acceptMarkdownBatch(eq("batch-1"), eq("attempt-1"), any());
    }

    @Test
    void rejectsAnAllBatchThatCitesAFeatureCandidateOutsideItsFrozenTargetWithoutAcceptingIt() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort agent = mock(KnowledgeAgentPort.class);
        CreateGenerationTaskRequest request = frozenAllRequest();
        GenerationTaskRepository.TaskExecutionWork work = new GenerationTaskRepository.TaskExecutionWork(
                TASK_ID, "batch-1", "attempt-1", FEATURE_ID, request);
        when(repository.request(TASK_ID)).thenReturn(request);
        when(repository.requireQueuedWork(TASK_ID)).thenReturn(work);
        when(repository.nextQueuedWork(TASK_ID)).thenReturn(Optional.of(work), Optional.empty());
        when(repository.frozenFeatureTargets(TASK_ID)).thenReturn(List.of(frozenTarget()));
        when(repository.acceptedMarkdownRows(TASK_ID)).thenReturn(new MarkdownTaskRows(List.of(), List.of()));
        when(agent.invoke(any())).thenReturn(new KnowledgeAgentInvocationResult("session-1", List.of(),
                validMarkdown().replace("candidateIds=candidate-a,candidate-b", "candidateIds=outside-candidate")));

        workflow(repository, agent).executeClaimed(new TaskExecutionClaim(TASK_ID, 1));

        verify(repository, never()).acceptMarkdownBatch(any(), any(), any());
        verify(repository).failBatch(eq("batch-1"), eq("attempt-1"), any(), eq(true));
    }

    @Test
    void keepsSpecifiedFeatureBatchesOnTheirExistingStrictMarkdownPathWithoutAReconciliationLedger() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort agent = mock(KnowledgeAgentPort.class);
        CreateGenerationTaskRequest request = new CreateGenerationTaskRequest(GenerationTaskMode.FEATURE, "feature", List.of("feature"),
                Map.of("feature", "订单管理/订单查询"), FewShotPolicy.NONE, "markdown-1.0", "1.0", "agent", scope(),
                new ExampleScope("example-kb", List.of("example-1")), List.of("function_list"), "补充说明");
        GenerationTaskRepository.TaskExecutionWork work = new GenerationTaskRepository.TaskExecutionWork(
                TASK_ID, "batch-1", "attempt-1", "feature", request);
        when(repository.request(TASK_ID)).thenReturn(request);
        when(repository.requireQueuedWork(TASK_ID)).thenReturn(work);
        when(repository.nextQueuedWork(TASK_ID)).thenReturn(Optional.of(work), Optional.empty());
        when(repository.acceptedMarkdownRows(TASK_ID)).thenReturn(new MarkdownTaskRows(List.of(), List.of()));
        when(agent.invoke(any())).thenReturn(new KnowledgeAgentInvocationResult("session-1", List.of(), validMarkdown()));

        workflow(repository, agent).executeClaimed(new TaskExecutionClaim(TASK_ID, 1));

        verify(repository, never()).frozenFeatureTargets(any());
        verify(repository).acceptMarkdownBatch(eq("batch-1"), eq("attempt-1"), any());
    }

    private static GenerationWorkflow workflow(GenerationTaskRepository repository, KnowledgeAgentPort agent) {
        when(repository.finalizationReadiness(TASK_ID))
                .thenReturn(new GenerationTaskRepository.FinalizationReadiness(GenerationTaskStatus.FAILED, false));
        return new GenerationWorkflow(repository, agent, mock(WorkbookExporter.class), new ObjectMapper(),
                mock(TaskExecutionQueue.class), Runnable::run, mock(RequirementMaterialTraversalService.class),
                mock(FeatureAuditService.class), mock(FrozenFeatureService.class));
    }

    private static CreateGenerationTaskRequest frozenAllRequest() {
        return new CreateGenerationTaskRequest(GenerationTaskMode.ALL, "all", List.of(FEATURE_ID),
                Map.of(FEATURE_ID, "订单管理/订单查询"), FewShotPolicy.NONE, "markdown-1.0", "1.0", "agent", scope(),
                new ExampleScope("example-kb", List.of("example-1")), List.of("function_list", "work_order_plan"), "补充说明");
    }

    private static RequirementScope scope() {
        return new RequirementScope("requirement-kb", "system", "version", "admission_material", null,
                List.of(new RequirementDocumentCoordinate("function-document", "function_list")));
    }

    private static FrozenFeatureTarget frozenTarget() {
        return new FrozenFeatureTarget(FEATURE_ID, 1, "订单管理/订单查询", true,
                new FrozenFeatureSource("conclusion-1", FeatureReviewConclusionType.MATCHED,
                        List.of("candidate-a", "candidate-b"), "双向核对完成"));
    }

    private static String validMarkdown() {
        return """
                ## 需求与功能清单审查发现
                | 序号 | 对象/功能点 | 问题分类 | 证据对照 |
                | --- | --- | --- | --- |
                | 1 | 订单查询 | 未发现问题 | candidateIds=candidate-a |

                ## 测试用例
                | 用例名称 | 功能模块 | 前提约束 | 执行步骤 | 预期结果 | 对应需求内容 |
                | --- | --- | --- | --- | --- | --- |
                | 订单查询_正向 | 订单管理/订单查询 | 已登录 | 1. 输入订单号<br>2. 点击查询 | 1. 展示匹配订单<br>2. 展示订单详情 | 需求摘要<br>candidateIds=candidate-a,candidate-b |
                | 订单查询_反向 | 订单管理/订单查询 | 已登录 | 1. 输入不存在订单号 | 1. 提示无匹配订单 | 依据通用经验，待确认 |
                """;
    }
}
