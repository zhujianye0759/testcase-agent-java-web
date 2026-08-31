package com.testcaseagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testcaseagent.export.WorkbookExporter;
import com.testcaseagent.featureaudit.FeatureAuditService;
import com.testcaseagent.featureaudit.FrozenFeatureService;
import com.testcaseagent.featureaudit.RequirementMaterialTraversalService;
import com.testcaseagent.fewshot.ExampleScope;
import com.testcaseagent.knowledgeagent.KnowledgeAgentPort;
import com.testcaseagent.scope.RequirementDocumentCoordinate;
import com.testcaseagent.scope.RequirementScope;
import com.testcaseagent.testcase.FewShotPolicy;
import com.testcaseagent.testcase.GenerationTaskMode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Public workflow routing seam for generation V2. [Req-ID]: REQ-TGV2-001, REQ-TGV2-002 */
class GenerationWorkflowV2RoutingTest {

    @Test
    void creatingV2PersistsNoLegacyGenerationBatchPlan() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        CreateGenerationTaskRequest request = request();
        when(repository.createTaskIfAbsent(anyString(), anyList(), same(request), anyString()))
                .thenReturn(new GenerationTaskRepository.TaskCreation("task-v2", true));
        GenerationWorkflow workflow = new GenerationWorkflow(repository, mock(KnowledgeAgentPort.class),
                mock(WorkbookExporter.class), new ObjectMapper(), mock(TaskExecutionQueue.class), Runnable::run,
                mock(RequirementMaterialTraversalService.class), mock(FeatureAuditService.class),
                mock(FrozenFeatureService.class), mock(StructuredAllGenerationCoordinator.class));

        assertThat(workflow.create(request)).isEqualTo("task-v2");

        verify(repository).createTaskIfAbsent(anyString(), eq(List.of()), same(request), anyString());
    }

    @Test
    void routesANonemptyApprovedScopeToTheV2CoordinatorWithoutLegacyBatchOrAuditCalls() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort legacyAgent = mock(KnowledgeAgentPort.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        FeatureAuditService audit = mock(FeatureAuditService.class);
        FrozenFeatureService freeze = mock(FrozenFeatureService.class);
        StructuredAllGenerationCoordinator coordinator = mock(StructuredAllGenerationCoordinator.class);
        CreateGenerationTaskRequest request = request();
        when(repository.request("task-v2")).thenReturn(request);
        GenerationWorkflow workflow = new GenerationWorkflow(repository, legacyAgent, mock(WorkbookExporter.class),
                new ObjectMapper(), mock(TaskExecutionQueue.class), Runnable::run, traversal, audit, freeze,
                coordinator);

        workflow.executeClaimed(new TaskExecutionClaim("task-v2", 1));

        verify(coordinator).execute("task-v2", request);
        verify(repository, never()).requireQueuedWork("task-v2");
        verify(traversal, never()).traverse("task-v2", request, false);
        verify(legacyAgent, never()).invoke(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void historicalV1TasksRemainReadOnlyThroughEveryPublicMutationEntry() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        when(repository.isV2Task("historical-v1")).thenReturn(false);
        GenerationWorkflow workflow = new GenerationWorkflow(repository, mock(KnowledgeAgentPort.class),
                mock(WorkbookExporter.class), new ObjectMapper(), mock(TaskExecutionQueue.class), Runnable::run,
                mock(RequirementMaterialTraversalService.class), mock(FeatureAuditService.class),
                mock(FrozenFeatureService.class), mock(StructuredAllGenerationCoordinator.class));

        assertThatThrownBy(() -> workflow.cancel("historical-v1"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> workflow.retryFailedBatches("historical-v1"))
                .isInstanceOf(GenerationTaskRetryConflictException.class);
        assertThatThrownBy(() -> workflow.regenerateStructuredArtifact("historical-v1"))
                .isInstanceOf(IllegalStateException.class);

        verify(repository, never()).requestCancellation("historical-v1");
        verify(repository, never()).retryFailedBatches("historical-v1");
        verify(repository, never()).structuredArtifactRegenerationBaseline("historical-v1");
    }

    static CreateGenerationTaskRequest request() {
        ApprovedFunctionScope approved = new ApprovedFunctionScope("scope-v2", List.of(
                new ApprovedFunctionScope.ApprovedFunction("function-a", "提交申请", "业务/提交申请", "")));
        RequirementScope scope = new RequirementScope("kb", "system", "version", "admission_material", "project",
                List.of(new RequirementDocumentCoordinate("requirements", "requirements_spec")));
        return new CreateGenerationTaskRequest(GenerationTaskMode.ALL, "function-a", List.of("function-a"),
                Map.of("function-a", "业务/提交申请"), FewShotPolicy.NONE, "2.0", "2.0", "agent", scope,
                new ExampleScope("example-kb", List.of("example")), List.of("requirements_spec"), "生成",
                new GenerationContractVersions("2.0", "2.0", "2.0"), approved);
    }
}
