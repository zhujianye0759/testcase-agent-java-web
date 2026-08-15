package com.testcaseagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testcaseagent.export.WorkbookExporter;
import com.testcaseagent.featureaudit.FeatureAuditResult;
import com.testcaseagent.featureaudit.FeatureAuditService;
import com.testcaseagent.featureaudit.FrozenFeatureResult;
import com.testcaseagent.featureaudit.FrozenFeatureService;
import com.testcaseagent.featureaudit.FrozenFeatureSource;
import com.testcaseagent.featureaudit.FrozenFeatureTarget;
import com.testcaseagent.featureaudit.FeatureReviewConclusionType;
import com.testcaseagent.featureaudit.RequirementMaterialTraversalService;
import com.testcaseagent.fewshot.ExampleScope;
import com.testcaseagent.knowledgeagent.KnowledgeAgentPort;
import com.testcaseagent.knowledgeagent.KnowledgeAgentSkillPreparationException;
import com.testcaseagent.scope.RequirementDocumentCoordinate;
import com.testcaseagent.scope.RequirementScope;
import com.testcaseagent.testcase.FewShotPolicy;
import com.testcaseagent.testcase.GenerationTaskMode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * Exercises the ALL-mode orchestration seam before any single-feature generation is attempted.
 *
 * [Req-ID]: REQ-CAG-001, REQ-BFA-005
 */
class GenerationWorkflowAllModeTest {

    private static final String TASK_ID = "task-all";

    private GenerationTaskRepository repository;
    private RequirementMaterialTraversalService traversalService;
    private FeatureAuditService featureAuditService;
    private FrozenFeatureService frozenFeatureService;
    private GenerationWorkflow workflow;

    @BeforeEach
    void setUp() {
        repository = mock(GenerationTaskRepository.class);
        traversalService = mock(RequirementMaterialTraversalService.class);
        featureAuditService = mock(FeatureAuditService.class);
        frozenFeatureService = mock(FrozenFeatureService.class);
        when(repository.finalizationReadiness(TASK_ID))
                .thenReturn(new GenerationTaskRepository.FinalizationReadiness(GenerationTaskStatus.FAILED, false));
        workflow = new GenerationWorkflow(repository, mock(KnowledgeAgentPort.class), mock(WorkbookExporter.class),
                new ObjectMapper(), mock(TaskExecutionQueue.class), Runnable::run,
                traversalService, featureAuditService, frozenFeatureService);
    }

    @Test
    void plansOnlyEligibleFrozenFeaturesInStableSequenceAfterTraversalAndAuditComplete() {
        CreateGenerationTaskRequest pending = pendingAllRequest();
        FrozenFeatureTarget later = target("ff-later", 2, "订单导出", true);
        FrozenFeatureTarget skipped = target("ff-skipped", 3, "异常材料", false);
        FrozenFeatureTarget first = target("ff-first", 1, "订单查询", true);
        when(featureAuditService.audit(TASK_ID, pending)).thenReturn(completeAudit());
        when(frozenFeatureService.freeze(TASK_ID, pending.requirementScope()))
                .thenReturn(new FrozenFeatureResult(true, List.of(later, skipped, first)));

        CreateGenerationTaskRequest frozen = workflow.freezeAllFeatures(TASK_ID, pending);

        assertThat(frozen.featureIds()).containsExactly("ff-first", "ff-later");
        assertThat(frozen.featurePaths()).containsExactly(Map.entry("ff-first", "订单查询"), Map.entry("ff-later", "订单导出"));
        ArgumentCaptor<List<GenerationTaskRepository.PlannedBatch>> batches = ArgumentCaptor.forClass(List.class);
        verify(repository).planFrozenBatches(eq(TASK_ID), eq(frozen), eq(List.of(first, later, skipped)), batches.capture());
        assertThat(batches.getValue()).extracting(GenerationTaskRepository.PlannedBatch::featureId)
                .containsExactly("ff-first", "ff-later");

        InOrder order = inOrder(traversalService, featureAuditService, frozenFeatureService, repository);
        order.verify(traversalService).traverse(TASK_ID, pending, false);
        order.verify(featureAuditService).audit(TASK_ID, pending);
        order.verify(frozenFeatureService).freeze(TASK_ID, pending.requirementScope());
        order.verify(repository).planFrozenBatches(eq(TASK_ID), eq(frozen), eq(List.of(first, later, skipped)), any());
    }

    @Test
    void refusesToPlanBatchesWhenTheAuditHasNotReachedItsCompleteGate() {
        CreateGenerationTaskRequest pending = pendingAllRequest();
        when(featureAuditService.audit(TASK_ID, pending)).thenReturn(new FeatureAuditResult(true, 2, 1, 0, 1, 0, false));

        assertThatThrownBy(() -> workflow.freezeAllFeatures(TASK_ID, pending))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("audit");

        verify(traversalService).traverse(TASK_ID, pending, false);
        verify(featureAuditService).audit(TASK_ID, pending);
        verify(frozenFeatureService, never()).freeze(any(), any());
        verify(repository, never()).planFrozenBatches(any(), any(), any(), any());
    }

    @Test
    void retainsAnExplicitNoEligibleFeatureSeamWithoutFabricatingABatch() {
        CreateGenerationTaskRequest pending = pendingAllRequest();
        FrozenFeatureTarget onlyIneligible = target("ff-insufficient", 1, "证据不足功能", false);
        when(featureAuditService.audit(TASK_ID, pending)).thenReturn(completeAudit());
        when(frozenFeatureService.freeze(TASK_ID, pending.requirementScope()))
                .thenReturn(new FrozenFeatureResult(true, List.of(onlyIneligible)));

        CreateGenerationTaskRequest frozen = workflow.freezeAllFeatures(TASK_ID, pending);

        assertThat(frozen.featureIds()).isEmpty();
        assertThat(frozen.featurePaths()).isEmpty();
        verify(repository, never()).planFrozenBatches(any(), any(), any(), any());
    }

    @Test
    void recordsAllIneligibleTargetsAsPartialInsteadOfLeavingTheClaimInAuditing() {
        CreateGenerationTaskRequest pending = pendingAllRequest();
        FrozenFeatureTarget onlyIneligible = target("ff-insufficient", 1, "证据不足功能", false);
        when(repository.request(TASK_ID)).thenReturn(pending);
        when(featureAuditService.audit(TASK_ID, pending)).thenReturn(completeAudit());
        when(frozenFeatureService.freeze(TASK_ID, pending.requirementScope()))
                .thenReturn(new FrozenFeatureResult(true, List.of(onlyIneligible)));

        workflow.executeClaimed(new TaskExecutionClaim(TASK_ID, 1));

        verify(repository).finishAllFrozenFeaturesIneligible(TASK_ID, pending.requirementScope());
        verify(repository, never()).requireQueuedWork(TASK_ID);
        verify(repository, never()).planFrozenBatches(any(), any(), any(), any());
    }

    @Test
    void cancelsDuringAuditingWithoutFreezingPlanningOrFailingTheTask() {
        CreateGenerationTaskRequest pending = pendingAllRequest();
        when(repository.request(TASK_ID)).thenReturn(pending);
        when(featureAuditService.audit(TASK_ID, pending)).thenThrow(new CancellationException("Cancellation requested"));
        when(repository.taskStatus(TASK_ID)).thenReturn(GenerationTaskStatus.AUDITING);
        when(repository.cancelAuditingAtCheckpoint(TASK_ID)).thenReturn(true);

        workflow.executeClaimed(new TaskExecutionClaim(TASK_ID, 1));

        verify(repository).cancelAuditingAtCheckpoint(TASK_ID);
        verify(repository, never()).failAuditingTask(TASK_ID);
        verify(frozenFeatureService, never()).freeze(any(), any());
        verify(repository, never()).planFrozenBatches(any(), any(), any(), any());
        verify(repository, never()).requireQueuedWork(TASK_ID);
        verify(repository, never()).transitionTask(TASK_ID, GenerationTaskStatus.GENERATING);
    }

    @Test
    void failsTheAuditingTaskWhenFeatureAuditStopsForAgentDiscoveryPreparationFailure() {
        CreateGenerationTaskRequest pending = pendingAllRequest();
        when(repository.request(TASK_ID)).thenReturn(pending);
        when(featureAuditService.audit(TASK_ID, pending))
                .thenThrow(new KnowledgeAgentSkillPreparationException("Knowledge agent discovery timed out", true, null));
        when(repository.taskStatus(TASK_ID)).thenReturn(GenerationTaskStatus.AUDITING);

        workflow.executeClaimed(new TaskExecutionClaim(TASK_ID, 1));

        verify(repository).failAuditingTask(TASK_ID);
        verify(frozenFeatureService, never()).freeze(any(), any());
        verify(repository, never()).planFrozenBatches(any(), any(), any(), any());
    }

    @Test
    void rejectsDuplicateOrNoncontinuousFrozenSequencesBeforeAnyBatchCanBePlanned() {
        CreateGenerationTaskRequest pending = pendingAllRequest();

        assertThatThrownBy(() -> pending.withFrozenFeatures(List.of(
                target("ff-one", 1, "订单查询", true), target("ff-two", 1, "订单导出", true))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sequence");
        assertThatThrownBy(() -> pending.withFrozenFeatures(List.of(
                target("ff-one", 1, "订单查询", true), target("ff-three", 3, "订单导出", true))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("continuous");
    }

    @Test
    void doesNotRunMaterialTraversalOrAuditForSpecifiedFeatureTasks() {
        CreateGenerationTaskRequest feature = specifiedFeatureRequest();
        when(repository.request(TASK_ID)).thenReturn(feature);
        when(repository.requireQueuedWork(TASK_ID)).thenReturn(new GenerationTaskRepository.TaskExecutionWork(
                TASK_ID, "batch-1", "attempt-1", feature.featureId(), feature));
        when(repository.nextQueuedWork(TASK_ID)).thenReturn(java.util.Optional.empty());
        when(repository.acceptedMarkdownRows(TASK_ID)).thenReturn(new MarkdownTaskRows(List.of(), List.of()));

        workflow.executeClaimed(new TaskExecutionClaim(TASK_ID, 1));

        verify(traversalService, never()).traverse(any(), any(), any(Boolean.class));
        verify(featureAuditService, never()).audit(any(), any());
        verify(frozenFeatureService, never()).freeze(any(), any());
        verify(repository).transitionTask(TASK_ID, GenerationTaskStatus.GENERATING);
    }

    @Test
    void resumesAnAlreadyFrozenAllRequestWithoutRepeatingTraversalAuditOrBatchPlanning() {
        CreateGenerationTaskRequest frozenAll = pendingAllRequest().withFrozenFeatures(List.of(target("ff-1", 1, "订单查询", true)));
        when(repository.request(TASK_ID)).thenReturn(frozenAll);
        when(repository.requireQueuedWork(TASK_ID)).thenReturn(new GenerationTaskRepository.TaskExecutionWork(
                TASK_ID, "batch-1", "attempt-1", "ff-1", frozenAll));
        when(repository.nextQueuedWork(TASK_ID)).thenReturn(java.util.Optional.empty());
        when(repository.acceptedMarkdownRows(TASK_ID)).thenReturn(new MarkdownTaskRows(List.of(), List.of()));

        workflow.executeClaimed(new TaskExecutionClaim(TASK_ID, 1));

        verify(traversalService, never()).traverse(any(), any(), any(Boolean.class));
        verify(featureAuditService, never()).audit(any(), any());
        verify(frozenFeatureService, never()).freeze(any(), any());
        verify(repository, never()).planFrozenBatches(any(), any(), any(), any());
        verify(repository).transitionTask(TASK_ID, GenerationTaskStatus.GENERATING);
    }

    private static FeatureAuditResult completeAudit() {
        return new FeatureAuditResult(true, 2, 2, 0, 2, 2, true);
    }

    private static FrozenFeatureTarget target(String id, int sequence, String name, boolean eligible) {
        return new FrozenFeatureTarget(id, sequence, name, eligible,
                new FrozenFeatureSource("conclusion-" + id, FeatureReviewConclusionType.MATCHED,
                        List.of("candidate-" + id), name));
    }

    private static CreateGenerationTaskRequest pendingAllRequest() {
        return new CreateGenerationTaskRequest(GenerationTaskMode.ALL, "all-pending", List.of(), Map.of(), FewShotPolicy.NONE,
                "markdown-1.0", "1.0", "agent", scope(), new ExampleScope("example-kb", List.of("example-1")),
                List.of("function_list", "work_order_plan"), "生成全部功能");
    }

    private static CreateGenerationTaskRequest specifiedFeatureRequest() {
        return new CreateGenerationTaskRequest(GenerationTaskMode.FEATURE, "feature-single", List.of("feature-single"),
                Map.of("feature-single", "订单查询"), FewShotPolicy.NONE, "markdown-1.0", "1.0", "agent", scope(),
                new ExampleScope("example-kb", List.of("example-1")), List.of("function_list"), "生成指定功能");
    }

    private static RequirementScope scope() {
        return new RequirementScope("requirement-kb", "system", "version", "admission_material", null,
                List.of(new RequirementDocumentCoordinate("function-document", "function_list")));
    }
}
