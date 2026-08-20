package com.testcaseagent.task;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testcaseagent.export.WorkbookExporter;
import com.testcaseagent.featureaudit.FeatureAuditService;
import com.testcaseagent.featureaudit.FrozenFeatureService;
import com.testcaseagent.featureaudit.RequirementMaterialTraversalService;
import com.testcaseagent.knowledgeagent.KnowledgeAgentPort;
import com.testcaseagent.testcase.GenerationTaskMode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

/** Production routing guard for the new structured ALL workflow. [Req-ID]: REQ-STG-001, REQ-STG-007 */
class GenerationWorkflowStructuredRoutingTest {

    @Test
    void allModeUsesTheStructuredCoordinatorAndNeverFallsBackToTheMarkdownAgentPath() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        KnowledgeAgentPort legacyAgent = mock(KnowledgeAgentPort.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        FeatureAuditService legacyAudit = mock(FeatureAuditService.class);
        FrozenFeatureService legacyFreeze = mock(FrozenFeatureService.class);
        WorkbookExporter legacyExporter = mock(WorkbookExporter.class);
        StructuredAllGenerationCoordinator structured = mock(StructuredAllGenerationCoordinator.class);
        CreateGenerationTaskRequest request = mock(CreateGenerationTaskRequest.class);
        when(request.taskMode()).thenReturn(GenerationTaskMode.ALL);
        when(request.featureIds()).thenReturn(List.of());
        when(repository.request("task-structured")).thenReturn(request);
        GenerationWorkflow workflow = new GenerationWorkflow(repository, legacyAgent, legacyExporter,
                new ObjectMapper(), mock(TaskExecutionQueue.class), new SyncTaskExecutor(), traversal,
                legacyAudit, legacyFreeze, structured);

        workflow.executeClaimed(new TaskExecutionClaim("task-structured", 1));

        verify(structured).execute("task-structured", request);
        verify(legacyAgent, never()).prepareGenerationSession(org.mockito.ArgumentMatchers.any());
        verify(legacyAgent, never()).invoke(org.mockito.ArgumentMatchers.any());
        verify(traversal, never()).traverse(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(legacyAudit, never()).audit(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        verify(legacyFreeze, never()).freeze(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        verify(repository, never()).requireQueuedWork(org.mockito.ArgumentMatchers.anyString());
        verify(legacyExporter, never()).exportMarkdown(org.mockito.ArgumentMatchers.any());
    }
}
