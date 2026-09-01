package com.testcaseagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testcaseagent.export.StructuredWorkbookExportRequest;
import com.testcaseagent.export.StructuredWorkbookRowSource;
import com.testcaseagent.export.WorkbookArtifact;
import com.testcaseagent.export.WorkbookExporter;
import com.testcaseagent.featureaudit.MaterialInventoryDocument;
import com.testcaseagent.featureaudit.MaterialInventoryUnit;
import com.testcaseagent.featureaudit.RequirementMaterialTraversalService;
import com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignV2Result;
import com.testcaseagent.knowledgeagent.RequirementFactExtractionV2Invocation;
import com.testcaseagent.knowledgeagent.RequirementFactExtractionV2Result;
import com.testcaseagent.knowledgeagent.StructuredSkillExecutionPort;
import com.testcaseagent.knowledgeagent.StructuredSkillExecutionException;
import com.testcaseagent.knowledgeagent.StructuredSkillErrorType;
import com.testcaseagent.knowledgeagent.StructuredSkillSessionPort;
import com.testcaseagent.knowledgeagent.StructuredSkillSuccess;
import com.testcaseagent.knowledgeagent.StructuredSkillSuccessEnvelope;
import com.testcaseagent.structuredgeneration.StructuredCoverageStatus;
import com.testcaseagent.structuredgeneration.StructuredGenerationAcceptanceStore;
import com.testcaseagent.structuredgeneration.StructuredProcessingStatus;
import com.testcaseagent.structuredgeneration.V2GenerationPlanner;
import com.testcaseagent.validation.StructuredValidationException;
import com.testcaseagent.validation.StructuredValidationFailure;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** [Req-ID]: REQ-TGV2-002, REQ-TGV2-003, REQ-TGV2-005~REQ-TGV2-010 */
class V2StructuredAllGenerationCoordinatorTest {

    /** [Req-ID]: REQ-TGV2-016 */
    @Test
    void executesReviewedPendingPointVerbatimAndDoesNotSubstituteTheNoFactFallback() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        var point = new ApprovedFunctionScope.ApprovedTestPoint("reviewed-point", "function-a",
                ApprovedFunctionScope.ApprovedTestPointType.NORMAL_BEHAVIOR,
                ApprovedFunctionScope.ApprovedTestPointSource.GENERAL_EXPERIENCE,
                ApprovedFunctionScope.ApprovedTestPointStatus.PENDING_CONFIRMATION,
                "验证待确认条件", List.of("触发时机尚未确认"));
        CreateGenerationTaskRequest request = requestWithPoints(List.of(point));
        stubApprovedScopeVersion(repository, request);
        var function = request.approvedFunctionScope().functions().get(0);
        when(repository.approvedFunctions("task-v2")).thenReturn(List.of(function));
        when(repository.approvedTestPoints("task-v2")).thenReturn(List.of(point));
        when(repository.hasCompleteMaterialInventory("task-v2", request.requirementScope())).thenReturn(true);
        stubBoundedInventory(repository, material());
        when(sessions.openStructuredSession()).thenReturn("session-v2");
        when(store.register(any())).thenAnswer(invocation -> {
            StructuredGenerationAcceptanceStore.WorkRegistration registration = invocation.getArgument(0);
            return "requirement-fact-extraction".equals(registration.skillName()) ? "fact-work" : "design-work";
        });
        when(store.isCompleted("fact-work")).thenReturn(true);
        when(store.claimRegistered("task-v2", "design-work", "structured-v2-worker"))
                .thenReturn(java.util.Optional.of(claim("design-work", "task-v2")));
        when(store.acceptedRequirementFactsV2Page("task-v2", "function-a", "", 100)).thenReturn(List.of());
        when(skills.designFunctionalTestcasesV2(any())).thenAnswer(invocation -> {
            var call = (com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignV2Invocation)
                    invocation.getArgument(0);
            return success("functional-testcase-design", new FunctionalTestcaseDesignV2Result(
                    call.input().functionKey(), call.input().testPoint().testPointKey(),
                    FunctionalTestcaseDesignV2Result.GenerationOutcome.UNABLE_TO_GENERATE,
                    List.of("触发时机尚未确认"), List.of()));
        });
        when(store.v2AggregateState("task-v2")).thenReturn(
                new StructuredGenerationAcceptanceStore.V2AggregateState(2, 2, 0, 0, 1, 0, 0, 0, 1));
        StructuredWorkbookRowSource rows = StructuredWorkbookRowSource.from(
                new StructuredWorkbookExportRequest("task-v2", List.of(), List.of()));
        when(repository.structuredWorkbookRows("task-v2")).thenReturn(rows);
        WorkbookArtifact artifact = new WorkbookArtifact("artifact-v2", "a".repeat(64), Path.of("artifact.xlsx"));
        when(exporter.exportV2StructuredRows(rows)).thenReturn(artifact);

        new V2StructuredAllGenerationCoordinator(repository, traversal, skills, sessions, store, exporter,
                new ObjectMapper(), noOpHeartbeat()).execute("task-v2", request);

        ArgumentCaptor<com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignV2Invocation> call =
                ArgumentCaptor.forClass(com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignV2Invocation.class);
        verify(skills).designFunctionalTestcasesV2(call.capture());
        assertThat(call.getValue().input().testPoint().testPointKey()).isEqualTo("reviewed-point");
        assertThat(call.getValue().input().testPoint().description()).isEqualTo("验证待确认条件");
        assertThat(call.getValue().input().testPoint().missingInformation())
                .containsExactly("触发时机尚未确认");
        assertThat(call.getValue().input().requirementFacts()).isEmpty();
        verify(store, never()).registerMissingFactFallback(any());
    }

    /** [Req-ID]: REQ-TGV2-016 */
    @Test
    void rejectsReviewedPointSnapshotDriftBeforeOpeningAKeeSession() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        var point = new ApprovedFunctionScope.ApprovedTestPoint("reviewed-point", "function-a",
                ApprovedFunctionScope.ApprovedTestPointType.NORMAL_BEHAVIOR,
                ApprovedFunctionScope.ApprovedTestPointSource.GENERAL_EXPERIENCE,
                ApprovedFunctionScope.ApprovedTestPointStatus.PENDING_CONFIRMATION,
                "验证待确认条件", List.of("触发时机尚未确认"));
        CreateGenerationTaskRequest request = requestWithPoints(List.of(point));
        stubApprovedScopeVersion(repository, request);
        when(repository.hasExactApprovedScopeSnapshot("task-v2")).thenReturn(false);
        when(repository.approvedFunctions("task-v2"))
                .thenReturn(request.approvedFunctionScope().functions());
        when(repository.approvedTestPoints("task-v2")).thenReturn(List.of());

        assertThatThrownBy(() -> new V2StructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                store, exporter, new ObjectMapper(), noOpHeartbeat()).execute("task-v2", request))
                .isInstanceOf(StructuredValidationException.class);

        verify(sessions, never()).openStructuredSession();
        verify(skills, never()).designFunctionalTestcasesV2(any());
        verify(skills, never()).extractRequirementFactsV2(any());
    }

    /** [Req-ID]: REQ-TGV2-016 */
    @Test
    void rejectsANonCanonicalRawApprovedScopeSnapshotBeforeChangingStateOrOpeningKee() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest request = request();
        when(repository.hasExactApprovedScopeSnapshot("task-v2")).thenReturn(false);

        assertThatThrownBy(() -> new V2StructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                store, exporter, new ObjectMapper(), noOpHeartbeat()).execute("task-v2", request))
                .isInstanceOf(StructuredValidationException.class);

        verify(store, never()).updateTaskState(any(), any());
        verify(sessions, never()).openStructuredSession();
        verify(skills, never()).extractRequirementFactsV2(any());
        verify(skills, never()).designFunctionalTestcasesV2(any());
    }

    /** [Req-ID]: REQ-TGV2-016 */
    @Test
    void rejectsApprovedScopeVersionDriftBeforeOpeningAKeeSession() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest request = request();
        when(repository.hasExactApprovedScopeSnapshot("task-v2")).thenReturn(true);
        when(repository.hasDistinctApprovedAndDerivedTestPointKeys("task-v2")).thenReturn(true);
        when(repository.approvedScopeVersion("task-v2")).thenReturn("different-scope-version");
        when(repository.approvedFunctions("task-v2")).thenReturn(request.approvedFunctionScope().functions());
        when(repository.approvedTestPoints("task-v2")).thenReturn(List.of());
        when(repository.hasCompleteMaterialInventory("task-v2", request.requirementScope())).thenReturn(true);
        stubBoundedInventory(repository, material());
        when(sessions.openStructuredSession()).thenReturn("session-v2");
        when(store.register(any())).thenAnswer(invocation ->
                ((StructuredGenerationAcceptanceStore.WorkRegistration) invocation.getArgument(0)).identityKey());
        when(store.isCompleted(any())).thenReturn(true);
        when(store.acceptedRequirementFactsV2Page("task-v2", "function-a", "", 100)).thenReturn(List.of());
        when(store.v2AggregateState("task-v2")).thenReturn(
                new StructuredGenerationAcceptanceStore.V2AggregateState(2, 2, 0, 0, 1, 0, 0, 0, 1));
        StructuredWorkbookRowSource rows = StructuredWorkbookRowSource.from(
                new StructuredWorkbookExportRequest("task-v2", List.of(), List.of()));
        when(repository.structuredWorkbookRows("task-v2")).thenReturn(rows);
        when(exporter.exportV2StructuredRows(rows)).thenReturn(
                new WorkbookArtifact("artifact-v2", "a".repeat(64), Path.of("artifact.xlsx")));

        assertThatThrownBy(() -> new V2StructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                store, exporter, new ObjectMapper(), noOpHeartbeat()).execute("task-v2", request))
                .isInstanceOf(StructuredValidationException.class);

        verify(sessions, never()).openStructuredSession();
        verify(skills, never()).extractRequirementFactsV2(any());
        verify(skills, never()).designFunctionalTestcasesV2(any());
    }

    /** [Req-ID]: REQ-TGV2-016 */
    @Test
    void rejectsReviewedPointKeyThatCollidesWithAFactDerivedPointBeforeDesignInvocation() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest base = request();
        var function = base.approvedFunctionScope().functions().get(0);
        var fact = persistedFact("fact-collision");
        String derivedPointKey = new V2GenerationPlanner().testPoint("task-v2", function, fact)
                .input().testPoint().testPointKey();
        var reviewed = new ApprovedFunctionScope.ApprovedTestPoint(derivedPointKey, function.functionKey(),
                ApprovedFunctionScope.ApprovedTestPointType.NORMAL_BEHAVIOR,
                ApprovedFunctionScope.ApprovedTestPointSource.GENERAL_EXPERIENCE,
                ApprovedFunctionScope.ApprovedTestPointStatus.PENDING_CONFIRMATION,
                "验证待确认条件", List.of("触发时机尚未确认"));
        CreateGenerationTaskRequest request = requestWithPoints(List.of(reviewed));
        stubApprovedScopeVersion(repository, request);
        when(repository.hasDistinctApprovedAndDerivedTestPointKeys("task-v2")).thenReturn(false);
        when(repository.approvedFunctions("task-v2")).thenReturn(List.of(function));
        when(repository.approvedTestPoints("task-v2")).thenReturn(List.of(reviewed));
        when(repository.hasCompleteMaterialInventory("task-v2", request.requirementScope())).thenReturn(true);
        stubBoundedInventory(repository, material());
        when(sessions.openStructuredSession()).thenReturn("session-v2");
        when(store.register(any())).thenAnswer(invocation -> {
            StructuredGenerationAcceptanceStore.WorkRegistration registration = invocation.getArgument(0);
            return "requirement-fact-extraction".equals(registration.skillName()) ? "fact-work" : "design-work";
        });
        when(store.isCompleted(any())).thenReturn(true);
        when(store.acceptedRequirementFactsV2Page("task-v2", function.functionKey(), "", 100))
                .thenReturn(List.of(fact));
        when(store.acceptedRequirementFactsV2Page("task-v2", function.functionKey(), fact.factKey(), 100))
                .thenReturn(List.of());
        when(store.v2AggregateState("task-v2")).thenReturn(
                new StructuredGenerationAcceptanceStore.V2AggregateState(3, 3, 0, 0, 1, 1, 1, 0, 1));
        StructuredWorkbookRowSource rows = StructuredWorkbookRowSource.from(
                new StructuredWorkbookExportRequest("task-v2", List.of(), List.of()));
        when(repository.structuredWorkbookRows("task-v2")).thenReturn(rows);
        when(exporter.exportV2StructuredRows(rows)).thenReturn(
                new WorkbookArtifact("artifact-v2", "a".repeat(64), Path.of("artifact.xlsx")));

        assertThatThrownBy(() -> new V2StructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                store, exporter, new ObjectMapper(), noOpHeartbeat()).execute("task-v2", request))
                .isInstanceOf(StructuredValidationException.class);

        verify(sessions, never()).openStructuredSession();
        verify(store, never()).acceptedRequirementFactsV2Page(any(), any(), any(), anyInt());
        verify(skills, never()).designFunctionalTestcasesV2(any());
    }

    /** [Req-ID]: REQ-TGV2-008, REQ-TGV2-016 */
    @Test
    void restartRegistersButDoesNotReinvokeAnAlreadyCompletedReviewedPoint() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        var point = new ApprovedFunctionScope.ApprovedTestPoint("reviewed-point", "function-a",
                ApprovedFunctionScope.ApprovedTestPointType.NORMAL_BEHAVIOR,
                ApprovedFunctionScope.ApprovedTestPointSource.GENERAL_EXPERIENCE,
                ApprovedFunctionScope.ApprovedTestPointStatus.PENDING_CONFIRMATION,
                "验证待确认条件", List.of("触发时机尚未确认"));
        CreateGenerationTaskRequest request = requestWithPoints(List.of(point));
        stubApprovedScopeVersion(repository, request);
        var function = request.approvedFunctionScope().functions().get(0);
        when(repository.approvedFunctions("task-v2")).thenReturn(List.of(function));
        when(repository.approvedTestPoints("task-v2")).thenReturn(List.of(point));
        when(repository.hasCompleteMaterialInventory("task-v2", request.requirementScope())).thenReturn(true);
        stubBoundedInventory(repository, material());
        when(sessions.openStructuredSession()).thenReturn("session-v2");
        when(store.register(any())).thenAnswer(invocation -> {
            StructuredGenerationAcceptanceStore.WorkRegistration registration = invocation.getArgument(0);
            return "requirement-fact-extraction".equals(registration.skillName()) ? "fact-work" : "design-work";
        });
        when(store.isCompleted("fact-work")).thenReturn(true);
        when(store.isCompleted("design-work")).thenReturn(true);
        when(store.acceptedRequirementFactsV2Page("task-v2", "function-a", "", 100)).thenReturn(List.of());
        when(store.v2AggregateState("task-v2")).thenReturn(
                new StructuredGenerationAcceptanceStore.V2AggregateState(2, 2, 0, 0, 1, 0, 0, 0, 1));
        StructuredWorkbookRowSource rows = StructuredWorkbookRowSource.from(
                new StructuredWorkbookExportRequest("task-v2", List.of(), List.of()));
        when(repository.structuredWorkbookRows("task-v2")).thenReturn(rows);
        when(exporter.exportV2StructuredRows(rows)).thenReturn(
                new WorkbookArtifact("artifact-v2", "a".repeat(64), Path.of("artifact.xlsx")));

        new V2StructuredAllGenerationCoordinator(repository, traversal, skills, sessions, store, exporter,
                new ObjectMapper(), noOpHeartbeat()).execute("task-v2", request);

        verify(skills, never()).designFunctionalTestcasesV2(any());
        verify(store, never()).registerMissingFactFallback(any());
    }

    /** [Req-ID]: REQ-TGV2-016 */
    @Test
    void invokesReviewedPointsInCallerOrderAcrossDifferentFunctions() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        var functionA = request().approvedFunctionScope().functions().get(0);
        var functionB = new ApprovedFunctionScope.ApprovedFunction(
                "function-b", "功能B", "范围/功能B", "第二个功能");
        var pointB = new ApprovedFunctionScope.ApprovedTestPoint("point-b", "function-b",
                ApprovedFunctionScope.ApprovedTestPointType.BOUNDARY_VALUE,
                ApprovedFunctionScope.ApprovedTestPointSource.GENERAL_EXPERIENCE,
                ApprovedFunctionScope.ApprovedTestPointStatus.PENDING_CONFIRMATION,
                "验证功能B的待确认边界", List.of("边界值尚未确认"));
        var pointA = new ApprovedFunctionScope.ApprovedTestPoint("point-a", "function-a",
                ApprovedFunctionScope.ApprovedTestPointType.STATE_TRANSITION,
                ApprovedFunctionScope.ApprovedTestPointSource.GENERAL_EXPERIENCE,
                ApprovedFunctionScope.ApprovedTestPointStatus.PENDING_CONFIRMATION,
                "验证功能A的待确认状态", List.of("初始状态尚未确认"));
        CreateGenerationTaskRequest request = requestWithScope(
                new ApprovedFunctionScope("scope-2", List.of(functionA, functionB), List.of(pointB, pointA)));
        stubApprovedScopeVersion(repository, request);
        when(repository.approvedFunctions("task-v2")).thenReturn(List.of(functionA, functionB));
        when(repository.approvedTestPoints("task-v2")).thenReturn(List.of(pointB, pointA));
        when(repository.hasCompleteMaterialInventory("task-v2", request.requirementScope())).thenReturn(true);
        stubBoundedInventory(repository, material());
        when(sessions.openStructuredSession()).thenReturn("session-v2");
        when(store.register(any())).thenAnswer(invocation -> {
            StructuredGenerationAcceptanceStore.WorkRegistration registration = invocation.getArgument(0);
            return "requirement-fact-extraction".equals(registration.skillName())
                    ? "fact-" + registration.functionKey()
                    : "design-" + registration.testPointKey();
        });
        when(store.isCompleted("fact-function-a")).thenReturn(true);
        when(store.isCompleted("fact-function-b")).thenReturn(true);
        when(store.claimRegistered("task-v2", "design-point-b", "structured-v2-worker"))
                .thenReturn(java.util.Optional.of(claim("design-point-b", "task-v2")));
        when(store.claimRegistered("task-v2", "design-point-a", "structured-v2-worker"))
                .thenReturn(java.util.Optional.of(claim("design-point-a", "task-v2")));
        when(store.acceptedRequirementFactsV2Page("task-v2", "function-a", "", 100)).thenReturn(List.of());
        when(store.acceptedRequirementFactsV2Page("task-v2", "function-b", "", 100)).thenReturn(List.of());
        when(skills.designFunctionalTestcasesV2(any())).thenAnswer(invocation -> {
            var call = (com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignV2Invocation)
                    invocation.getArgument(0);
            return success("functional-testcase-design", new FunctionalTestcaseDesignV2Result(
                    call.input().functionKey(), call.input().testPoint().testPointKey(),
                    FunctionalTestcaseDesignV2Result.GenerationOutcome.UNABLE_TO_GENERATE,
                    call.input().testPoint().missingInformation(), List.of()));
        });
        when(store.v2AggregateState("task-v2")).thenReturn(
                new StructuredGenerationAcceptanceStore.V2AggregateState(4, 4, 0, 0, 2, 0, 0, 0, 2));
        StructuredWorkbookRowSource rows = StructuredWorkbookRowSource.from(
                new StructuredWorkbookExportRequest("task-v2", List.of(), List.of()));
        when(repository.structuredWorkbookRows("task-v2")).thenReturn(rows);
        when(exporter.exportV2StructuredRows(rows)).thenReturn(
                new WorkbookArtifact("artifact-v2", "a".repeat(64), Path.of("artifact.xlsx")));

        new V2StructuredAllGenerationCoordinator(repository, traversal, skills, sessions, store, exporter,
                new ObjectMapper(), noOpHeartbeat()).execute("task-v2", request);

        ArgumentCaptor<com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignV2Invocation> calls =
                ArgumentCaptor.forClass(com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignV2Invocation.class);
        verify(skills, org.mockito.Mockito.times(2)).designFunctionalTestcasesV2(calls.capture());
        assertThat(calls.getAllValues().stream()
                .map(call -> call.input().testPoint().testPointKey()).toList())
                .containsExactly("point-b", "point-a");
        verify(store, never()).registerMissingFactFallback(any());
    }

    @Test
    void persistsTheExactAllowlistedStageForAnOuterPlanningFailure() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest request = request();
        stubApprovedScopeVersion(repository, request);
        when(repository.approvedFunctions("task-v2")).thenThrow(new IllegalStateException("sensitive details"));

        var coordinator = new V2StructuredAllGenerationCoordinator(repository, traversal, skills, sessions, store,
                exporter, new ObjectMapper(), noOpHeartbeat());

        assertThatThrownBy(() -> coordinator.execute("task-v2", request))
                .isInstanceOf(StructuredValidationException.class);
        ArgumentCaptor<StructuredValidationFailure> failure = ArgumentCaptor.forClass(StructuredValidationFailure.class);
        verify(repository).failStructuredTask(org.mockito.ArgumentMatchers.eq("task-v2"),
                org.mockito.ArgumentMatchers.eq(StructuredCoverageStatus.PENDING), failure.capture());
        assertThat(failure.getValue().path()).isEqualTo("$.task_start_state_resume");
        assertThat(failure.getValue().toString()).doesNotContain("sensitive details");
    }

    @Test
    void callsOnlyTheTwoV2SkillsWithOneDocumentFactsAndContinuesToAnUnablePoint() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        StructuredWorkLeaseHeartbeat heartbeat = noOpHeartbeat();
        CreateGenerationTaskRequest request = request();
        stubApprovedScopeVersion(repository, request);
        MaterialInventoryDocument material = material();
        ApprovedFunctionScope.ApprovedFunction function = request.approvedFunctionScope().functions().get(0);
        when(repository.approvedFunctions("task-v2")).thenReturn(List.of(function));
        when(repository.hasCompleteMaterialInventory("task-v2", request.requirementScope())).thenReturn(false);
        when(traversal.traverse("task-v2", request, false))
                .thenReturn(new RequirementMaterialTraversalService.TraversalResult(List.of(material)));
        stubBoundedInventory(repository, material);
        when(sessions.openStructuredSession()).thenReturn("session-v2");
        when(store.register(any())).thenAnswer(invocation -> {
            StructuredGenerationAcceptanceStore.WorkRegistration registration = invocation.getArgument(0);
            return registration.operationName() + "-work";
        });
        when(store.registerMissingFactFallback(any())).thenAnswer(invocation ->
                ((V2GenerationPlanner.TestPointPlan) invocation.getArgument(0)).registration().operationName()
                        + "-work");
        when(store.claimRegistered(any(), any(), any())).thenAnswer(invocation -> java.util.Optional.of(
                claim(invocation.getArgument(1), invocation.getArgument(0))));
        when(skills.extractRequirementFactsV2(any())).thenAnswer(invocation -> {
            RequirementFactExtractionV2Invocation call = invocation.getArgument(0);
            return success("requirement-fact-extraction", new RequirementFactExtractionV2Result(
                    call.input().functionKey(), call.input().windowKey(), List.of(), List.of()));
        });
        when(store.acceptedRequirementFactsV2("task-v2", "function-a")).thenReturn(List.of());
        when(skills.designFunctionalTestcasesV2(any())).thenAnswer(invocation -> {
            var call = (com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignV2Invocation) invocation.getArgument(0);
            return success("functional-testcase-design", new FunctionalTestcaseDesignV2Result(
                    call.input().functionKey(), call.input().testPoint().testPointKey(),
                    FunctionalTestcaseDesignV2Result.GenerationOutcome.UNABLE_TO_GENERATE,
                    List.of("缺少可引用的正式需求事实"), List.of()));
        });
        when(store.v2AggregateState("task-v2")).thenReturn(
                new StructuredGenerationAcceptanceStore.V2AggregateState(2, 2, 0, 0, 1, 0, 0, 0, 1));
        StructuredWorkbookExportRequest rows = new StructuredWorkbookExportRequest("task-v2", List.of(), List.of());
        StructuredWorkbookRowSource rowSource = StructuredWorkbookRowSource.from(rows);
        when(repository.structuredWorkbookRows("task-v2")).thenReturn(rowSource);
        WorkbookArtifact artifact = new WorkbookArtifact("artifact-v2", "a".repeat(64), Path.of("artifact.xlsx"));
        when(exporter.exportV2StructuredRows(rowSource)).thenReturn(artifact);

        new V2StructuredAllGenerationCoordinator(repository, traversal, skills, sessions, store, exporter,
                new ObjectMapper(), heartbeat).execute("task-v2", request);

        ArgumentCaptor<RequirementFactExtractionV2Invocation> factCall =
                ArgumentCaptor.forClass(RequirementFactExtractionV2Invocation.class);
        verify(skills).extractRequirementFactsV2(factCall.capture());
        assertThat(factCall.getValue().requirementScope().documents()).hasSize(1);
        assertThat(factCall.getValue().input().units()).singleElement()
                .extracting(unit -> unit.content()).isEqualTo("正式需求原文");
        verify(skills).designFunctionalTestcasesV2(any());
        verify(skills, never()).reviewRequirementMaterial(any());
        verify(skills, never()).extractFunctionList(any());
        verify(skills, never()).extractFunctionCandidates(any());
        verify(skills, never()).reconcileFeatureScope(any());
        verify(skills, never()).reconcileFeatureScopePage(any());
        verify(repository).completeV2StructuredTask("task-v2", artifact,
                StructuredProcessingStatus.COMPLETED, StructuredCoverageStatus.UNABLE_TO_GENERATE);
        verify(store, never()).updateTaskState(eq("task-v2"), argThat(state ->
                state.processingStatus() == StructuredProcessingStatus.COMPLETED));
    }

    @Test
    void exhaustsOnlyTheFailedFactWindowAndStillPublishesAnAuditableUnableOutcome() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest request = request();
        stubApprovedScopeVersion(repository, request);
        var function = request.approvedFunctionScope().functions().get(0);
        when(repository.approvedFunctions("task-v2")).thenReturn(List.of(function));
        when(repository.hasCompleteMaterialInventory("task-v2", request.requirementScope())).thenReturn(false);
        when(traversal.traverse("task-v2", request, false))
                .thenReturn(new RequirementMaterialTraversalService.TraversalResult(List.of(material())));
        stubBoundedInventory(repository, material());
        when(sessions.openStructuredSession()).thenReturn("session-v2");
        when(store.register(any())).thenAnswer(invocation ->
                ((StructuredGenerationAcceptanceStore.WorkRegistration) invocation.getArgument(0)).operationName()
                        + "-work");
        when(store.registerMissingFactFallback(any())).thenAnswer(invocation ->
                ((V2GenerationPlanner.TestPointPlan) invocation.getArgument(0)).registration().operationName()
                        + "-work");
        AtomicInteger factAttempt = new AtomicInteger();
        when(store.claimRegistered(any(), any(), any())).thenAnswer(invocation -> {
            String workId = invocation.getArgument(1);
            int attempt = workId.startsWith("REQUIREMENT_FACT") ? factAttempt.incrementAndGet() : 1;
            return java.util.Optional.of(claim(workId, invocation.getArgument(0), attempt));
        });
        when(skills.extractRequirementFactsV2(any())).thenThrow(
                new StructuredSkillExecutionException(StructuredSkillErrorType.MODEL_EXECUTION_FAILED, false));
        when(store.hasRemainingModelAttemptBudget(any())).thenReturn(true, false);
        when(store.acceptedRequirementFactsV2("task-v2", "function-a")).thenReturn(List.of());
        when(skills.designFunctionalTestcasesV2(any())).thenAnswer(invocation -> {
            var call = (com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignV2Invocation) invocation.getArgument(0);
            return success("functional-testcase-design", new FunctionalTestcaseDesignV2Result(
                    call.input().functionKey(), call.input().testPoint().testPointKey(),
                    FunctionalTestcaseDesignV2Result.GenerationOutcome.UNABLE_TO_GENERATE,
                    List.of("正式需求事实提取窗口未完成"), List.of()));
        });
        when(store.v2AggregateState("task-v2")).thenReturn(
                new StructuredGenerationAcceptanceStore.V2AggregateState(2, 1, 1, 0, 1, 0, 0, 0, 1));
        StructuredWorkbookExportRequest rows = new StructuredWorkbookExportRequest("task-v2", List.of(), List.of());
        StructuredWorkbookRowSource rowSource = StructuredWorkbookRowSource.from(rows);
        when(repository.structuredWorkbookRows("task-v2")).thenReturn(rowSource);
        WorkbookArtifact artifact = new WorkbookArtifact("artifact-v2", "a".repeat(64), Path.of("artifact.xlsx"));
        when(exporter.exportV2StructuredRows(rowSource)).thenReturn(artifact);

        new V2StructuredAllGenerationCoordinator(repository, traversal, skills, sessions, store, exporter,
                new ObjectMapper(), noOpHeartbeat()).execute("task-v2", request);

        verify(skills, org.mockito.Mockito.times(StructuredGenerationAcceptanceStore.MAX_ATTEMPTS))
                .extractRequirementFactsV2(any());
        verify(skills).designFunctionalTestcasesV2(any());
        verify(repository).completeV2StructuredTask("task-v2", artifact,
                StructuredProcessingStatus.FAILED, StructuredCoverageStatus.UNABLE_TO_GENERATE);
    }

    /** [Req-ID]: REQ-TGV2-006, REQ-TGV2-008 */
    @Test
    void interruptionBeforeTheFirstModelFailureDoesNotConsumeTheRemainingModelAttempt() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest request = request();
        stubApprovedScopeVersion(repository, request);
        var function = request.approvedFunctionScope().functions().get(0);
        when(repository.approvedFunctions("task-v2")).thenReturn(List.of(function));
        when(repository.hasCompleteMaterialInventory("task-v2", request.requirementScope())).thenReturn(false);
        when(traversal.traverse("task-v2", request, false))
                .thenReturn(new RequirementMaterialTraversalService.TraversalResult(List.of(material())));
        stubBoundedInventory(repository, material());
        when(sessions.openStructuredSession()).thenReturn("session-v2");
        when(store.register(any())).thenReturn("fact-work");
        when(store.registerMissingFactFallback(any())).thenReturn("fallback-work");
        when(store.claimRegistered("task-v2", "fact-work", "structured-v2-worker"))
                .thenReturn(java.util.Optional.of(claim("fact-work", "task-v2", 2)),
                        java.util.Optional.of(claim("fact-work", "task-v2", 3)));
        when(store.hasRemainingModelAttemptBudget("fact-work")).thenReturn(true, false);
        when(skills.extractRequirementFactsV2(any())).thenThrow(
                new StructuredSkillExecutionException(StructuredSkillErrorType.MODEL_EXECUTION_FAILED, false));
        when(store.acceptedRequirementFactsV2("task-v2", "function-a")).thenReturn(List.of());
        when(skills.designFunctionalTestcasesV2(any())).thenAnswer(invocation -> {
            var call = (com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignV2Invocation) invocation.getArgument(0);
            return success("functional-testcase-design", new FunctionalTestcaseDesignV2Result(
                    call.input().functionKey(), call.input().testPoint().testPointKey(),
                    FunctionalTestcaseDesignV2Result.GenerationOutcome.UNABLE_TO_GENERATE,
                    List.of("正式需求事实提取窗口未完成"), List.of()));
        });
        when(store.v2AggregateState("task-v2")).thenReturn(
                new StructuredGenerationAcceptanceStore.V2AggregateState(2, 1, 1, 0, 1, 0, 0, 0, 1));
        StructuredWorkbookExportRequest rows = new StructuredWorkbookExportRequest("task-v2", List.of(), List.of());
        StructuredWorkbookRowSource rowSource = StructuredWorkbookRowSource.from(rows);
        when(repository.structuredWorkbookRows("task-v2")).thenReturn(rowSource);
        WorkbookArtifact artifact = new WorkbookArtifact("artifact-v2", "a".repeat(64), Path.of("artifact.xlsx"));
        when(exporter.exportV2StructuredRows(rowSource)).thenReturn(artifact);

        new V2StructuredAllGenerationCoordinator(repository, traversal, skills, sessions, store, exporter,
                new ObjectMapper(), noOpHeartbeat()).execute("task-v2", request);

        verify(skills, org.mockito.Mockito.times(2)).extractRequirementFactsV2(any());
        verify(store, org.mockito.Mockito.times(2)).fail(
                org.mockito.ArgumentMatchers.argThat(value -> "fact-work".equals(value.workItemId())),
                eq("model_execution_failed"));
    }

    @Test
    void interruptedLiveSkillWaitStopsWithoutConsumingTheModelRetryBudget() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest request = request();
        stubApprovedScopeVersion(repository, request);
        var function = request.approvedFunctionScope().functions().get(0);
        when(repository.approvedFunctions("task-v2")).thenReturn(List.of(function));
        when(repository.hasCompleteMaterialInventory("task-v2", request.requirementScope())).thenReturn(false);
        when(traversal.traverse("task-v2", request, false))
                .thenReturn(new RequirementMaterialTraversalService.TraversalResult(List.of(material())));
        stubBoundedInventory(repository, material());
        when(sessions.openStructuredSession()).thenReturn("session-v2");
        when(store.register(any())).thenReturn("fact-work");
        var claim = claim("fact-work", "task-v2", StructuredGenerationAcceptanceStore.MAX_ATTEMPTS);
        when(store.claimRegistered("task-v2", "fact-work", "structured-v2-worker"))
                .thenReturn(java.util.Optional.of(claim));
        when(skills.extractRequirementFactsV2(any())).thenAnswer(ignored -> {
            Thread.currentThread().interrupt();
            throw new StructuredSkillExecutionException(StructuredSkillErrorType.MODEL_EXECUTION_FAILED, false);
        });

        try {
            assertThatThrownBy(() -> new V2StructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                    store, exporter, new ObjectMapper(), noOpHeartbeat()).execute("task-v2", request))
                    .isInstanceOf(StructuredSkillExecutionException.class);
        } finally {
            Thread.interrupted();
        }

        verify(skills).extractRequirementFactsV2(any());
        verify(store).fail(claim, "worker_interrupted");
    }

    /** [Req-ID]: REQ-TGV2-006, REQ-TGV2-008 */
    @Test
    void restartSkipsCompletedFactAndTestcaseWorkWithoutCallingKeeAgain() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest request = request();
        stubApprovedScopeVersion(repository, request);
        var function = request.approvedFunctionScope().functions().get(0);
        when(repository.approvedFunctions("task-v2")).thenReturn(List.of(function));
        when(repository.hasCompleteMaterialInventory("task-v2", request.requirementScope())).thenReturn(true);
        stubBoundedInventory(repository, material());
        when(sessions.openStructuredSession()).thenReturn("session-v2");
        when(store.register(any())).thenAnswer(invocation ->
                ((StructuredGenerationAcceptanceStore.WorkRegistration) invocation.getArgument(0)).operationName()
                        + "-work");
        when(store.registerMissingFactFallback(any())).thenAnswer(invocation ->
                ((V2GenerationPlanner.TestPointPlan) invocation.getArgument(0)).registration().operationName()
                        + "-work");
        when(store.isCompleted(any())).thenReturn(true);
        when(store.acceptedRequirementFactsV2("task-v2", "function-a")).thenReturn(List.of());
        when(store.v2AggregateState("task-v2")).thenReturn(
                new StructuredGenerationAcceptanceStore.V2AggregateState(2, 2, 0, 0, 1, 0, 0, 0, 1));
        StructuredWorkbookExportRequest rows = new StructuredWorkbookExportRequest("task-v2", List.of(), List.of());
        StructuredWorkbookRowSource rowSource = StructuredWorkbookRowSource.from(rows);
        when(repository.structuredWorkbookRows("task-v2")).thenReturn(rowSource);
        WorkbookArtifact artifact = new WorkbookArtifact("artifact-v2", "a".repeat(64), Path.of("artifact.xlsx"));
        when(exporter.exportV2StructuredRows(rowSource)).thenReturn(artifact);

        new V2StructuredAllGenerationCoordinator(repository, traversal, skills, sessions, store, exporter,
                new ObjectMapper(), noOpHeartbeat()).execute("task-v2", request);

        verify(skills, never()).extractRequirementFactsV2(any());
        verify(skills, never()).designFunctionalTestcasesV2(any());
        verify(store).registerMissingFactFallback(any());
        verify(store, never()).claimRegistered(any(), any(), any());
        verify(repository).completeV2StructuredTask("task-v2", artifact,
                StructuredProcessingStatus.COMPLETED, StructuredCoverageStatus.UNABLE_TO_GENERATE);
    }

    /** [Req-ID]: REQ-TGV2-014, REQ-TGV2-015 */
    @Test
    void designRecoverySkipsCompletedFactsAndCallsOnlyTheRequeuedDesignWork() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest request = request();
        stubApprovedScopeVersion(repository, request);
        var function = request.approvedFunctionScope().functions().get(0);
        var fact = persistedFact("fact-retry");
        when(repository.approvedFunctions("task-v2")).thenReturn(List.of(function));
        when(repository.hasCompleteMaterialInventory("task-v2", request.requirementScope())).thenReturn(true);
        stubBoundedInventory(repository, material());
        when(sessions.openStructuredSession()).thenReturn("session-v2");
        when(store.register(any())).thenAnswer(invocation -> {
            StructuredGenerationAcceptanceStore.WorkRegistration registration = invocation.getArgument(0);
            return "requirement-fact-extraction".equals(registration.skillName())
                    ? "fact-completed" : "design-retry";
        });
        when(store.isCompleted("fact-completed")).thenReturn(true);
        when(store.isCompleted("design-retry")).thenReturn(false);
        when(store.acceptedRequirementFactsV2Page("task-v2", "function-a", "", 100))
                .thenReturn(List.of(fact));
        when(store.acceptedRequirementFactsV2Page("task-v2", "function-a", "fact-retry", 100))
                .thenReturn(List.of());
        when(store.claimRegistered("task-v2", "design-retry", "structured-v2-worker"))
                .thenReturn(java.util.Optional.of(claim("design-retry", "task-v2", 2)));
        when(skills.designFunctionalTestcasesV2(any())).thenAnswer(invocation -> {
            var call = (com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignV2Invocation)
                    invocation.getArgument(0);
            return success("functional-testcase-design", new FunctionalTestcaseDesignV2Result(
                    call.input().functionKey(), call.input().testPoint().testPointKey(),
                    FunctionalTestcaseDesignV2Result.GenerationOutcome.UNABLE_TO_GENERATE,
                    List.of("缺少可确认的测试环境"), List.of()));
        });
        when(store.v2AggregateState("task-v2")).thenReturn(
                new StructuredGenerationAcceptanceStore.V2AggregateState(2, 2, 0, 0, 1, 0, 0, 0, 1));
        StructuredWorkbookRowSource rowSource = StructuredWorkbookRowSource.from(
                new StructuredWorkbookExportRequest("task-v2", List.of(), List.of()));
        when(repository.structuredWorkbookRows("task-v2")).thenReturn(rowSource);
        WorkbookArtifact artifact = new WorkbookArtifact("artifact-v2", "a".repeat(64), Path.of("artifact.xlsx"));
        when(exporter.exportV2StructuredRows(rowSource)).thenReturn(artifact);

        new V2StructuredAllGenerationCoordinator(repository, traversal, skills, sessions, store, exporter,
                new ObjectMapper(), noOpHeartbeat()).execute("task-v2", request);

        verify(skills, never()).extractRequirementFactsV2(any());
        verify(skills).designFunctionalTestcasesV2(any());
        verify(store, never()).claimRegistered("task-v2", "fact-completed", "structured-v2-worker");
        verify(store).claimRegistered("task-v2", "design-retry", "structured-v2-worker");
        verify(store).acceptTestcasesV2(eq(claim("design-retry", "task-v2", 2)), any(), any(), any());
    }

    @Test
    void restartPlansPersistedFactsInBoundedPagesAndNeverLoadsEveryMaterialTogether() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest request = request();
        stubApprovedScopeVersion(repository, request);
        var function = request.approvedFunctionScope().functions().get(0);
        MaterialInventoryDocument material = material();
        when(repository.approvedFunctions("task-v2")).thenReturn(List.of(function));
        when(repository.hasCompleteMaterialInventory("task-v2", request.requirementScope())).thenReturn(true);
        stubBoundedInventory(repository, material);
        when(sessions.openStructuredSession()).thenReturn("session-v2");
        when(store.register(any())).thenAnswer(invocation ->
                ((StructuredGenerationAcceptanceStore.WorkRegistration) invocation.getArgument(0)).identityKey());
        when(store.isCompleted(any())).thenReturn(true);
        List<V2GenerationPlanner.PersistedFact> firstPage = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            firstPage.add(persistedFact(String.format("fact-%03d", index)));
        }
        when(store.acceptedRequirementFactsV2Page("task-v2", "function-a", "", 100))
                .thenReturn(List.copyOf(firstPage));
        when(store.acceptedRequirementFactsV2Page("task-v2", "function-a", "fact-099", 100))
                .thenReturn(List.of(persistedFact("fact-100")));
        when(store.v2AggregateState("task-v2")).thenReturn(
                new StructuredGenerationAcceptanceStore.V2AggregateState(102, 102, 0, 0, 101, 101, 101, 0, 0));
        StructuredWorkbookExportRequest rows = new StructuredWorkbookExportRequest("task-v2", List.of(), List.of());
        StructuredWorkbookRowSource rowSource = StructuredWorkbookRowSource.from(rows);
        when(repository.structuredWorkbookRows("task-v2")).thenReturn(rowSource);
        WorkbookArtifact artifact = new WorkbookArtifact("artifact-v2", "a".repeat(64), Path.of("artifact.xlsx"));
        when(exporter.exportV2StructuredRows(rowSource)).thenReturn(artifact);

        new V2StructuredAllGenerationCoordinator(repository, traversal, skills, sessions, store, exporter,
                new ObjectMapper(), noOpHeartbeat()).execute("task-v2", request);

        verify(repository, never()).materialInventoryDocuments("task-v2");
        verify(repository, never()).materialInventoryDocument(any(), any());
        verify(store).acceptedRequirementFactsV2Page("task-v2", "function-a", "", 100);
        verify(store).acceptedRequirementFactsV2Page("task-v2", "function-a", "fact-099", 100);
        verify(store, never()).acceptedRequirementFactsV2("task-v2", "function-a");
        verify(skills, never()).extractRequirementFactsV2(any());
        verify(skills, never()).designFunctionalTestcasesV2(any());
    }

    private static StructuredWorkLeaseHeartbeat noOpHeartbeat() {
        return (claim, cancellation) -> new StructuredWorkLeaseHeartbeat.ActiveLease() {
            @Override public void requireActive() { }
            @Override public void close() { }
        };
    }

    private static StructuredGenerationAcceptanceStore.WorkClaim claim(String workId, String taskId) {
        return claim(workId, taskId, 1);
    }

    private static StructuredGenerationAcceptanceStore.WorkClaim claim(String workId, String taskId, int attempt) {
        return new StructuredGenerationAcceptanceStore.WorkClaim(workId, workId + "-attempt", taskId,
                "a".repeat(64), "skill", "operation", attempt, null, null, null, List.of(), "worker");
    }

    private static <T> StructuredSkillSuccessEnvelope<T> success(String skillName, T result) {
        return new StructuredSkillSuccessEnvelope<>(true,
                new StructuredSkillSuccess<>("2.0", skillName, false, result));
    }

    private static MaterialInventoryDocument material() {
        MaterialInventoryUnit unit = new MaterialInventoryUnit("requirements", "REQUIREMENT", "unit-a",
                0, 1, "正式需求原文", 0, 6);
        return new MaterialInventoryDocument("requirements", "requirements", "REQUIREMENT", 1, true,
                List.of(unit));
    }

    private static void stubBoundedInventory(
            GenerationTaskRepository repository, MaterialInventoryDocument material) {
        V2GenerationPlanner.MaterialDescriptor descriptor = new V2GenerationPlanner.MaterialDescriptor(
                material.documentId(), material.documentRole(), material.totalUnits(),
                material.units().get(0).ordinal(), material.units().get(material.units().size() - 1).ordinal());
        when(repository.formalRequirementMaterials("task-v2")).thenReturn(List.of(descriptor));
        when(repository.materialInventoryPlanningSlice(eq("task-v2"), eq(material.documentId()),
                anyInt(), eq(descriptor.firstOrdinal()), eq(descriptor.lastOrdinal()))).thenAnswer(invocation -> {
                    int cursor = invocation.getArgument(2);
                    int first = Math.max(descriptor.firstOrdinal(), cursor - 4);
                    int last = Math.min(descriptor.lastOrdinal(), cursor + 19);
                    return material.units().subList(first - descriptor.firstOrdinal(),
                            last - descriptor.firstOrdinal() + 1);
                });
    }

    private static V2GenerationPlanner.PersistedFact persistedFact(String factKey) {
        return new V2GenerationPlanner.PersistedFact(factKey,
                RequirementFactExtractionV2Result.FactType.BUSINESS_RULE, "订单可以提交",
                List.of(new com.testcaseagent.knowledgeagent.StructuredSourceQuoteV2("unit-a", "订单可以提交")));
    }

    private static void stubApprovedScopeVersion(
            GenerationTaskRepository repository, CreateGenerationTaskRequest request) {
        when(repository.hasExactApprovedScopeSnapshot("task-v2")).thenReturn(true);
        when(repository.hasDistinctApprovedAndDerivedTestPointKeys("task-v2")).thenReturn(true);
        when(repository.approvedScopeVersion("task-v2"))
                .thenReturn(request.approvedFunctionScope().scopeVersion());
    }

    private static CreateGenerationTaskRequest request() {
        return GenerationWorkflowV2RoutingTest.request();
    }

    private static CreateGenerationTaskRequest requestWithPoints(
            List<ApprovedFunctionScope.ApprovedTestPoint> points) {
        CreateGenerationTaskRequest base = request();
        ApprovedFunctionScope scope = new ApprovedFunctionScope(base.approvedFunctionScope().scopeVersion(),
                base.approvedFunctionScope().functions(), points);
        return requestWithScope(scope);
    }

    private static CreateGenerationTaskRequest requestWithScope(ApprovedFunctionScope scope) {
        CreateGenerationTaskRequest base = request();
        List<String> functionKeys = scope.functions().stream()
                .map(ApprovedFunctionScope.ApprovedFunction::functionKey).toList();
        java.util.LinkedHashMap<String, String> functionPaths = new java.util.LinkedHashMap<>();
        scope.functions().forEach(function -> functionPaths.put(function.functionKey(), function.path()));
        return new CreateGenerationTaskRequest(base.taskMode(), functionKeys.get(0), functionKeys,
                functionPaths, base.fewShotPolicy(), base.schemaVersion(), base.promptVersion(), base.agentId(),
                base.requirementScope(), base.exampleScope(), base.requirementAdmissionTypeKeys(), base.prompt(),
                base.contractVersions(), scope);
    }
}
