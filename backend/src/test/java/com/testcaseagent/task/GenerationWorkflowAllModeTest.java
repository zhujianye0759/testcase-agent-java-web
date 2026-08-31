package com.testcaseagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testcaseagent.export.WorkbookExporter;
import com.testcaseagent.export.WorkbookArtifact;
import com.testcaseagent.export.StructuredWorkbookExportRequest;
import com.testcaseagent.export.StructuredWorkbookRowSource;
import com.testcaseagent.featureaudit.FeatureAuditResult;
import com.testcaseagent.featureaudit.FeatureAuditService;
import com.testcaseagent.featureaudit.FinalReconciliationPageException;
import com.testcaseagent.featureaudit.FrozenFeatureResult;
import com.testcaseagent.featureaudit.FrozenFeatureService;
import com.testcaseagent.featureaudit.FrozenFeatureSource;
import com.testcaseagent.featureaudit.FrozenFeatureTarget;
import com.testcaseagent.featureaudit.FeatureReviewConclusionType;
import com.testcaseagent.featureaudit.RequirementMaterialTraversalService;
import com.testcaseagent.fewshot.ExampleScope;
import com.testcaseagent.knowledgeagent.KnowledgeAgentInvocation;
import com.testcaseagent.knowledgeagent.KnowledgeAgentInvocationResult;
import com.testcaseagent.knowledgeagent.KnowledgeAgentPort;
import com.testcaseagent.knowledgeagent.KnowledgeAgentSkillPreparationException;
import com.testcaseagent.scope.RequirementDocumentCoordinate;
import com.testcaseagent.scope.RequirementScope;
import com.testcaseagent.testcase.FewShotPolicy;
import com.testcaseagent.testcase.GenerationTaskMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;

/**
 * Exercises the ALL-mode orchestration seam before any single-feature generation is attempted.
 *
 * [Req-ID]: REQ-CAG-001, REQ-BFA-005, REQ-TGV2-009, REQ-TGV2-010
 */
class GenerationWorkflowAllModeTest {

    private static final String TASK_ID = "task-all";

    private GenerationTaskRepository repository;
    private RequirementMaterialTraversalService traversalService;
    private FeatureAuditService featureAuditService;
    private FrozenFeatureService frozenFeatureService;
    private KnowledgeAgentPort knowledgeAgentPort;
    private WorkbookExporter workbookExporter;
    private GenerationWorkflow workflow;

    @BeforeEach
    void setUp() {
        repository = mock(GenerationTaskRepository.class);
        traversalService = mock(RequirementMaterialTraversalService.class);
        featureAuditService = mock(FeatureAuditService.class);
        frozenFeatureService = mock(FrozenFeatureService.class);
        knowledgeAgentPort = mock(KnowledgeAgentPort.class);
        workbookExporter = mock(WorkbookExporter.class);
        when(repository.finalizationReadiness(TASK_ID))
                .thenReturn(new GenerationTaskRepository.FinalizationReadiness(GenerationTaskStatus.FAILED, false));
        workflow = new GenerationWorkflow(repository, knowledgeAgentPort, workbookExporter,
                new ObjectMapper(), mock(TaskExecutionQueue.class), Runnable::run,
                traversalService, featureAuditService, frozenFeatureService);
    }

    @Test
    void regeneratesV2StructuredArtifactFromPersistedProjectionWithoutCallingKee() {
        StructuredWorkbookExportRequest rows = new StructuredWorkbookExportRequest(TASK_ID, List.of(), List.of());
        StructuredWorkbookRowSource rowSource = StructuredWorkbookRowSource.from(rows);
        WorkbookArtifact artifact = new WorkbookArtifact("artifact-safe", "a".repeat(64), Path.of("safe.xlsx"));
        when(repository.isV2Task(TASK_ID)).thenReturn(true);
        when(repository.structuredArtifactRegenerationBaseline(TASK_ID)).thenReturn("artifact-old");
        when(repository.structuredWorkbookRows(TASK_ID)).thenReturn(rowSource);
        when(workbookExporter.exportV2StructuredRows(rowSource)).thenReturn(artifact);

        assertThat(workflow.regenerateStructuredArtifact(TASK_ID)).isEqualTo(artifact);

        InOrder order = inOrder(repository, workbookExporter);
        order.verify(repository).structuredArtifactRegenerationBaseline(TASK_ID);
        order.verify(repository).structuredWorkbookRows(TASK_ID);
        order.verify(workbookExporter).exportV2StructuredRows(rowSource);
        order.verify(repository).replaceStructuredArtifact(TASK_ID, "artifact-old", artifact);
        verify(knowledgeAgentPort, never()).invoke(any());
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
        verify(repository, never()).failAuditingTask(eq(TASK_ID), anyString());
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

        verify(repository).failAuditingTask(eq(TASK_ID), eq("材料或审查处理未完成，未冻结功能范围"));
        verify(frozenFeatureService, never()).freeze(any(), any());
        verify(repository, never()).planFrozenBatches(any(), any(), any(), any());
    }

    @Test
    // [Req-ID]: REQ-CWR-004
    void persistsAndLogsOnlyTheSafeFinalReconciliationPageSummary() {
        CreateGenerationTaskRequest pending = pendingAllRequest();
        FinalReconciliationPageException pageFailure = FinalReconciliationPageException.exhausted(3, 12, 3,
                "https://internal.invalid/path?secret=red-team-only; candidateId=hidden");
        when(repository.request(TASK_ID)).thenReturn(pending);
        when(featureAuditService.audit(TASK_ID, pending)).thenThrow(pageFailure);
        when(repository.taskStatus(TASK_ID)).thenReturn(GenerationTaskStatus.AUDITING);

        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(GenerationWorkflow.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> events =
                new ch.qos.logback.core.read.ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            workflow.executeClaimed(new TaskExecutionClaim(TASK_ID, 1));
        } finally {
            logger.detachAppender(events);
            events.stop();
        }

        String expected = "最终双向核对第 3/12 个功能审核批次连续 3 次未通过：固定合同未满足";
        verify(repository).failAuditingTask(TASK_ID, expected);
        assertThat(events.list).extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .contains("Audit-stage task failed: " + expected)
                .noneMatch(message -> message.contains("internal.invalid") || message.contains("red-team-only")
                        || message.contains("candidateId"));
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

    /** [Req-ID]: REQ-CAG-007, REQ-CWR-001 */
    @Test
    void resumesOnlyTheRequeuedBatchWithoutInvokingAnEarlierAcceptedFrozenFeature() {
        FrozenFeatureTarget accepted = target("ff-accepted", 1, "订单查询", true);
        FrozenFeatureTarget requeued = target("ff-requeued", 2, "订单创建", true);
        CreateGenerationTaskRequest frozenAll = pendingAllRequest().withFrozenFeatures(List.of(accepted, requeued));
        GenerationTaskRepository.TaskExecutionWork requeuedWork = new GenerationTaskRepository.TaskExecutionWork(
                TASK_ID, "batch-requeued", "attempt-requeued-2", requeued.stableFeatureId(), frozenAll);

        when(repository.request(TASK_ID)).thenReturn(frozenAll);
        when(repository.requireQueuedWork(TASK_ID)).thenReturn(requeuedWork);
        when(repository.nextQueuedWork(TASK_ID)).thenReturn(java.util.Optional.of(requeuedWork), java.util.Optional.empty());
        when(repository.frozenFeatureTargets(TASK_ID)).thenReturn(List.of(accepted, requeued));
        when(repository.acceptedMarkdownRows(TASK_ID)).thenReturn(new MarkdownTaskRows(List.of(), List.of()));
        when(knowledgeAgentPort.invoke(any())).thenReturn(new KnowledgeAgentInvocationResult(
                "session-requeued", List.of(), validMarkdownFor("订单创建", "candidate-ff-requeued")));

        workflow.executeClaimed(new TaskExecutionClaim(TASK_ID, 1));

        ArgumentCaptor<KnowledgeAgentInvocation> invocation = ArgumentCaptor.forClass(KnowledgeAgentInvocation.class);
        verify(knowledgeAgentPort).prepareGenerationSession(any());
        verify(knowledgeAgentPort).invoke(invocation.capture());
        verify(knowledgeAgentPort).closePreparedSession();
        assertThat(invocation.getValue().prompt())
                .contains("仅生成当前功能路径：订单创建", "candidateIds=candidate-ff-requeued")
                .doesNotContain("仅生成当前功能路径：订单查询", "candidateIds=candidate-ff-accepted");
        verify(repository).startBatch("batch-requeued", "attempt-requeued-2");
        verify(repository, never()).startBatch("batch-accepted", "attempt-accepted");
        verify(repository, never()).acceptMarkdownBatch(eq("batch-accepted"), eq("attempt-accepted"), any());
    }

    @Test
    // [Req-ID]: REQ-CAG-007
    void keepsTheFirstAttemptPromptFreeOfRetryCorrectionFeedback() {
        String prompt = captureAllPrompt("attempt-first", Optional.empty());

        assertThat(prompt).isEqualTo("生成全部功能\n\n"
                + "仅生成当前功能路径：订单创建。不得生成其他功能。\n"
                + "只输出两个 H2 和两张 Markdown 表：## 需求与功能清单审查发现、## 测试用例；表头必须分别为"
                + "序号|对象/功能点|问题分类|证据对照和"
                + "用例名称|功能模块|前提约束|执行步骤|预期结果|对应需求内容。\n"
                + "测试用例表恰好两行，名称必须是：订单创建_正向、订单创建_反向。\n"
                + "功能模块精确填写为：订单创建。执行步骤和预期结果均用 <br> 分隔，并从 1 开始连续编号且一一对应。\n"
                + "正式需求内容必须是可读摘要加 <br>candidateIds=candidate-ff-retry"
                + "；candidateIds 只能使用该允许集合。仅基于通用经验时，对应需求内容必须精确为：依据通用经验，待确认。\n"
                + "审查表如有行，对象只能是当前功能路径或其叶子名，证据对照同样只能回显 candidateIds=candidate-ff-retry。");
    }

    @Test
    // [Req-ID]: REQ-CAG-007
    void preservesLegacySpecifiedFeaturePromptWithoutReadingRetryFeedback() {
        GenerationTaskRepository localRepository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort localKnowledgeAgentPort = mock(KnowledgeAgentPort.class);
        CreateGenerationTaskRequest request = specifiedFeatureRequest();
        GenerationTaskRepository.TaskExecutionWork work = new GenerationTaskRepository.TaskExecutionWork(
                TASK_ID, "batch-feature", "attempt-feature-retry", request.featureId(), request);
        GenerationWorkflow localWorkflow = new GenerationWorkflow(localRepository, localKnowledgeAgentPort,
                mock(WorkbookExporter.class), new ObjectMapper(), mock(TaskExecutionQueue.class), Runnable::run,
                mock(RequirementMaterialTraversalService.class), mock(FeatureAuditService.class), mock(FrozenFeatureService.class));

        when(localRepository.request(TASK_ID)).thenReturn(request);
        when(localRepository.requireQueuedWork(TASK_ID)).thenReturn(work);
        when(localRepository.nextQueuedWork(TASK_ID)).thenReturn(java.util.Optional.of(work), java.util.Optional.empty());
        when(localRepository.previousFailureReason("batch-feature", "attempt-feature-retry")).thenReturn(Optional.of(
                "Markdown contract invalid: expected no content after the final test-case table"));
        when(localRepository.finalizationReadiness(TASK_ID))
                .thenReturn(new GenerationTaskRepository.FinalizationReadiness(GenerationTaskStatus.FAILED, false));
        when(localKnowledgeAgentPort.invoke(any())).thenReturn(new KnowledgeAgentInvocationResult(
                "session-feature", List.of(), validMarkdownFor("订单查询", "candidate-feature")));

        localWorkflow.executeClaimed(new TaskExecutionClaim(TASK_ID, 1));

        ArgumentCaptor<KnowledgeAgentInvocation> invocation = ArgumentCaptor.forClass(KnowledgeAgentInvocation.class);
        verify(localKnowledgeAgentPort).invoke(invocation.capture());
        assertThat(invocation.getValue().prompt()).isEqualTo("生成指定功能\n本批次功能点：订单查询");
        verify(localRepository, never()).previousFailureReason("batch-feature", "attempt-feature-retry");
    }

    @Test
    // [Req-ID]: REQ-CAG-007
    void appendsOnlyWhitelistedFixedCorrectionsForKnownRetryFailures() {
        Map<String, String> corrections = Map.ofEntries(
                Map.entry("General-experience content must exactly equal '依据通用经验，待确认'",
                        "上一轮校验未通过：对应需求内容只能二选一：\n"
                                + "正式材料：可读摘要+<br>candidateIds\n"
                                + "通用经验：依据通用经验，待确认\n"
                                + "若选择通用经验，冒号后到行尾的全部内容必须到此结束；严禁追加 <br>、candidateIds、引号、句号或任何其他文字，不得混合两种写法。"),
                Map.entry("Execution steps and expected results must have the same numbered items",
                        "上一轮校验未通过：执行步骤和预期结果必须都从 1 连续编号，并保持编号逐项一一对应。"),
                Map.entry("Audit evidence must retain candidateIds for the frozen target",
                        "上一轮校验未通过：审查证据对照必须保留当前冻结目标允许的 candidateIds。"),
                Map.entry("Markdown contract invalid: expected no content after the final test-case table",
                        "上一轮校验未通过：测试用例表结束后不得再输出任何内容。"),
                Map.entry("Markdown contract invalid: expected text-only table cells with only <br> line separators",
                        "上一轮校验未通过：表格单元格只能包含文本，换行只能使用 <br>。"),
                Map.entry("Requirement content must retain candidateIds for the frozen target",
                        "上一轮校验未通过：正式需求内容必须保留当前冻结目标允许的 candidateIds。"),
                Map.entry("Requirement content must reference only candidates of the frozen target",
                        "上一轮校验未通过：正式需求内容只能引用当前冻结目标允许的 candidateIds。"));

        corrections.forEach((failureReason, correction) -> {
            String prompt = captureAllPrompt("attempt-retry-" + failureReason.hashCode(), Optional.of(failureReason));

            assertThat(prompt).contains(correction).doesNotContain(failureReason);
        });
    }

    @Test
    // [Req-ID]: REQ-CAG-007
    void neverReturnsUnknownOrSuspiciousFailureReasonsToTheRetryPrompt() {
        String untrustedReason = "database timeout https://internal.invalid/retry?token=opaque-secret-778";

        String prompt = captureAllPrompt("attempt-retry-unknown", Optional.of(untrustedReason));

        assertThat(prompt)
                .contains("上一轮未通过固定输出合同校验")
                .doesNotContain(untrustedReason, "internal.invalid", "opaque-secret-778");
    }

    private static String captureAllPrompt(String attemptId, Optional<String> previousFailureReason) {
        GenerationTaskRepository localRepository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort localKnowledgeAgentPort = mock(KnowledgeAgentPort.class);
        FrozenFeatureTarget target = target("ff-retry", 1, "订单创建", true);
        CreateGenerationTaskRequest frozenAll = pendingAllRequest().withFrozenFeatures(List.of(target));
        GenerationTaskRepository.TaskExecutionWork work = new GenerationTaskRepository.TaskExecutionWork(
                TASK_ID, "batch-retry", attemptId, target.stableFeatureId(), frozenAll);
        GenerationWorkflow localWorkflow = new GenerationWorkflow(localRepository, localKnowledgeAgentPort,
                mock(WorkbookExporter.class), new ObjectMapper(), mock(TaskExecutionQueue.class), Runnable::run,
                mock(RequirementMaterialTraversalService.class), mock(FeatureAuditService.class), mock(FrozenFeatureService.class));

        when(localRepository.request(TASK_ID)).thenReturn(frozenAll);
        when(localRepository.requireQueuedWork(TASK_ID)).thenReturn(work);
        when(localRepository.nextQueuedWork(TASK_ID)).thenReturn(java.util.Optional.of(work), java.util.Optional.empty());
        when(localRepository.frozenFeatureTargets(TASK_ID)).thenReturn(List.of(target));
        when(localRepository.previousFailureReason("batch-retry", attemptId)).thenReturn(previousFailureReason);
        when(localRepository.finalizationReadiness(TASK_ID))
                .thenReturn(new GenerationTaskRepository.FinalizationReadiness(GenerationTaskStatus.FAILED, false));
        when(localKnowledgeAgentPort.invoke(any())).thenReturn(new KnowledgeAgentInvocationResult(
                "session-retry", List.of(), validMarkdownFor("订单创建", "candidate-ff-retry")));

        localWorkflow.executeClaimed(new TaskExecutionClaim(TASK_ID, 1));

        ArgumentCaptor<KnowledgeAgentInvocation> invocation = ArgumentCaptor.forClass(KnowledgeAgentInvocation.class);
        verify(localKnowledgeAgentPort).invoke(invocation.capture());
        verify(localRepository).previousFailureReason("batch-retry", attemptId);
        return invocation.getValue().prompt();
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

    private static String validMarkdownFor(String featureName, String candidateId) {
        return """
                ## 需求与功能清单审查发现
                | 序号 | 对象/功能点 | 问题分类 | 证据对照 |
                | --- | --- | --- | --- |
                | 1 | %s | 未发现问题 | candidateIds=%s |

                ## 测试用例
                | 用例名称 | 功能模块 | 前提约束 | 执行步骤 | 预期结果 | 对应需求内容 |
                | --- | --- | --- | --- | --- | --- |
                | %s_正向 | %s | 已登录 | 1. 输入内容 | 1. 展示结果 | 需求摘要<br>candidateIds=%s |
                | %s_反向 | %s | 已登录 | 1. 输入异常内容 | 1. 提示校验失败 | 依据通用经验，待确认 |
                """.formatted(featureName, candidateId, featureName, featureName, candidateId, featureName, featureName);
    }
}
