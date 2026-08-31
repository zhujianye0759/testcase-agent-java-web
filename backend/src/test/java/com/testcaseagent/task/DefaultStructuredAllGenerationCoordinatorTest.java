package com.testcaseagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testcaseagent.export.StructuredWorkbookExportRequest;
import com.testcaseagent.export.WorkbookArtifact;
import com.testcaseagent.export.WorkbookExporter;
import com.testcaseagent.featureaudit.MaterialInventoryDocument;
import com.testcaseagent.featureaudit.MaterialInventoryUnit;
import com.testcaseagent.featureaudit.RequirementMaterialTraversalService;
import com.testcaseagent.knowledgeagent.FeatureScopeReconciliationInput;
import com.testcaseagent.knowledgeagent.FeatureScopeReconciliationPageInput;
import com.testcaseagent.knowledgeagent.FeatureScopeReconciliationPageInvocation;
import com.testcaseagent.knowledgeagent.FeatureScopeReconciliationPageResult;
import com.testcaseagent.knowledgeagent.FeatureScopeReconciliationResult;
import com.testcaseagent.knowledgeagent.FeatureScopeReconciliationV2Canonicalizer;
import com.testcaseagent.knowledgeagent.FunctionListExtractionInput;
import com.testcaseagent.knowledgeagent.FunctionListExtractionInvocation;
import com.testcaseagent.knowledgeagent.FunctionListExtractionResult;
import com.testcaseagent.knowledgeagent.FunctionCandidateExtractionInput;
import com.testcaseagent.knowledgeagent.FunctionCandidateExtractionInvocation;
import com.testcaseagent.knowledgeagent.FunctionCandidateExtractionResult;
import com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignResult;
import com.testcaseagent.knowledgeagent.RequirementMaterialQualityReviewResult;
import com.testcaseagent.knowledgeagent.StructuredSkillExecutionPort;
import com.testcaseagent.knowledgeagent.StructuredSkillExecutionException;
import com.testcaseagent.knowledgeagent.StructuredSkillErrorType;
import com.testcaseagent.knowledgeagent.StructuredSkillSessionPort;
import com.testcaseagent.knowledgeagent.StructuredSkillSuccess;
import com.testcaseagent.knowledgeagent.StructuredSkillSuccessEnvelope;
import com.testcaseagent.scope.RequirementDocumentCoordinate;
import com.testcaseagent.scope.RequirementScope;
import com.testcaseagent.structuredgeneration.StructuredGenerationAcceptanceStore;
import com.testcaseagent.validation.StructuredKeyType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/** Orchestration tests for the production structured ALL route. [Req-ID]: REQ-STG-001~007, REQ-FTG-005 */
class DefaultStructuredAllGenerationCoordinatorTest {

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError("The JDK must provide SHA-256", exception);
        }
    }

    /** [Req-ID]: REQ-ESR-006 */
    @Test
    void recordsAllowlistedPreIsolatedStageAndFixedExceptionCategoryWithoutLeakingTheException() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest request = mock(CreateGenerationTaskRequest.class);
        MaterialInventoryDocument functionList = material(
                "function-list-1", "FUNCTION_LIST", "function-unit-1", "功能清单");
        when(request.requirementScope()).thenReturn(scope("function-list-1"));
        when(repository.hasCompleteMaterialInventory("task-safe-stage", request.requirementScope())).thenReturn(true);
        when(repository.materialInventoryDocuments("task-safe-stage")).thenReturn(List.of(functionList));
        when(sessions.openStructuredSession()).thenReturn("session-safe-stage");
        when(store.materialWindowPlans("task-safe-stage", "FEATURE_SCOPE_EXTRACT", "function-list-1"))
                .thenThrow(new IllegalStateException(
                        "Authorization=Bearer must-not-leak; material content must-not-leak"));

        assertThatThrownBy(() -> new DefaultStructuredAllGenerationCoordinator(
                repository, traversal, skills, sessions, store, exporter, new ObjectMapper(), heartbeat())
                .execute("task-safe-stage", request))
                .isInstanceOfSatisfying(com.testcaseagent.validation.StructuredValidationException.class,
                        safe -> assertThat(safe.failure().path()).isEqualTo("$.function_extraction_pre_split"))
                .hasMessageNotContaining("Authorization")
                .hasMessageNotContaining("must-not-leak")
                .hasMessageNotContaining("material content");

        ArgumentCaptor<com.testcaseagent.validation.StructuredValidationFailure> failure =
                ArgumentCaptor.forClass(com.testcaseagent.validation.StructuredValidationFailure.class);
        verify(repository).failStructuredTask(eq("task-safe-stage"),
                eq(com.testcaseagent.structuredgeneration.StructuredCoverageStatus.PENDING), failure.capture());
        assertThat(failure.getValue().code()).isEqualTo("STRUCTURED_COORDINATOR_STATE_FAILURE");
        assertThat(failure.getValue().path()).isEqualTo("$.function_extraction_pre_split");
        assertThat(failure.getValue().message())
                .isEqualTo("结构化任务在状态处理阶段失败")
                .doesNotContain("Authorization", "must-not-leak", "material content");
        verify(sessions).openStructuredSession();
        verify(skills, never()).extractFunctionList(any());
        verify(skills, never()).reviewRequirementMaterial(any());
    }

    /** [Req-ID]: REQ-ESR-006 */
    @Test
    void taskStartFailureStillUsesTheRepositorySafeDiagnosticBoundary() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest request = mock(CreateGenerationTaskRequest.class);
        doThrow(new IllegalStateException("password=must-not-leak"))
                .when(store).updateTaskState(eq("task-start-failure"), any());

        assertThatThrownBy(() -> new DefaultStructuredAllGenerationCoordinator(
                repository, traversal, skills, sessions, store, exporter, new ObjectMapper(), heartbeat())
                .execute("task-start-failure", request))
                .isInstanceOfSatisfying(com.testcaseagent.validation.StructuredValidationException.class,
                        safe -> assertThat(safe.failure().path()).isEqualTo("$.task_start_state_resume"))
                .hasMessageNotContaining("password")
                .hasMessageNotContaining("must-not-leak");

        ArgumentCaptor<com.testcaseagent.validation.StructuredValidationFailure> failure =
                ArgumentCaptor.forClass(com.testcaseagent.validation.StructuredValidationFailure.class);
        verify(repository).failStructuredTask(eq("task-start-failure"),
                eq(com.testcaseagent.structuredgeneration.StructuredCoverageStatus.PENDING), failure.capture());
        assertThat(failure.getValue().path()).isEqualTo("$.task_start_state_resume");
        assertThat(failure.getValue().code()).isEqualTo("STRUCTURED_COORDINATOR_STATE_FAILURE");
        assertThat(failure.getValue().toString()).doesNotContain("password", "must-not-leak");
        verifyNoStructuredCalls(skills, exporter);
    }

    /** [Req-ID]: REQ-FTG-010 */
    @Test
    void responseTooLargeReviewWindowSplitsInPlaceAndPreservesEveryPersistedUnit() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest request = mock(CreateGenerationTaskRequest.class);
        MaterialInventoryDocument material = prototypeMaterialWithUnits(33, 64);
        when(request.agentId()).thenReturn("agent-1");
        when(request.requirementScope()).thenReturn(scope("prototype-1"));
        when(sessions.openStructuredSession()).thenReturn("session-review-split");
        when(traversal.traverse("task-review-split", request, false)).thenReturn(
                new RequirementMaterialTraversalService.TraversalResult(List.of(material)));

        Map<String, StructuredGenerationAcceptanceStore.WorkRegistration> registrations = new LinkedHashMap<>();
        when(store.register(any())).thenAnswer(invocation -> {
            StructuredGenerationAcceptanceStore.WorkRegistration registration = invocation.getArgument(0);
            String workId = "work-%d-%d".formatted(registration.ordinalStart(), registration.ordinalEnd());
            registrations.put(workId, registration);
            return workId;
        });
        AtomicBoolean parentSplit = new AtomicBoolean();
        when(store.isSplit("work-33-44")).thenAnswer(ignored -> parentSplit.get());
        when(store.claimRegistered(eq("task-review-split"), anyString(), eq("structured-all-worker")))
                .thenAnswer(invocation -> {
                    String workId = invocation.getArgument(1);
                    StructuredGenerationAcceptanceStore.WorkRegistration registration = registrations.get(workId);
                    return java.util.Optional.of(new StructuredGenerationAcceptanceStore.WorkClaim(
                            workId, "attempt-" + workId, registration.taskId(), registration.identityKey(),
                            registration.skillName(), registration.operationName(), 1,
                            registration.ordinalStart(), registration.ordinalEnd(), registration.materialKey(),
                            registration.allowedEvidenceKeys(), "structured-all-worker"));
                });
        doAnswer(invocation -> {
            StructuredGenerationAcceptanceStore.WorkRegistration left = invocation.getArgument(1);
            StructuredGenerationAcceptanceStore.WorkRegistration right = invocation.getArgument(2);
            assertThat(left.ordinalStart()).isEqualTo(33);
            assertThat(left.ordinalEnd()).isEqualTo(38);
            assertThat(right.ordinalStart()).isEqualTo(39);
            assertThat(right.ordinalEnd()).isEqualTo(44);
            assertThat(left.allowedEvidenceKeys()).containsExactlyElementsOf(
                    material.units().subList(0, 6).stream().map(MaterialInventoryUnit::unitId).toList());
            assertThat(right.allowedEvidenceKeys()).containsExactlyElementsOf(
                    material.units().subList(6, 12).stream().map(MaterialInventoryUnit::unitId).toList());
            assertThat(left.contextEvidenceKeys()).containsExactlyElementsOf(
                    material.units().subList(6, 10).stream().map(MaterialInventoryUnit::unitId).toList());
            assertThat(right.contextEvidenceKeys()).containsExactlyElementsOf(java.util.stream.Stream.concat(
                            material.units().subList(2, 6).stream(), material.units().subList(12, 16).stream())
                            .map(MaterialInventoryUnit::unitId).toList());
            parentSplit.set(true);
            return null;
        }).when(store).splitReviewWork(any(), any(), any());
        when(skills.reviewRequirementMaterial(any())).thenAnswer(invocation -> {
            var input = invocation.getArgument(
                    0, com.testcaseagent.knowledgeagent.RequirementMaterialQualityReviewInvocation.class).input();
            if (input.units().get(0).ordinal() == 33 && input.units().size() == 12) {
                throw new StructuredSkillExecutionException(StructuredSkillErrorType.RESPONSE_TOO_LARGE, false);
            }
            var first = input.units().get(0);
            return success("requirement-material-quality-review", new RequirementMaterialQualityReviewResult(
                    List.of(), List.of(new RequirementMaterialQualityReviewResult.ReviewFinding(
                    "finding-" + first.ordinal(), RequirementMaterialQualityReviewResult.RootCauseKind.MISSING_FUNCTION_SCOPE,
                    "功能范围需要补充", new RequirementMaterialQualityReviewResult.AffectedScope(
                    List.of(first.unitKey()), "当前原型范围"),
                    new RequirementMaterialQualityReviewResult.BadSourceExample(first.unitKey(), first.content()),
                    new RequirementMaterialQualityReviewResult.ProposedGoodExample(
                            RequirementMaterialQualityReviewResult.ProposalStatus.PENDING_CONFIRMATION,
                            "待需求方确认：补充功能范围"),
                    "当前材料未明确功能范围", List.of(first.unitKey()), "影响功能测试设计", "当前项目待确认",
                    "设计中心补充功能范围模板", RequirementMaterialQualityReviewResult.HandlingLevel.IMPROVEMENT))));
        });
        when(store.acceptedInputs("task-review-split"))
                .thenReturn(new StructuredGenerationAcceptanceStore.AcceptedInputs(List.of(), List.of()));
        StructuredWorkbookExportRequest rows = new StructuredWorkbookExportRequest("task-review-split", List.of(), List.of());
        WorkbookArtifact artifact = new WorkbookArtifact("artifact-review-split", "a".repeat(64), Path.of("review-split.xlsx"));
        when(repository.structuredWorkbookRequest("task-review-split")).thenReturn(rows);
        when(exporter.exportStructured(rows)).thenReturn(artifact);
        when(store.aggregateState("task-review-split"))
                .thenReturn(new StructuredGenerationAcceptanceStore.AggregateState(2, 2, 0, 0, 0, 2, 0, true));

        new DefaultStructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                store, exporter, new ObjectMapper(), heartbeat()).execute("task-review-split", request);

        var invocation = org.mockito.ArgumentCaptor.forClass(
                com.testcaseagent.knowledgeagent.RequirementMaterialQualityReviewInvocation.class);
        verify(skills, org.mockito.Mockito.times(5)).reviewRequirementMaterial(invocation.capture());
        List<com.testcaseagent.knowledgeagent.RequirementMaterialQualityReviewInput.MaterialUnit> frozenUnits =
                material.units().stream().map(unit ->
                        new com.testcaseagent.knowledgeagent.RequirementMaterialQualityReviewInput.MaterialUnit(
                                unit.unitId(), unit.ordinal(), unit.content())).toList();
        assertThat(invocation.getAllValues()).extracting(call -> call.input().units())
                .containsExactly(frozenUnits.subList(0, 12), frozenUnits.subList(0, 6), frozenUnits.subList(6, 12),
                        frozenUnits.subList(12, 24), frozenUnits.subList(24, 32));
        verify(store).splitReviewWork(any(), any(), any());
        verify(store, never()).fail(org.mockito.ArgumentMatchers.argThat(claim ->
                "work-33-44".equals(claim.workItemId())), anyString());
    }

    /** [Req-ID]: REQ-FTG-010 */
    @Test
    void oneUnitReviewWindowThatIsStillTooLargeFailsWithoutCreatingChildren() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest request = mock(CreateGenerationTaskRequest.class);
        when(request.agentId()).thenReturn("agent-1");
        when(request.requirementScope()).thenReturn(scope("prototype-1"));
        when(sessions.openStructuredSession()).thenReturn("session-review-minimum");
        when(traversal.traverse("task-review-minimum", request, false)).thenReturn(
                new RequirementMaterialTraversalService.TraversalResult(List.of(prototypeMaterialWithUnits(1))));
        AtomicReference<StructuredGenerationAcceptanceStore.WorkRegistration> registration = new AtomicReference<>();
        when(store.register(any())).thenAnswer(invocation -> {
            registration.set(invocation.getArgument(0));
            return "work-review-minimum";
        });
        var claim = new StructuredGenerationAcceptanceStore.WorkClaim(
                "work-review-minimum", "attempt-review-minimum", "task-review-minimum", "a".repeat(64),
                "requirement-material-quality-review", "REQUIREMENT_MATERIAL_REVIEW", 1,
                1, 1, "prototype-1", List.of("prototype-unit-1"), "structured-all-worker");
        when(store.claimRegistered("task-review-minimum", "work-review-minimum", "structured-all-worker"))
                .thenReturn(java.util.Optional.of(claim));
        when(skills.reviewRequirementMaterial(any())).thenThrow(
                new StructuredSkillExecutionException(StructuredSkillErrorType.RESPONSE_TOO_LARGE, true));

        assertThatThrownBy(() -> new DefaultStructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                store, exporter, new ObjectMapper(), heartbeat()).execute("task-review-minimum", request))
                .isInstanceOfSatisfying(StructuredSkillExecutionException.class,
                        failure -> assertThat(failure.type()).isEqualTo(StructuredSkillErrorType.RESPONSE_TOO_LARGE));

        verify(store, never()).splitReviewWork(any(), any(), any());
        verify(store).fail(claim, "response_too_large");
    }

    /** [Req-ID]: REQ-FTG-012, REQ-FTG-015 */
    @ParameterizedTest
    @EnumSource(value = StructuredSkillErrorType.class,
            names = {"REQUEST_TOO_LARGE", "RESPONSE_TOO_LARGE", "STRUCTURED_OUTPUT_INVALID"})
    void candidateCapacityOrMultiTargetFormatFailureSplitsInPlaceAndPreservesEveryPersistedUnit(
            StructuredSkillErrorType splitFailure) {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest request = mock(CreateGenerationTaskRequest.class);
        MaterialInventoryDocument material = functionMaterialWithUnits(32);
        when(request.agentId()).thenReturn("agent-1");
        when(request.requirementScope()).thenReturn(scope("requirement-1", "function-list-1"));
        when(sessions.openStructuredSession()).thenReturn("session-extraction-split");
        when(traversal.traverse("task-extraction-split", request, false)).thenReturn(
                new RequirementMaterialTraversalService.TraversalResult(List.of(material)));

        Map<String, StructuredGenerationAcceptanceStore.WorkRegistration> registrations = new LinkedHashMap<>();
        when(store.register(any())).thenAnswer(invocation -> {
            StructuredGenerationAcceptanceStore.WorkRegistration registration = invocation.getArgument(0);
            String workId = "work-%d-%d".formatted(registration.ordinalStart(), registration.ordinalEnd());
            registrations.put(workId, registration);
            return workId;
        });
        AtomicBoolean parentSplit = new AtomicBoolean();
        when(store.isSplit("work-1-12")).thenAnswer(ignored -> parentSplit.get());
        when(store.claimRegistered(eq("task-extraction-split"), anyString(), eq("structured-all-worker")))
                .thenAnswer(invocation -> {
                    String workId = invocation.getArgument(1);
                    StructuredGenerationAcceptanceStore.WorkRegistration registration = registrations.get(workId);
                    return java.util.Optional.of(new StructuredGenerationAcceptanceStore.WorkClaim(
                            workId, "attempt-" + workId, registration.taskId(), registration.identityKey(),
                            registration.skillName(), registration.operationName(), 1,
                            registration.ordinalStart(), registration.ordinalEnd(), registration.materialKey(),
                            registration.allowedEvidenceKeys(), "structured-all-worker"));
                });
        doAnswer(invocation -> {
            StructuredGenerationAcceptanceStore.WorkRegistration left = invocation.getArgument(1);
            StructuredGenerationAcceptanceStore.WorkRegistration right = invocation.getArgument(2);
            assertThat(left.ordinalStart()).isEqualTo(1);
            assertThat(left.ordinalEnd()).isEqualTo(6);
            assertThat(right.ordinalStart()).isEqualTo(7);
            assertThat(right.ordinalEnd()).isEqualTo(12);
            assertThat(left.allowedEvidenceKeys()).containsExactlyElementsOf(
                    material.units().subList(0, 6).stream().map(MaterialInventoryUnit::unitId).toList());
            assertThat(right.allowedEvidenceKeys()).containsExactlyElementsOf(
                    material.units().subList(6, 12).stream().map(MaterialInventoryUnit::unitId).toList());
            parentSplit.set(true);
            return null;
        }).when(store).splitFunctionListExtractionWork(any(), any(), any());
        when(skills.extractFunctionCandidates(any())).thenAnswer(invocation -> {
            FunctionCandidateExtractionInput input = invocation.getArgument(
                    0, FunctionCandidateExtractionInvocation.class).input();
            if (input.units().get(0).ordinal() == 1 && input.units().size() == 12) {
                throw new StructuredSkillExecutionException(splitFailure, true);
            }
            return success("feature-scope-reconciliation", noFunctionCandidateResult(input));
        });
        when(store.acceptedInputs("task-extraction-split"))
                .thenReturn(new StructuredGenerationAcceptanceStore.AcceptedInputs(List.of(), List.of()));
        StructuredWorkbookExportRequest rows = new StructuredWorkbookExportRequest(
                "task-extraction-split", List.of(), List.of());
        WorkbookArtifact artifact = new WorkbookArtifact(
                "artifact-extraction-split", "f".repeat(64), Path.of("extraction-split.xlsx"));
        when(repository.structuredWorkbookRequest("task-extraction-split")).thenReturn(rows);
        when(exporter.exportStructured(rows)).thenReturn(artifact);
        when(store.aggregateState("task-extraction-split"))
                .thenReturn(new StructuredGenerationAcceptanceStore.AggregateState(0, 0, 0, 0, 0, 2, 0, true));

        new DefaultStructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                store, exporter, new ObjectMapper(), heartbeat()).execute("task-extraction-split", request);

        var invocation = org.mockito.ArgumentCaptor.forClass(FunctionCandidateExtractionInvocation.class);
        verify(skills, org.mockito.Mockito.times(5)).extractFunctionCandidates(invocation.capture());
        List<FunctionCandidateExtractionInput.Unit> frozenUnits = material.units().stream().map(unit ->
                new FunctionCandidateExtractionInput.Unit(unit.unitId(), unit.ordinal(), unit.content())).toList();
        assertThat(invocation.getAllValues()).extracting(call -> call.input().units())
                .containsExactly(frozenUnits.subList(0, 12), frozenUnits.subList(0, 6), frozenUnits.subList(6, 12),
                        frozenUnits.subList(12, 24), frozenUnits.subList(24, 32));
        assertThat(invocation.getAllValues()).allSatisfy(call -> {
            assertThat(call.input().windowKey()).hasSize(64);
            assertThat(registrations.get("work-%d-%d".formatted(
                    call.input().units().get(0).ordinal(),
                    call.input().units().get(call.input().units().size() - 1).ordinal())).identityKey())
                    .isEqualTo(call.input().windowKey());
        });
        verify(skills, never()).extractFunctionList(any());
        verify(store).splitFunctionListExtractionWork(any(), any(), any());
        verify(store, never()).fail(org.mockito.ArgumentMatchers.argThat(claim ->
                "work-1-12".equals(claim.workItemId())), anyString());
    }

    /** [Req-ID]: REQ-FTG-016 */
    @Test
    void historicalThirtyTwoUnitExtractionSplitsBeforeKeeAndOwnsEveryUnitExactlyOnce() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest request = mock(CreateGenerationTaskRequest.class);
        MaterialInventoryDocument material = functionMaterialWithUnits(32);
        when(request.agentId()).thenReturn("agent-1");
        when(request.requirementScope()).thenReturn(scope("function-list-1"));
        when(repository.hasCompleteMaterialInventory("task-historical-presplit", request.requirementScope()))
                .thenReturn(true);
        when(repository.materialInventoryDocuments("task-historical-presplit")).thenReturn(List.of(material));
        when(sessions.openStructuredSession()).thenReturn("session-historical-presplit");
        when(store.materialWindowPlans("task-historical-presplit", "FEATURE_SCOPE_EXTRACT", "function-list-1"))
                .thenReturn(List.of(legacyWindow("FAILED")));
        when(store.register(any())).thenAnswer(invocation -> {
            StructuredGenerationAcceptanceStore.WorkRegistration row = invocation.getArgument(0);
            return "work-%d-%d".formatted(row.ordinalStart(), row.ordinalEnd());
        });
        AtomicBoolean parentSplit = new AtomicBoolean();
        when(store.isSplit("work-1-32")).thenAnswer(ignored -> parentSplit.get());
        when(store.splitQueuedHistoricalFunctionListExtractionWork(
                eq("work-1-32"), any(), any())).thenAnswer(invocation -> {
                    StructuredGenerationAcceptanceStore.WorkRegistration left = invocation.getArgument(1);
                    StructuredGenerationAcceptanceStore.WorkRegistration right = invocation.getArgument(2);
                    assertThat(left.allowedEvidenceKeys()).containsExactlyElementsOf(
                            java.util.stream.IntStream.rangeClosed(1, 16)
                                    .mapToObj(index -> "fn-unit-" + index).toList());
                    assertThat(right.allowedEvidenceKeys()).containsExactlyElementsOf(
                            java.util.stream.IntStream.rangeClosed(17, 32)
                                    .mapToObj(index -> "fn-unit-" + index).toList());
                    parentSplit.set(true);
                    return true;
                });
        when(store.claimRegistered(eq("task-historical-presplit"), anyString(), eq("structured-all-worker")))
                .thenAnswer(invocation -> {
                    String workId = invocation.getArgument(1);
                    String[] bounds = workId.substring("work-".length()).split("-");
                    int start = Integer.parseInt(bounds[0]);
                    int end = Integer.parseInt(bounds[1]);
                    return java.util.Optional.of(new StructuredGenerationAcceptanceStore.WorkClaim(
                            workId, "attempt-" + workId, "task-historical-presplit", "a".repeat(64),
                            "feature-scope-reconciliation", "FEATURE_SCOPE_EXTRACT", 1, start, end,
                            "function-list-1", java.util.stream.IntStream.rangeClosed(start, end)
                                    .mapToObj(index -> "fn-unit-" + index).toList(), "structured-all-worker"));
                });
        when(skills.extractFunctionList(any())).thenReturn(success(
                "feature-scope-reconciliation", new FunctionListExtractionResult(List.of())));
        when(store.acceptedInputs("task-historical-presplit"))
                .thenReturn(new StructuredGenerationAcceptanceStore.AcceptedInputs(List.of(), List.of()));
        StructuredWorkbookExportRequest rows = new StructuredWorkbookExportRequest(
                "task-historical-presplit", List.of(), List.of());
        WorkbookArtifact artifact = new WorkbookArtifact(
                "artifact-historical-presplit", "f".repeat(64), Path.of("historical-presplit.xlsx"));
        when(repository.structuredWorkbookRequest("task-historical-presplit")).thenReturn(rows);
        when(exporter.exportStructured(rows)).thenReturn(artifact);
        when(store.aggregateState("task-historical-presplit"))
                .thenReturn(new StructuredGenerationAcceptanceStore.AggregateState(0, 0, 0, 0, 0, 2, 0, true));

        new DefaultStructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                store, exporter, new ObjectMapper(), heartbeat()).execute("task-historical-presplit", request);

        var invocation = org.mockito.ArgumentCaptor.forClass(FunctionListExtractionInvocation.class);
        verify(skills, org.mockito.Mockito.atLeastOnce()).extractFunctionList(invocation.capture());
        assertThat(invocation.getAllValues()).allSatisfy(call -> assertThat(call.input().units()).hasSizeLessThanOrEqualTo(16));
        assertThat(invocation.getAllValues().stream().flatMap(call -> call.input().units().stream())
                .map(FunctionListExtractionInput.Unit::ordinal).toList())
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 32).boxed().toList());
    }

    /** [Req-ID]: REQ-FTG-016 */
    @Test
    void historicalPresplitSkipsCompletedSiblingAndOnlyInvokesUnfinishedChildren() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest request = mock(CreateGenerationTaskRequest.class);
        MaterialInventoryDocument material = functionMaterialWithUnits(64);
        when(request.agentId()).thenReturn("agent-1");
        when(request.requirementScope()).thenReturn(scope("function-list-1"));
        when(repository.hasCompleteMaterialInventory("task-historical-sibling", request.requirementScope()))
                .thenReturn(true);
        when(repository.materialInventoryDocuments("task-historical-sibling")).thenReturn(List.of(material));
        when(sessions.openStructuredSession()).thenReturn("session-historical-sibling");
        when(store.materialWindowPlans("task-historical-sibling", "FEATURE_SCOPE_EXTRACT", "function-list-1"))
                .thenReturn(List.of(legacyWindow("COMPLETED"), legacyWindow("FAILED")));
        when(store.register(any())).thenAnswer(invocation -> {
            StructuredGenerationAcceptanceStore.WorkRegistration row = invocation.getArgument(0);
            return "work-%d-%d".formatted(row.ordinalStart(), row.ordinalEnd());
        });
        when(store.isCompleted("work-1-32")).thenReturn(true);
        AtomicBoolean secondSplit = new AtomicBoolean();
        when(store.isSplit("work-33-64")).thenAnswer(ignored -> secondSplit.get());
        when(store.splitQueuedHistoricalFunctionListExtractionWork(
                eq("work-33-64"), any(), any())).thenAnswer(invocation -> {
                    secondSplit.set(true);
                    return true;
                });
        when(store.claimRegistered(eq("task-historical-sibling"), anyString(), eq("structured-all-worker")))
                .thenAnswer(invocation -> {
                    String workId = invocation.getArgument(1);
                    String[] bounds = workId.substring("work-".length()).split("-");
                    int start = Integer.parseInt(bounds[0]);
                    int end = Integer.parseInt(bounds[1]);
                    return java.util.Optional.of(new StructuredGenerationAcceptanceStore.WorkClaim(
                            workId, "attempt-" + workId, "task-historical-sibling", "a".repeat(64),
                            "feature-scope-reconciliation", "FEATURE_SCOPE_EXTRACT", 1, start, end,
                            "function-list-1", java.util.stream.IntStream.rangeClosed(start, end)
                                    .mapToObj(index -> "fn-unit-" + index).toList(), "structured-all-worker"));
                });
        when(skills.extractFunctionList(any())).thenReturn(success(
                "feature-scope-reconciliation", new FunctionListExtractionResult(List.of())));
        when(store.acceptedInputs("task-historical-sibling"))
                .thenReturn(new StructuredGenerationAcceptanceStore.AcceptedInputs(List.of(), List.of()));
        StructuredWorkbookExportRequest rows = new StructuredWorkbookExportRequest(
                "task-historical-sibling", List.of(), List.of());
        WorkbookArtifact artifact = new WorkbookArtifact(
                "artifact-historical-sibling", "f".repeat(64), Path.of("historical-sibling.xlsx"));
        when(repository.structuredWorkbookRequest("task-historical-sibling")).thenReturn(rows);
        when(exporter.exportStructured(rows)).thenReturn(artifact);
        when(store.aggregateState("task-historical-sibling"))
                .thenReturn(new StructuredGenerationAcceptanceStore.AggregateState(0, 0, 0, 0, 0, 3, 0, true));

        new DefaultStructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                store, exporter, new ObjectMapper(), heartbeat()).execute("task-historical-sibling", request);

        var invocation = org.mockito.ArgumentCaptor.forClass(FunctionListExtractionInvocation.class);
        verify(skills, org.mockito.Mockito.times(2)).extractFunctionList(invocation.capture());
        assertThat(invocation.getAllValues().stream().flatMap(call -> call.input().units().stream())
                .map(FunctionListExtractionInput.Unit::ordinal).toList())
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(33, 64).boxed().toList());
        verify(store, never()).splitQueuedHistoricalFunctionListExtractionWork(eq("work-1-32"), any(), any());
        verify(store, never()).claimRegistered("task-historical-sibling", "work-1-32", "structured-all-worker");
    }

    /** [Req-ID]: REQ-FTG-012 */
    @Test
    void oneUnitFunctionListWindowThatIsStillTooLargeFailsWithoutCreatingChildren() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest request = mock(CreateGenerationTaskRequest.class);
        when(request.agentId()).thenReturn("agent-1");
        when(request.requirementScope()).thenReturn(scope("function-list-1"));
        when(sessions.openStructuredSession()).thenReturn("session-extraction-minimum");
        when(traversal.traverse("task-extraction-minimum", request, false)).thenReturn(
                new RequirementMaterialTraversalService.TraversalResult(List.of(functionMaterialWithUnits(1))));
        AtomicReference<StructuredGenerationAcceptanceStore.WorkRegistration> registration = new AtomicReference<>();
        when(store.register(any())).thenAnswer(invocation -> {
            registration.set(invocation.getArgument(0));
            return "work-extraction-minimum";
        });
        var claim = new StructuredGenerationAcceptanceStore.WorkClaim(
                "work-extraction-minimum", "attempt-extraction-minimum", "task-extraction-minimum", "a".repeat(64),
                "feature-scope-reconciliation", "FEATURE_SCOPE_EXTRACT", 1,
                1, 1, "function-list-1", List.of("fn-unit-1"), "structured-all-worker");
        when(store.claimRegistered("task-extraction-minimum", "work-extraction-minimum", "structured-all-worker"))
                .thenReturn(java.util.Optional.of(claim));
        when(skills.extractFunctionCandidates(any())).thenThrow(
                new StructuredSkillExecutionException(StructuredSkillErrorType.RESPONSE_TOO_LARGE, false));
        when(store.acceptedInputs("task-extraction-minimum")).thenReturn(
                new StructuredGenerationAcceptanceStore.AcceptedInputs(List.of(), List.of()));
        when(store.aggregateState("task-extraction-minimum")).thenReturn(
                new StructuredGenerationAcceptanceStore.AggregateState(0, 0, 0, 0, 0, 0, 1, true,
                        0, 1, 1));

        new DefaultStructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                store, exporter, new ObjectMapper(), heartbeat()).execute("task-extraction-minimum", request);

        verify(store, never()).splitFunctionListExtractionWork(any(), any(), any());
        verify(store).fail(claim, "response_too_large");
        verify(repository).failStructuredTask("task-extraction-minimum",
                com.testcaseagent.structuredgeneration.StructuredCoverageStatus.UNABLE_TO_GENERATE);
    }

    /** [Req-ID]: REQ-FTG-012 */
    @Test
    void restartInvokesOnlyTheUnfinishedFunctionListExtractionChild() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest request = mock(CreateGenerationTaskRequest.class);
        MaterialInventoryDocument material = functionMaterialWithUnits(32);
        when(request.agentId()).thenReturn("agent-1");
        when(request.requirementScope()).thenReturn(scope("function-list-1"));
        when(repository.hasCompleteMaterialInventory("task-extraction-restart", request.requirementScope())).thenReturn(true);
        when(repository.materialInventoryDocuments("task-extraction-restart")).thenReturn(List.of(material));
        when(sessions.openStructuredSession()).thenReturn("session-extraction-restart");
        when(store.materialWindowPlans("task-extraction-restart", "FEATURE_SCOPE_EXTRACT", "function-list-1"))
                .thenReturn(List.of(legacyWindow("SPLIT")));
        when(store.register(any())).thenAnswer(invocation -> {
            StructuredGenerationAcceptanceStore.WorkRegistration value = invocation.getArgument(0);
            return "work-%d-%d".formatted(value.ordinalStart(), value.ordinalEnd());
        });
        when(store.isSplit("work-1-32")).thenReturn(true);
        when(store.isCompleted("work-1-16")).thenReturn(true);
        when(store.isCompleted("work-17-32")).thenReturn(false);
        when(store.claimRegistered("task-extraction-restart", "work-17-32", "structured-all-worker"))
                .thenReturn(java.util.Optional.of(new StructuredGenerationAcceptanceStore.WorkClaim(
                        "work-17-32", "attempt-17-32", "task-extraction-restart", "b".repeat(64),
                        "feature-scope-reconciliation", "FEATURE_SCOPE_EXTRACT", 1,
                        17, 32, "function-list-1", java.util.stream.IntStream.rangeClosed(17, 32)
                                .mapToObj(index -> "fn-unit-" + index).toList(), "structured-all-worker")));
        when(skills.extractFunctionList(any())).thenReturn(success(
                "feature-scope-reconciliation", new FunctionListExtractionResult(List.of())));
        when(store.acceptedInputs("task-extraction-restart"))
                .thenReturn(new StructuredGenerationAcceptanceStore.AcceptedInputs(List.of(), List.of()));
        StructuredWorkbookExportRequest rows = new StructuredWorkbookExportRequest(
                "task-extraction-restart", List.of(), List.of());
        WorkbookArtifact artifact = new WorkbookArtifact(
                "artifact-extraction-restart", "e".repeat(64), Path.of("extraction-restart.xlsx"));
        when(repository.structuredWorkbookRequest("task-extraction-restart")).thenReturn(rows);
        when(exporter.exportStructured(rows)).thenReturn(artifact);
        when(store.aggregateState("task-extraction-restart"))
                .thenReturn(new StructuredGenerationAcceptanceStore.AggregateState(0, 0, 0, 0, 0, 2, 0, true));

        new DefaultStructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                store, exporter, new ObjectMapper(), heartbeat()).execute("task-extraction-restart", request);

        var invocation = org.mockito.ArgumentCaptor.forClass(FunctionListExtractionInvocation.class);
        verify(skills).extractFunctionList(invocation.capture());
        assertThat(invocation.getValue().requirementScope().documents())
                .extracting(RequirementDocumentCoordinate::documentId)
                .containsExactly("function-list-1");
        assertThat(invocation.getValue().input().units()).extracting(FunctionListExtractionInput.Unit::ordinal)
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(17, 32).boxed().toList());
        verify(store, never()).claimRegistered("task-extraction-restart", "work-1-32", "structured-all-worker");
        verify(store, never()).claimRegistered("task-extraction-restart", "work-1-16", "structured-all-worker");
    }

    /** [Req-ID]: REQ-FTG-013 */
    @Test
    void restartSkipsACompletedSemanticSiblingAndKeepsTheFrozenContextBoundary() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest request = mock(CreateGenerationTaskRequest.class);
        MaterialInventoryDocument material = functionMaterialWithUnits(25);
        when(request.agentId()).thenReturn("agent-1");
        when(request.requirementScope()).thenReturn(scope("function-list-1"));
        when(repository.hasCompleteMaterialInventory("task-semantic-restart", request.requirementScope())).thenReturn(true);
        when(repository.materialInventoryDocuments("task-semantic-restart")).thenReturn(List.of(material));
        when(sessions.openStructuredSession()).thenReturn("session-semantic-restart");
        when(store.materialWindowPlans("task-semantic-restart", "FEATURE_SCOPE_EXTRACT", "function-list-1"))
                .thenReturn(List.of(new StructuredGenerationAcceptanceStore.MaterialWindowPlan(
                        "work-1-12", "a".repeat(64), "COMPLETED", 1, 12, "function-list-1",
                        java.util.stream.IntStream.rangeClosed(1, 12).mapToObj(i -> "fn-unit-" + i).toList(),
                        java.util.stream.IntStream.rangeClosed(13, 16).mapToObj(i -> "fn-unit-" + i).toList(), null, 0)));
        when(store.register(any())).thenAnswer(invocation -> {
            StructuredGenerationAcceptanceStore.WorkRegistration value = invocation.getArgument(0);
            return "work-%d-%d".formatted(value.ordinalStart(), value.ordinalEnd());
        });
        when(store.isCompleted("work-1-12")).thenReturn(true);
        when(store.isCompleted("work-13-25")).thenReturn(false);
        when(store.claimRegistered("task-semantic-restart", "work-13-25", "structured-all-worker"))
                .thenAnswer(invocation -> java.util.Optional.of(new StructuredGenerationAcceptanceStore.WorkClaim(
                        "work-13-25", "attempt-13-25", "task-semantic-restart", "b".repeat(64),
                        "feature-scope-reconciliation", "FEATURE_SCOPE_EXTRACT", 1, 13, 25,
                        "function-list-1", java.util.stream.IntStream.rangeClosed(13, 25)
                                .mapToObj(i -> "fn-unit-" + i).toList(), "structured-all-worker",
                        "function-list-1", java.util.stream.IntStream.rangeClosed(9, 12)
                                .mapToObj(i -> "fn-unit-" + i).toList(), null, 0)));
        stubNoFunctionCandidates(skills);
        when(store.acceptedInputs("task-semantic-restart"))
                .thenReturn(new StructuredGenerationAcceptanceStore.AcceptedInputs(List.of(), List.of()));
        StructuredWorkbookExportRequest rows = new StructuredWorkbookExportRequest(
                "task-semantic-restart", List.of(), List.of());
        WorkbookArtifact artifact = new WorkbookArtifact(
                "artifact-semantic-restart", "e".repeat(64), Path.of("semantic-restart.xlsx"));
        when(repository.structuredWorkbookRequest("task-semantic-restart")).thenReturn(rows);
        when(exporter.exportStructured(rows)).thenReturn(artifact);
        when(store.aggregateState("task-semantic-restart"))
                .thenReturn(new StructuredGenerationAcceptanceStore.AggregateState(0, 0, 0, 0, 0, 2, 0, true));

        new DefaultStructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                store, exporter, new ObjectMapper(), heartbeat()).execute("task-semantic-restart", request);

        var invocation = org.mockito.ArgumentCaptor.forClass(FunctionCandidateExtractionInvocation.class);
        verify(skills).extractFunctionCandidates(invocation.capture());
        assertThat(invocation.getValue().input().units()).extracting(FunctionCandidateExtractionInput.Unit::ordinal)
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(13, 25).boxed().toList());
        assertThat(invocation.getValue().input().contextUnits()).extracting(FunctionCandidateExtractionInput.Unit::ordinal)
                .containsExactly(9, 10, 11, 12);
        var registration = org.mockito.ArgumentCaptor.forClass(
                StructuredGenerationAcceptanceStore.WorkRegistration.class);
        verify(store, org.mockito.Mockito.atLeastOnce()).register(registration.capture());
        assertThat(registration.getAllValues()).filteredOn(row -> "FEATURE_SCOPE_EXTRACT".equals(row.operationName()))
                .filteredOn(row -> Integer.valueOf(13).equals(row.ordinalStart()))
                .singleElement().satisfies(row -> {
                    assertThat(row.allowedEvidenceKeys()).containsExactlyElementsOf(
                            java.util.stream.IntStream.rangeClosed(13, 25)
                                    .mapToObj(i -> "fn-unit-" + i).toList());
                    assertThat(row.contextEvidenceKeys()).containsExactly(
                            "fn-unit-9", "fn-unit-10", "fn-unit-11", "fn-unit-12");
                    assertThat(row.allowedEvidenceKeys()).doesNotContainAnyElementsOf(row.contextEvidenceKeys());
                });
        verify(store, never()).claimRegistered("task-semantic-restart", "work-1-12", "structured-all-worker");
    }

    /** [Req-ID]: REQ-FTG-010, REQ-FTG-015 */
    @Test
    void restartUsesFrozenInventoryAndInvokesOnlyTheUnfinishedReviewChild() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest request = mock(CreateGenerationTaskRequest.class);
        MaterialInventoryDocument material = prototypeMaterialWithUnits(32);
        when(request.agentId()).thenReturn("agent-1");
        when(request.requirementScope()).thenReturn(scope("prototype-1", "function-list-1"));
        when(repository.hasCompleteMaterialInventory("task-review-restart", request.requirementScope())).thenReturn(true);
        when(repository.materialInventoryDocuments("task-review-restart")).thenReturn(List.of(material));
        when(sessions.openStructuredSession()).thenReturn("session-review-restart");
        when(store.materialWindowPlans("task-review-restart", "REQUIREMENT_MATERIAL_REVIEW", "prototype-1"))
                .thenReturn(List.of(legacyWindow("SPLIT")));
        when(store.register(any())).thenAnswer(invocation -> {
            StructuredGenerationAcceptanceStore.WorkRegistration registration = invocation.getArgument(0);
            return "work-%d-%d".formatted(registration.ordinalStart(), registration.ordinalEnd());
        });
        when(store.isSplit("work-1-32")).thenReturn(true);
        when(store.isCompleted("work-1-16")).thenReturn(true);
        when(store.isCompleted("work-17-32")).thenReturn(false);
        when(store.claimRegistered("task-review-restart", "work-17-32", "structured-all-worker"))
                .thenReturn(java.util.Optional.of(new StructuredGenerationAcceptanceStore.WorkClaim(
                        "work-17-32", "attempt-17-32", "task-review-restart", "b".repeat(64),
                        "requirement-material-quality-review", "REQUIREMENT_MATERIAL_REVIEW", 1,
                        17, 32, "prototype-1", java.util.stream.IntStream.rangeClosed(17, 32)
                                .mapToObj(index -> "prototype-unit-" + index).toList(), "structured-all-worker")));
        when(skills.reviewRequirementMaterial(any())).thenAnswer(invocation -> {
            var input = invocation.getArgument(
                    0, com.testcaseagent.knowledgeagent.RequirementMaterialQualityReviewInvocation.class).input();
            var first = input.units().get(0);
            return success("requirement-material-quality-review", new RequirementMaterialQualityReviewResult(
                    List.of(), List.of(new RequirementMaterialQualityReviewResult.ReviewFinding(
                    "finding-restart", RequirementMaterialQualityReviewResult.RootCauseKind.MISSING_FUNCTION_SCOPE,
                    "功能范围需要补充", new RequirementMaterialQualityReviewResult.AffectedScope(
                    List.of(first.unitKey()), "当前原型范围"),
                    new RequirementMaterialQualityReviewResult.BadSourceExample(first.unitKey(), first.content()),
                    new RequirementMaterialQualityReviewResult.ProposedGoodExample(
                            RequirementMaterialQualityReviewResult.ProposalStatus.PENDING_CONFIRMATION,
                            "待需求方确认：补充功能范围"),
                    "当前材料未明确功能范围", List.of(first.unitKey()), "影响功能测试设计", "当前项目待确认",
                    "设计中心补充功能范围模板", RequirementMaterialQualityReviewResult.HandlingLevel.IMPROVEMENT))));
        });
        when(store.acceptedInputs("task-review-restart"))
                .thenReturn(new StructuredGenerationAcceptanceStore.AcceptedInputs(List.of(), List.of()));
        StructuredWorkbookExportRequest rows = new StructuredWorkbookExportRequest("task-review-restart", List.of(), List.of());
        WorkbookArtifact artifact = new WorkbookArtifact("artifact-review-restart", "f".repeat(64), Path.of("review-restart.xlsx"));
        when(repository.structuredWorkbookRequest("task-review-restart")).thenReturn(rows);
        when(exporter.exportStructured(rows)).thenReturn(artifact);
        when(store.aggregateState("task-review-restart"))
                .thenReturn(new StructuredGenerationAcceptanceStore.AggregateState(2, 2, 0, 0, 0, 2, 0, true));

        new DefaultStructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                store, exporter, new ObjectMapper(), heartbeat()).execute("task-review-restart", request);

        verify(traversal, never()).traverse(anyString(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        var invocation = org.mockito.ArgumentCaptor.forClass(
                com.testcaseagent.knowledgeagent.RequirementMaterialQualityReviewInvocation.class);
        verify(skills).reviewRequirementMaterial(invocation.capture());
        assertThat(invocation.getValue().requirementScope().documents())
                .extracting(RequirementDocumentCoordinate::documentId)
                .containsExactly("prototype-1");
        assertThat(invocation.getValue().input().units())
                .extracting(com.testcaseagent.knowledgeagent.RequirementMaterialQualityReviewInput.MaterialUnit::ordinal)
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(17, 32).boxed().toList());
        verify(store, never()).claimRegistered("task-review-restart", "work-1-32", "structured-all-worker");
        verify(store, never()).claimRegistered("task-review-restart", "work-1-16", "structured-all-worker");
    }

    /** [Req-ID]: REQ-FSC-008 */
    @Test
    void buildsV2PageFromTheComplete297Item514FactCatalogBeforeCallingKee() throws Exception {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        RuntimeException afterSplit = new RuntimeException("stop-after-v2-split");
        AtomicReference<com.fasterxml.jackson.databind.JsonNode> wireInput = new AtomicReference<>();
        ObjectMapper mapper = new ObjectMapper();
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class, invocation -> {
            if (invocation.getMethod().getName().equals("reconcileFeatureScopePage")) {
                Object pageInvocation = invocation.getArgument(0);
                Object pageInput = pageInvocation.getClass().getMethod("input").invoke(pageInvocation);
                wireInput.set(mapper.valueToTree(pageInput));
                throw new StructuredSkillExecutionException(StructuredSkillErrorType.RESPONSE_TOO_LARGE, false);
            }
            if (invocation.getMethod().getName().equals("reconcileFeatureScope")) {
                throw new AssertionError("New or stage-gap reconciliation must not invoke the retained V1 operation");
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest request = mock(CreateGenerationTaskRequest.class);
        when(request.agentId()).thenReturn("agent-1");
        when(request.requirementScope()).thenReturn(scope("function-list-1", "requirement-1"));
        when(sessions.openStructuredSession()).thenReturn("session-task-wide");
        when(traversal.traverse("task-wide", request, false)).thenReturn(
                new RequirementMaterialTraversalService.TraversalResult(List.of()));

        List<StructuredGenerationAcceptanceStore.AcceptedFunctionItem> items = java.util.stream.IntStream.range(0, 297)
                .mapToObj(index -> new StructuredGenerationAcceptanceStore.AcceptedFunctionItem(
                        "item-%03d".formatted(296 - index), "功能路径 " + index, "功能说明 " + index,
                        List.of("item-evidence-%03d".formatted(index))))
                .toList();
        List<StructuredGenerationAcceptanceStore.AcceptedFact> facts = java.util.stream.IntStream.range(0, 514)
                .mapToObj(index -> new StructuredGenerationAcceptanceStore.AcceptedFact(
                        "fact-%03d".formatted(513 - index), "需求功能 " + index,
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of("fact-evidence-%03d".formatted(index)),
                        Map.of("fact-evidence-%03d".formatted(index), "需求证据 " + index)))
                .toList();
        when(store.acceptedInputs("task-wide"))
                .thenReturn(new StructuredGenerationAcceptanceStore.AcceptedInputs(facts, items));

        AtomicReference<StructuredGenerationAcceptanceStore.WorkRegistration> registration = new AtomicReference<>();
        when(store.register(any())).thenAnswer(invocation -> {
            registration.set(invocation.getArgument(0));
            return "work-task-wide";
        });
        when(store.claimRegistered("task-wide", "work-task-wide", "structured-all-worker"))
                .thenAnswer(invocation -> {
                    StructuredGenerationAcceptanceStore.WorkRegistration frozen = registration.get();
                    return java.util.Optional.of(new StructuredGenerationAcceptanceStore.WorkClaim(
                            "work-task-wide", "attempt-task-wide", "task-wide", frozen.identityKey(),
                            frozen.skillName(), frozen.operationName(), 1, null, null, null,
                            frozen.allowedEvidenceKeys(), "structured-all-worker"));
                });
        AtomicReference<StructuredGenerationAcceptanceStore.ReconciliationRunPlan> durablePlan =
                new AtomicReference<>();
        doAnswer(invocation -> {
            durablePlan.set(invocation.getArgument(1));
            return null;
        }).when(store).initializeReconciliationRun(any(), any());
        AtomicInteger pendingPoll = new AtomicInteger();
        when(store.pendingReconciliationPages(eq("work-task-wide"), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    if (pendingPoll.getAndIncrement() == 0) {
                        return List.of(durablePlan.get().initialOwnerWindows().get(0));
                    }
                    throw afterSplit;
                });
        AtomicReference<StructuredGenerationAcceptanceStore.ReconciliationOwnerWindow> leftChild =
                new AtomicReference<>();
        AtomicReference<StructuredGenerationAcceptanceStore.ReconciliationOwnerWindow> rightChild =
                new AtomicReference<>();
        doAnswer(invocation -> {
            leftChild.set(invocation.getArgument(3));
            rightChild.set(invocation.getArgument(4));
            return null;
        }).when(store).splitReconciliationPage(any(), any(), anyString(), any(), any());
        var coordinator = new DefaultStructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                store, exporter, mapper, heartbeat());

        assertThatThrownBy(() -> coordinator.execute("task-wide", request))
                .isInstanceOfSatisfying(com.testcaseagent.validation.StructuredValidationException.class,
                        safe -> assertThat(safe.failure().code())
                                .isEqualTo("STRUCTURED_COORDINATOR_UNEXPECTED_FAILURE"));
        assertThat(wireInput.get().path("operation").asText()).isEqualTo("reconcile_page");
        assertThat(wireInput.get().path("protocol_version").asText()).isEqualTo("2");
        assertThat(wireInput.get().path("run").path("function_item_count").asInt()).isEqualTo(297);
        assertThat(wireInput.get().path("run").path("requirement_fact_count").asInt()).isEqualTo(514);
        assertThat(wireInput.get().path("run").path("catalog_sha256").asText()).matches("[0-9a-f]{64}");
        assertThat(wireInput.get().path("global_catalog").path("function_list_items")).hasSize(297);
        assertThat(wireInput.get().path("global_catalog").path("requirement_facts")).hasSize(514);
        assertThat(wireInput.get().path("owner_window").path("owner_source_refs")).isNotEmpty();
        String runKey = wireInput.get().path("run").path("run_key").asText();
        String compactOwnerRefs = mapper.writeValueAsString(
                wireInput.get().path("owner_window").path("owner_source_refs"));
        String pageIdentityBytes = "reconcile-page-v2\n" + runKey + "\n" + compactOwnerRefs;
        assertThat(wireInput.get().path("owner_window").path("page_key").asText())
                .isEqualTo(sha256(pageIdentityBytes))
                .isNotEqualTo(sha256(pageIdentityBytes + "\n"));
        var originalOwners = durablePlan.get().initialOwnerWindows().get(0).ownerSourceRefs();
        assertThat(leftChild.get().ownerSourceRefs()).containsExactlyElementsOf(originalOwners.subList(0, 50));
        assertThat(rightChild.get().ownerSourceRefs()).containsExactlyElementsOf(originalOwners.subList(50, 100));
        assertThat(leftChild.get().ownerSourceRefs()).doesNotContainAnyElementsOf(rightChild.get().ownerSourceRefs());
    }

    /** [Req-ID]: REQ-ESR-003, REQ-ESR-011 */
    @Test
    void rebuiltInvalidReconciliationStagingInvokesKeeInsteadOfReusingCompletedPages() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest request = mock(CreateGenerationTaskRequest.class);
        String taskId = "task-rebuilt-reconciliation";
        when(request.agentId()).thenReturn("agent-1");
        when(request.requirementScope()).thenReturn(scope("function-list-1"));
        when(store.hasCompletedMaterialStages(taskId)).thenReturn(true);
        when(store.hasFunctionCandidateAudit(taskId)).thenReturn(true);
        when(store.persistedValidationRegistry(taskId)).thenReturn(
                com.testcaseagent.validation.StructuredValidationRegistry.forTask(taskId));
        when(sessions.openStructuredSession()).thenReturn("session-rebuilt-reconciliation");
        var item = new StructuredGenerationAcceptanceStore.AcceptedFunctionItem(
                "item-rebuilt", "订单/提交", "提交订单", List.of("evidence-rebuilt"));
        when(store.acceptedInputs(taskId)).thenReturn(
                new StructuredGenerationAcceptanceStore.AcceptedInputs(List.of(), List.of(item)));
        when(store.register(any())).thenReturn("work-rebuilt-reconciliation");
        var claim = new StructuredGenerationAcceptanceStore.WorkClaim(
                "work-rebuilt-reconciliation", "attempt-rebuilt-reconciliation", taskId, "a".repeat(64),
                "feature-scope-reconciliation", "FEATURE_SCOPE_RECONCILIATION_V2", 3,
                null, null, null, List.of("evidence-rebuilt"), "structured-all-worker");
        when(store.claimRegistered(taskId, "work-rebuilt-reconciliation", "structured-all-worker"))
                .thenReturn(java.util.Optional.of(claim));
        AtomicReference<StructuredGenerationAcceptanceStore.ReconciliationRunPlan> durablePlan =
                new AtomicReference<>();
        doAnswer(invocation -> {
            durablePlan.set(invocation.getArgument(1));
            return null;
        }).when(store).initializeReconciliationRun(eq(claim), any());
        AtomicReference<StructuredGenerationAcceptanceStore.ReconciliationPageStage> staged =
                new AtomicReference<>();
        AtomicInteger pendingPoll = new AtomicInteger();
        when(store.pendingReconciliationPages(eq(claim.workItemId()), anyString(), anyString()))
                .thenAnswer(invocation -> pendingPoll.getAndIncrement() == 0
                        ? List.of(durablePlan.get().initialOwnerWindows().get(0)) : List.of());
        doAnswer(invocation -> {
            staged.set(invocation.getArgument(1));
            return null;
        }).when(store).stageReconciliationPage(eq(claim), any());
        when(store.stagedReconciliationPages(eq(claim.workItemId()), anyString(), anyString()))
                .thenAnswer(invocation -> List.of(staged.get()));
        when(skills.reconcileFeatureScopePage(any())).thenAnswer(invocation -> {
            FeatureScopeReconciliationPageInput input = invocation.getArgument(
                    0, FeatureScopeReconciliationPageInvocation.class).input();
            List<String> itemKeys = List.of(input.globalCatalog().functionListItems().get(0).itemKey());
            List<FeatureScopeReconciliationPageInput.SourceRef> refs =
                    FeatureScopeReconciliationV2Canonicalizer.relationSourceRefs(itemKeys, List.of());
            String relationKey = FeatureScopeReconciliationV2Canonicalizer.reconciliationKey(
                    input.run().runKey(), FeatureScopeReconciliationResult.Classification.FUNCTION_LIST_ONLY,
                    FeatureScopeReconciliationResult.ConfirmationStatus.CONFIRMED, refs);
            return success("feature-scope-reconciliation", new FeatureScopeReconciliationPageResult(
                    FeatureScopeReconciliationPageInput.OPERATION, FeatureScopeReconciliationPageInput.PROTOCOL_VERSION,
                    input.run().runKey(), input.ownerWindow().pageKey(), input.ownerWindow().ownerSourceRefs(),
                    List.of(new FeatureScopeReconciliationPageResult.Reconciliation(
                            relationKey, refs.get(0), itemKeys, List.of(),
                            FeatureScopeReconciliationResult.Classification.FUNCTION_LIST_ONLY,
                            List.of("evidence-rebuilt"), "仅见功能清单",
                            FeatureScopeReconciliationResult.ConfirmationStatus.CONFIRMED))));
        });
        when(store.acceptedConfirmedFunctions(taskId)).thenReturn(List.of());
        StructuredWorkbookExportRequest rows = new StructuredWorkbookExportRequest(taskId, List.of(), List.of());
        WorkbookArtifact artifact = new WorkbookArtifact(
                "artifact-rebuilt", "b".repeat(64), Path.of("rebuilt.xlsx"));
        when(repository.structuredWorkbookRequest(taskId)).thenReturn(rows);
        when(exporter.exportStructured(rows)).thenReturn(artifact);
        when(store.aggregateState(taskId)).thenReturn(
                new StructuredGenerationAcceptanceStore.AggregateState(0, 0, 1, 1, 0, 1, 0, true));

        new DefaultStructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                store, exporter, new ObjectMapper(), heartbeat()).execute(taskId, request);

        verify(skills).reconcileFeatureScopePage(any());
        verify(store).stageReconciliationPage(eq(claim), any());
        verify(store).publishReconciliationRun(eq(claim), any());
        verify(traversal, never()).traverse(anyString(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    /** [Req-ID]: REQ-ESR-003, REQ-ESR-012, REQ-ESR-013 */
    @Test
    void resumedReconciliationInvokesOnlyTheFirstPersistedPlannedPage() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest request = mock(CreateGenerationTaskRequest.class);
        String taskId = "task-resumed-reconciliation-model-failure";
        when(request.agentId()).thenReturn("agent-1");
        when(request.requirementScope()).thenReturn(scope("function-list-1"));
        when(store.hasCompletedMaterialStages(taskId)).thenReturn(true);
        when(store.hasFunctionCandidateAudit(taskId)).thenReturn(true);
        when(store.persistedValidationRegistry(taskId)).thenReturn(
                com.testcaseagent.validation.StructuredValidationRegistry.forTask(taskId));
        when(sessions.openStructuredSession()).thenReturn("session-resumed-reconciliation");
        // Four owner windows prove recovery is based on the persisted protocol graph rather than one fixed page count:
        // three completed pages are absent from the pending seam and only the fourth planned page reaches KEE.
        List<StructuredGenerationAcceptanceStore.AcceptedFunctionItem> items = java.util.stream.IntStream.range(0, 301)
                .mapToObj(index -> new StructuredGenerationAcceptanceStore.AcceptedFunctionItem(
                        "resumed-item-%03d".formatted(index), "功能路径/" + index, "功能说明 " + index,
                        List.of("resumed-evidence-%03d".formatted(index))))
                .toList();
        when(store.acceptedInputs(taskId)).thenReturn(
                new StructuredGenerationAcceptanceStore.AcceptedInputs(List.of(), items));
        when(store.register(any())).thenReturn("work-resumed-reconciliation");
        var claim = new StructuredGenerationAcceptanceStore.WorkClaim(
                "work-resumed-reconciliation", "attempt-resumed-reconciliation", taskId, "a".repeat(64),
                "feature-scope-reconciliation", "FEATURE_SCOPE_RECONCILIATION_V2", 7,
                null, null, null, items.stream().flatMap(item -> item.evidenceKeys().stream()).toList(),
                "structured-all-worker");
        when(store.claimRegistered(taskId, claim.workItemId(), "structured-all-worker"))
                .thenReturn(java.util.Optional.of(claim));
        AtomicReference<StructuredGenerationAcceptanceStore.ReconciliationRunPlan> durablePlan =
                new AtomicReference<>();
        AtomicReference<StructuredGenerationAcceptanceStore.ReconciliationOwnerWindow> persistedPlannedLeaf =
                new AtomicReference<>();
        doAnswer(invocation -> {
            StructuredGenerationAcceptanceStore.ReconciliationRunPlan initialized = invocation.getArgument(1);
            durablePlan.set(initialized);
            var wireRefs = List.of(new FeatureScopeReconciliationPageInput.SourceRef(
                    FeatureScopeReconciliationPageInput.SourceType.FUNCTION_LIST_ITEM, "resumed-item-300"));
            persistedPlannedLeaf.set(new StructuredGenerationAcceptanceStore.ReconciliationOwnerWindow(
                    FeatureScopeReconciliationV2Canonicalizer.pageKey(initialized.run().runKey(), wireRefs),
                    List.of(new StructuredGenerationAcceptanceStore.ReconciliationSourceRef(
                            "function_list_item", "resumed-item-300"))));
            return null;
        }).when(store).initializeReconciliationRun(eq(claim), any());
        AtomicInteger pendingPoll = new AtomicInteger();
        when(store.pendingReconciliationPages(eq(claim.workItemId()), anyString(), anyString()))
                .thenAnswer(invocation -> pendingPoll.getAndIncrement() == 0
                        ? List.of(persistedPlannedLeaf.get()) : List.of());
        AtomicReference<FeatureScopeReconciliationPageInput.OwnerWindow> invokedWindow = new AtomicReference<>();
        AtomicReference<StructuredGenerationAcceptanceStore.ReconciliationPageStage> staged = new AtomicReference<>();
        when(skills.reconcileFeatureScopePage(any())).thenAnswer(invocation -> {
            FeatureScopeReconciliationPageInput input = invocation.getArgument(
                    0, FeatureScopeReconciliationPageInvocation.class).input();
            invokedWindow.set(input.ownerWindow());
            String itemKey = input.ownerWindow().ownerSourceRefs().get(0).sourceKey();
            List<FeatureScopeReconciliationPageInput.SourceRef> refs =
                    FeatureScopeReconciliationV2Canonicalizer.relationSourceRefs(List.of(itemKey), List.of());
            String relationKey = FeatureScopeReconciliationV2Canonicalizer.reconciliationKey(
                    input.run().runKey(), FeatureScopeReconciliationResult.Classification.FUNCTION_LIST_ONLY,
                    FeatureScopeReconciliationResult.ConfirmationStatus.CONFIRMED, refs);
            return success("feature-scope-reconciliation", new FeatureScopeReconciliationPageResult(
                    FeatureScopeReconciliationPageInput.OPERATION, FeatureScopeReconciliationPageInput.PROTOCOL_VERSION,
                    input.run().runKey(), input.ownerWindow().pageKey(), input.ownerWindow().ownerSourceRefs(),
                    List.of(new FeatureScopeReconciliationPageResult.Reconciliation(
                            relationKey, refs.get(0), List.of(itemKey), List.of(),
                            FeatureScopeReconciliationResult.Classification.FUNCTION_LIST_ONLY,
                            List.of("resumed-evidence-300"), "仅见功能清单",
                            FeatureScopeReconciliationResult.ConfirmationStatus.CONFIRMED))));
        });
        doAnswer(invocation -> {
            staged.set(invocation.getArgument(1));
            return null;
        }).when(store).stageReconciliationPage(eq(claim), any());
        when(store.stagedReconciliationPages(eq(claim.workItemId()), anyString(), anyString()))
                .thenAnswer(invocation -> List.of(staged.get()));

        var coordinator = new DefaultStructuredAllGenerationCoordinator(
                repository, traversal, skills, sessions, store, exporter, new ObjectMapper(), heartbeat());
        assertThatThrownBy(() -> coordinator.execute(taskId, request))
                .isInstanceOf(com.testcaseagent.validation.StructuredValidationException.class);

        assertThat(durablePlan.get().initialOwnerWindows()).hasSize(4);
        assertThat(persistedPlannedLeaf.get())
                .isEqualTo(durablePlan.get().initialOwnerWindows().get(3))
                .isNotSameAs(durablePlan.get().initialOwnerWindows().get(3));
        assertThat(invokedWindow.get().pageKey())
                .isEqualTo(persistedPlannedLeaf.get().pageKey())
                .isNotEqualTo(durablePlan.get().initialOwnerWindows().get(0).pageKey());
        verify(skills, times(1)).reconcileFeatureScopePage(any());
        verify(store).stageReconciliationPage(eq(claim), any());
        verify(store, times(2)).pendingReconciliationPages(
                claim.workItemId(), durablePlan.get().run().runKey(), durablePlan.get().run().catalogSha256());
    }

    /** [Req-ID]: REQ-FSC-008 */
    @Test
    void oneOwnerV2WindowThatStillExceedsKeeCapacityFailsClosedWithoutSplitting() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest request = mock(CreateGenerationTaskRequest.class);
        StructuredWorkLeaseHeartbeat heartbeat = heartbeat();
        when(request.agentId()).thenReturn("agent-1");
        when(request.requirementScope()).thenReturn(scope("function-list-1"));
        when(sessions.openStructuredSession()).thenReturn("session-minimum-window");
        when(traversal.traverse("task-minimum-window", request, false)).thenReturn(
                new RequirementMaterialTraversalService.TraversalResult(List.of()));

        var item = new StructuredGenerationAcceptanceStore.AcceptedFunctionItem(
                "item-only", "唯一功能", "唯一功能说明", List.of("evidence-only"));
        when(store.acceptedInputs("task-minimum-window")).thenReturn(
                new StructuredGenerationAcceptanceStore.AcceptedInputs(List.of(), List.of(item)));
        AtomicReference<StructuredGenerationAcceptanceStore.WorkRegistration> registration = new AtomicReference<>();
        when(store.register(any())).thenAnswer(invocation -> {
            registration.set(invocation.getArgument(0));
            return "work-minimum-window";
        });
        var claim = new StructuredGenerationAcceptanceStore.WorkClaim(
                "work-minimum-window", "attempt-minimum-window", "task-minimum-window", "a".repeat(64),
                "feature-scope-reconciliation", "FEATURE_SCOPE_RECONCILIATION_V2", 1,
                null, null, null, List.of("evidence-only"), "structured-all-worker");
        when(store.claimRegistered("task-minimum-window", "work-minimum-window", "structured-all-worker"))
                .thenReturn(java.util.Optional.of(claim));
        AtomicReference<StructuredGenerationAcceptanceStore.ReconciliationRunPlan> durablePlan =
                new AtomicReference<>();
        doAnswer(invocation -> {
            durablePlan.set(invocation.getArgument(1));
            return null;
        }).when(store).initializeReconciliationRun(any(), any());
        when(store.pendingReconciliationPages(eq("work-minimum-window"), anyString(), anyString()))
                .thenAnswer(invocation -> List.of(durablePlan.get().initialOwnerWindows().get(0)));
        when(skills.reconcileFeatureScopePage(any())).thenThrow(
                new StructuredSkillExecutionException(StructuredSkillErrorType.RESPONSE_TOO_LARGE, true));

        var coordinator = new DefaultStructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                store, exporter, new ObjectMapper(), heartbeat);

        assertThatThrownBy(() -> coordinator.execute("task-minimum-window", request))
                .isInstanceOfSatisfying(StructuredSkillExecutionException.class, failure -> {
                    assertThat(failure.type()).isEqualTo(StructuredSkillErrorType.RESPONSE_TOO_LARGE);
                    assertThat(failure.repairAttempted()).isTrue();
                });
        assertThat(registration.get().operationName()).isEqualTo("FEATURE_SCOPE_RECONCILIATION_V2");
        verify(store, never()).splitReconciliationPage(any(), any(), anyString(), any(), any());
        verify(store, never()).stageReconciliationPage(any(), any());
        verify(store).fail(claim, "response_too_large");
        verify(repository).failStructuredTask(eq("task-minimum-window"),
                eq(com.testcaseagent.structuredgeneration.StructuredCoverageStatus.PENDING),
                any(com.testcaseagent.validation.StructuredValidationFailure.class));
    }

    /** [Req-ID]: REQ-FTG-005 */
    @Test
    void classifiesUngroundedRequirementFactsAsBusinessValidationWithoutStartingTheNextStage() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest request = mock(CreateGenerationTaskRequest.class);
        when(request.agentId()).thenReturn("agent-1");
        when(request.requirementScope()).thenReturn(scope("material-1"));
        when(sessions.openStructuredSession()).thenReturn("session-1");
        when(traversal.traverse("task-review-grounding", request, false)).thenReturn(
                new RequirementMaterialTraversalService.TraversalResult(List.of(material(
                        "material-1", "REQUIREMENT", "evidence-1",
                        "已注册且状态正常的用户在登录页提交账号和正确密码后，系统进入首页并显示当前用户名称"))));
        AtomicReference<StructuredGenerationAcceptanceStore.WorkRegistration> registration = new AtomicReference<>();
        when(store.register(any())).thenAnswer(invocation -> {
            registration.set(invocation.getArgument(0));
            return "work-review-grounding";
        });
        when(store.claimRegistered(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            var frozen = registration.get();
            return java.util.Optional.of(new StructuredGenerationAcceptanceStore.WorkClaim(
                    "work-review-grounding", "attempt-review-grounding", frozen.taskId(), frozen.identityKey(),
                    frozen.skillName(), frozen.operationName(), 1, frozen.ordinalStart(), frozen.ordinalEnd(),
                    frozen.materialKey(), frozen.allowedEvidenceKeys(), invocation.getArgument(2)));
        });
        when(skills.reviewRequirementMaterial(any())).thenReturn(success("requirement-material-quality-review",
                new RequirementMaterialQualityReviewResult(List.of(new RequirementMaterialQualityReviewResult.RequirementFact(
                        "model-fact", "用户中心→账号登录", List.of("已注册且状态正常的用户"),
                        List.of("用户在登录页提交账号和密码"), List.of("账号", "密码"),
                        List.of("密码必须正确", "用户状态必须正常", "用户必须已注册"),
                        List.of("系统进入首页", "首页显示当前用户名称"), List.of(),
                        List.of("用户会话状态由未登录变为已登录"), List.of(), List.of(),
                        List.of("evidence-1"))), List.of())));
        org.mockito.Mockito.doAnswer(invocation -> {
            com.testcaseagent.validation.RequirementMaterialReviewValidator validator = invocation.getArgument(1);
            validator.validate(invocation.getArgument(2), invocation.getArgument(3));
            return null;
        }).when(store).acceptReview(any(), any(), any(), any());
        when(store.acceptedInputs("task-review-grounding"))
                .thenThrow(new IllegalArgumentException("Ungrounded review fact crossed the acceptance boundary"));

        var coordinator = new DefaultStructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                store, exporter, new ObjectMapper(), heartbeat());

        assertThatThrownBy(() -> coordinator.execute("task-review-grounding", request))
                .isInstanceOfSatisfying(com.testcaseagent.validation.StructuredValidationException.class,
                        safe -> assertThat(safe.failure().code())
                                .isEqualTo("REVIEW_FACT_DIRECT_EVIDENCE_UNSUPPORTED"));
        verify(store).fail(any(), org.mockito.ArgumentMatchers.eq("business_validation_failed"),
                org.mockito.ArgumentMatchers.argThat(failure ->
                        failure.code().equals("REVIEW_FACT_DIRECT_EVIDENCE_UNSUPPORTED")
                                && failure.path().equals("$.requirement_facts[0].function")
                                && failure.message().equals("正式需求事实未在引用材料单元中直接出现")));
        verify(skills).reviewRequirementMaterial(any());
        verify(skills, never()).extractFunctionList(any());
        verify(skills, never()).reconcileFeatureScope(any());
        verify(skills, never()).designFunctionalTestcases(any());
        verify(exporter, never()).exportStructured(any());
        verify(exporter, never()).exportMarkdown(any());
    }

    /** [Req-ID]: REQ-FTG-015 */
    @Test
    void runsReviewExtractReconcileAndTestcaseInOrderThenExportsOnlyPersistedStructuredRows() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest request = mock(CreateGenerationTaskRequest.class);
        RequirementScope scope = RequirementScope.freeze("kb-1", "system-1", "version-1", "requirements_spec", "project-1",
                List.of(new RequirementDocumentCoordinate("requirement-1"),
                        new RequirementDocumentCoordinate("function-list-1")));
        when(request.agentId()).thenReturn("agent-1");
        when(request.requirementScope()).thenReturn(scope);
        when(sessions.openStructuredSession()).thenReturn("session-1");
        when(traversal.traverse("task-1", request, false)).thenReturn(new RequirementMaterialTraversalService.TraversalResult(List.of(
                material("requirement-1", "REQUIREMENT", "req-unit", "需求正文"),
                material("function-list-1", "FUNCTION_LIST", "fn-unit", "功能清单正文：提交"))));
        AtomicReference<StructuredGenerationAcceptanceStore.WorkRegistration> latest = new AtomicReference<>();
        AtomicInteger sequence = new AtomicInteger();
        when(store.register(any())).thenAnswer(invocation -> {
            latest.set(invocation.getArgument(0));
            return "work-" + sequence.incrementAndGet();
        });
        when(store.claimRegistered(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            var registration = latest.get();
            return java.util.Optional.of(new StructuredGenerationAcceptanceStore.WorkClaim(
                    invocation.getArgument(1), "attempt-" + sequence.get(), registration.taskId(), registration.identityKey(),
                    registration.skillName(), registration.operationName(), 1, registration.ordinalStart(), registration.ordinalEnd(),
                    registration.materialKey(), registration.allowedEvidenceKeys(), invocation.getArgument(2)));
        });
         when(skills.reviewRequirementMaterial(any())).thenReturn(success("requirement-material-quality-review",
                 new RequirementMaterialQualityReviewResult(List.of(new RequirementMaterialQualityReviewResult.RequirementFact(
                         "model-fact", "提交订单", List.of(), List.of(), List.of("订单"), List.of("金额大于零"),
                         List.of(), List.of(), List.of(), List.of(), List.of(), List.of("req-unit"))), List.of(
                         new RequirementMaterialQualityReviewResult.ReviewFinding("model-finding",
                                 RequirementMaterialQualityReviewResult.RootCauseKind.MISSING_EXCEPTION_HANDLING,
                                 "异常处理缺失", new RequirementMaterialQualityReviewResult.AffectedScope(
                                         List.of("req-unit"), "订单异常范围"),
                                 new RequirementMaterialQualityReviewResult.BadSourceExample("req-unit", "需求正文"),
                                 new RequirementMaterialQualityReviewResult.ProposedGoodExample(
                                         RequirementMaterialQualityReviewResult.ProposalStatus.PENDING_CONFIRMATION,
                                         "待需求方确认：补充订单异常处理"),
                                 "材料未说明异常处理", List.of("req-unit"), "影响异常用例设计", "本项目待确认",
                                 "设计中心补充异常模板", RequirementMaterialQualityReviewResult.HandlingLevel.IMPROVEMENT)))));
        stubNoFunctionCandidates(skills);
        StructuredGenerationAcceptanceStore.AcceptedFact acceptedFact =
                new StructuredGenerationAcceptanceStore.AcceptedFact("fact-task", "提交订单", List.of(),
                        List.of("提交"), List.of("订单"), List.of("金额大于零"), List.of("订单创建成功"),
                        List.of(), List.of(), List.of(), List.of(), List.of("req-unit"),
                        Map.of("req-unit", "提交订单，提交后订单创建成功"));
        StructuredGenerationAcceptanceStore.AcceptedFunctionItem acceptedItem =
                new StructuredGenerationAcceptanceStore.AcceptedFunctionItem(
                        "fli-task", "订单/提交", "提交订单", List.of("fn-unit"));
        when(store.acceptedInputs("task-1")).thenReturn(new StructuredGenerationAcceptanceStore.AcceptedInputs(
                List.of(acceptedFact), List.of(acceptedItem)));
        when(store.acceptedConfirmedFunctions("task-1")).thenReturn(List.of(
                new StructuredGenerationAcceptanceStore.AcceptedConfirmedFunction(
                        "reconciliation-task", List.of(acceptedItem), List.of(acceptedFact))));
        AtomicReference<StructuredGenerationAcceptanceStore.ReconciliationRunPlan> v2Plan = new AtomicReference<>();
        AtomicReference<StructuredGenerationAcceptanceStore.ReconciliationPageStage> v2Stage = new AtomicReference<>();
        AtomicInteger pagePoll = new AtomicInteger();
        doAnswer(invocation -> {
            v2Plan.set(invocation.getArgument(1));
            return null;
        }).when(store).initializeReconciliationRun(any(), any());
        when(store.pendingReconciliationPages(anyString(), anyString(), anyString())).thenAnswer(invocation ->
                pagePoll.getAndIncrement() == 0 ? List.of(v2Plan.get().initialOwnerWindows().get(0)) : List.of());
        doAnswer(invocation -> {
            v2Stage.set(invocation.getArgument(1));
            return null;
        }).when(store).stageReconciliationPage(any(), any());
        when(store.stagedReconciliationPages(anyString(), anyString(), anyString())).thenAnswer(invocation ->
                List.of(v2Stage.get()));
        when(skills.reconcileFeatureScopePage(any())).thenAnswer(invocation -> {
            FeatureScopeReconciliationPageInput input = invocation.getArgument(
                    0, FeatureScopeReconciliationPageInvocation.class).input();
            List<String> itemKeys = List.of(input.globalCatalog().functionListItems().get(0).itemKey());
            List<String> factKeys = List.of(input.globalCatalog().requirementFacts().get(0).factKey());
            List<FeatureScopeReconciliationPageInput.SourceRef> refs =
                    FeatureScopeReconciliationV2Canonicalizer.relationSourceRefs(itemKeys, factKeys);
            String relationKey = FeatureScopeReconciliationV2Canonicalizer.reconciliationKey(
                    input.run().runKey(), FeatureScopeReconciliationResult.Classification.EXACT_MATCH,
                    FeatureScopeReconciliationResult.ConfirmationStatus.CONFIRMED, refs);
            return success("feature-scope-reconciliation", new FeatureScopeReconciliationPageResult(
                    FeatureScopeReconciliationPageInput.OPERATION, FeatureScopeReconciliationPageInput.PROTOCOL_VERSION,
                    input.run().runKey(), input.ownerWindow().pageKey(), input.ownerWindow().ownerSourceRefs(),
                    List.of(new FeatureScopeReconciliationPageResult.Reconciliation(
                            relationKey, refs.get(0), itemKeys, factKeys,
                            FeatureScopeReconciliationResult.Classification.EXACT_MATCH,
                            List.of("fn-unit", "req-unit"), "保持范围",
                            FeatureScopeReconciliationResult.ConfirmationStatus.CONFIRMED))));
        });
        when(skills.designFunctionalTestcases(any())).thenAnswer(invocation -> {
            var input = invocation.getArgument(0, com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignInvocation.class).input();
            assertThat(input.functionName()).isEqualTo("订单/提交");
            assertThat(input.testPoint().requirementFactKeys()).containsExactly("fact-task");
             return success("functional-testcase-design", new FunctionalTestcaseDesignResult(input.functionKey(),
                     input.testPoint().testPointKey(), List.of(new FunctionalTestcaseDesignResult.Testcase(
                             "model-case", "提交订单", "提交订单", FunctionalTestcaseDesignResult.Priority.HIGH,
                             List.of(), FunctionalTestcaseDesignResult.Initialization.empty(),
                             List.of(new FunctionalTestcaseDesignResult.Input("提交",
                                     FunctionalTestcaseDesignResult.InputNature.VALID,
                                     FunctionalTestcaseDesignResult.InputSource.MANUAL,
                                     FunctionalTestcaseDesignResult.TestMethod.EQUIVALENCE_PARTITIONING,
                                     FunctionalTestcaseDesignResult.Authenticity.SIMULATED, "提交")),
                             List.of(new FunctionalTestcaseDesignResult.Step(1, "提交", "订单创建成功",
                                     FunctionalTestcaseDesignResult.GenericClauses.STEP_EVALUATION,
                                     FunctionalTestcaseDesignResult.GenericClauses.TERMINATION_OR_ERROR,
                                     FunctionalTestcaseDesignResult.GenericClauses.RESULT_COLLECTION)),
                             List.of("订单创建成功"), FunctionalTestcaseDesignResult.GenericClauses.EVALUATION,
                             FunctionalTestcaseDesignResult.GenericClauses.RESULT_EVALUATION, List.of(),
                             FunctionalTestcaseDesignResult.GenericClauses.RESULT_COLLECTION,
                             FunctionalTestcaseDesignResult.AuthoringInformation.empty(),
                             input.testPoint().requirementFactKeys(), input.testPoint().evidenceKeys(),
                             FunctionalTestcaseDesignResult.CaseStatus.FORMAL, List.of()))));
        });
        StructuredWorkbookExportRequest persistedRows = new StructuredWorkbookExportRequest("task-1", List.of(), List.of(
                new com.testcaseagent.export.StructuredTestCaseRow("case-task", "正常提交订单", "提交订单",
                        com.testcaseagent.export.StructuredTestCaseRow.Status.FORMAL, List.of(),
                        List.of(new com.testcaseagent.export.StructuredTestStep(1, "提交", "订单创建成功")),
                        List.of("提交订单"), List.of(), true)));
        when(repository.structuredWorkbookRequest("task-1")).thenReturn(persistedRows);
        WorkbookArtifact artifact = new WorkbookArtifact("artifact-1", "a".repeat(64), Path.of("artifact.xlsx"));
        when(exporter.exportStructured(persistedRows)).thenReturn(artifact);
        when(store.aggregateState("task-1")).thenReturn(new StructuredGenerationAcceptanceStore.AggregateState(
                1, 1, 1, 1, 0, 4, 0, true, 1, 0, 0));
        var coordinator = new DefaultStructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                store, exporter, new ObjectMapper(), heartbeat());

        coordinator.execute("task-1", request);

        InOrder order = inOrder(skills, exporter);
        order.verify(skills).reviewRequirementMaterial(any());
        order.verify(skills).extractFunctionCandidates(any());
        order.verify(skills).reconcileFeatureScopePage(any());
        order.verify(skills, org.mockito.Mockito.atLeastOnce()).designFunctionalTestcases(any());
         order.verify(exporter).exportStructured(persistedRows);
         var reviewInvocation = org.mockito.ArgumentCaptor.forClass(
                 com.testcaseagent.knowledgeagent.RequirementMaterialQualityReviewInvocation.class);
         verify(skills).reviewRequirementMaterial(reviewInvocation.capture());
         assertThat(reviewInvocation.getValue().requirementScope().documents())
                 .extracting(RequirementDocumentCoordinate::documentId)
                 .containsExactly("requirement-1");
         assertThat(reviewInvocation.getValue().input().materialKey()).isEqualTo("requirement-1");
         var extractionInvocation = org.mockito.ArgumentCaptor.forClass(
                  com.testcaseagent.knowledgeagent.FunctionCandidateExtractionInvocation.class);
         verify(skills).extractFunctionCandidates(extractionInvocation.capture());
         assertThat(extractionInvocation.getValue().requirementScope().documents())
                 .extracting(RequirementDocumentCoordinate::documentId)
                 .containsExactly("function-list-1");
         assertThat(extractionInvocation.getValue().input().materialKey()).isEqualTo("function-list-1");
         var reconciliationInvocation = org.mockito.ArgumentCaptor.forClass(FeatureScopeReconciliationPageInvocation.class);
         verify(skills).reconcileFeatureScopePage(reconciliationInvocation.capture());
         assertThat(reconciliationInvocation.getValue().requirementScope()).isEqualTo(scope);
         verify(skills, never()).reconcileFeatureScope(any());
         var testcaseInvocations = org.mockito.ArgumentCaptor.forClass(
                 com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignInvocation.class);
         verify(skills, org.mockito.Mockito.atLeastOnce()).designFunctionalTestcases(testcaseInvocations.capture());
         assertThat(testcaseInvocations.getAllValues())
                 .allSatisfy(invocation -> assertThat(invocation.requirementScope()).isEqualTo(scope));
         var accepted = org.mockito.ArgumentCaptor.forClass(
                 com.testcaseagent.validation.FunctionalTestcaseResultValidator.Result.class);
         verify(store, org.mockito.Mockito.atLeastOnce()).acceptTestcases(any(), any(), any(), accepted.capture());
         assertThat(accepted.getAllValues()).allSatisfy(result ->
                 assertThat(result.testcases()).singleElement().satisfies(row -> {
                     assertThat(row.name()).isEqualTo("提交订单");
                     assertThat(row.priority().name()).isEqualTo("HIGH");
                     assertThat(row.inputs()).singleElement().satisfies(input ->
                             assertThat(input.content()).isEqualTo("提交"));
                     assertThat(row.steps()).singleElement().satisfies(step ->
                             assertThat(step.evaluationCriteria()).isEqualTo(
                                     FunctionalTestcaseDesignResult.GenericClauses.STEP_EVALUATION));
                 }));
         var acceptedReview = org.mockito.ArgumentCaptor.forClass(
                 com.testcaseagent.validation.RequirementMaterialReviewValidator.Result.class);
         verify(store).acceptReview(any(), any(), any(), acceptedReview.capture());
         assertThat(acceptedReview.getValue().reviewFindings()).singleElement().satisfies(finding -> {
             assertThat(finding.rootCauseKind().name()).isEqualTo("MISSING_EXCEPTION_HANDLING");
             assertThat(finding.affectedScope().summary()).isEqualTo("订单异常范围");
             assertThat(finding.badSourceExample().quote()).isEqualTo("需求正文");
             assertThat(finding.proposedGoodExample().text()).contains("待需求方确认");
         });
        verify(exporter, never()).exportMarkdown(any());
        verify(repository).completeStructuredTask("task-1", artifact,
                com.testcaseagent.structuredgeneration.StructuredProcessingStatus.COMPLETED,
                com.testcaseagent.structuredgeneration.StructuredCoverageStatus.COMPLETE, true);
    }

    /** [Req-ID]: REQ-AFCE-006 */
    @Test
    void candidateDeliveryWithoutAnAcceptedFormalFunctionFailsWithoutPublishingAnArtifact() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest request = mock(CreateGenerationTaskRequest.class);
        when(request.agentId()).thenReturn("agent-1");
        when(request.requirementScope()).thenReturn(scope("requirement-1", "function-list-1"));
        when(sessions.openStructuredSession()).thenReturn("session-1");
        when(traversal.traverse("task-empty", request, false)).thenReturn(
                new RequirementMaterialTraversalService.TraversalResult(List.of(
                        material("function-list-1", "FUNCTION_LIST", "fn-unit", "无功能条目"))));
        AtomicReference<StructuredGenerationAcceptanceStore.WorkRegistration> registration = new AtomicReference<>();
        when(store.register(any())).thenAnswer(invocation -> {
            registration.set(invocation.getArgument(0));
            return "work-empty";
        });
        when(store.claimRegistered("task-empty", "work-empty", "structured-all-worker")).thenAnswer(invocation -> java.util.Optional.of(
                new StructuredGenerationAcceptanceStore.WorkClaim("work-empty", "attempt-empty", "task-empty",
                        registration.get().identityKey(), registration.get().skillName(), registration.get().operationName(), 1,
                        registration.get().ordinalStart(), registration.get().ordinalEnd(), registration.get().materialKey(),
                        registration.get().allowedEvidenceKeys(), "structured-all-worker")));
        stubNoFunctionCandidates(skills);
        when(store.acceptedInputs("task-empty")).thenReturn(
                new StructuredGenerationAcceptanceStore.AcceptedInputs(List.of(), List.of()));
        when(store.aggregateState("task-empty")).thenReturn(
                new StructuredGenerationAcceptanceStore.AggregateState(0, 0, 0, 0, 0, 1, 0, true,
                        0, 1, 0));

        new DefaultStructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                store, exporter, new ObjectMapper(), heartbeat()).execute("task-empty", request);

        verify(repository).failStructuredTask("task-empty",
                com.testcaseagent.structuredgeneration.StructuredCoverageStatus.UNABLE_TO_GENERATE);
        verify(repository, never()).completeStructuredTask(anyString(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyBoolean());
        verify(exporter, never()).exportStructured(any());
        verify(skills, never()).reconcileFeatureScope(any());
        verify(skills, never()).designFunctionalTestcases(any());
        verify(exporter, never()).exportMarkdown(any());
    }

    /** [Req-ID]: REQ-AFCE-006 */
    @Test
    void resumedCandidateDeliveryPublishesPartialWhenOneSafeWindowRemainsIncomplete() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest request = mock(CreateGenerationTaskRequest.class);
        var item = new StructuredGenerationAcceptanceStore.AcceptedFunctionItem(
                "item-accepted", "订单/提交", "提交订单", List.of("fn-unit"));
        when(request.requirementScope()).thenReturn(scope("function-list-1"));
        when(request.agentId()).thenReturn("agent-1");
        when(store.hasCompletedMaterialStages("task-partial")).thenReturn(true);
        when(store.hasFunctionCandidateAudit("task-partial")).thenReturn(true);
        when(store.persistedValidationRegistry("task-partial")).thenReturn(
                com.testcaseagent.validation.StructuredValidationRegistry.forTask("task-partial"));
        when(sessions.openStructuredSession()).thenReturn("session-partial");
        when(store.acceptedInputs("task-partial")).thenReturn(
                new StructuredGenerationAcceptanceStore.AcceptedInputs(List.of(), List.of(item)));
        when(store.hasCompletedReconciliationWork("task-partial")).thenReturn(true);
        when(store.acceptedConfirmedFunctions("task-partial")).thenReturn(List.of());
        when(store.aggregateState("task-partial")).thenReturn(
                new StructuredGenerationAcceptanceStore.AggregateState(0, 0, 1, 1, 0, 3, 1, true,
                        1, 1, 1));
        StructuredWorkbookExportRequest rows = new StructuredWorkbookExportRequest("task-partial", List.of(), List.of());
        WorkbookArtifact artifact = new WorkbookArtifact(
                "artifact-partial", "9".repeat(64), Path.of("partial.xlsx"));
        when(repository.structuredWorkbookRequest("task-partial")).thenReturn(rows);
        when(exporter.exportStructured(rows)).thenReturn(artifact);

        new DefaultStructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                store, exporter, new ObjectMapper(), heartbeat()).execute("task-partial", request);

        verify(repository).completeStructuredTask("task-partial", artifact,
                com.testcaseagent.structuredgeneration.StructuredProcessingStatus.COMPLETED,
                com.testcaseagent.structuredgeneration.StructuredCoverageStatus.PARTIAL, true);
        verify(traversal, never()).traverse(anyString(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(skills, never()).extractFunctionCandidates(any());
    }

    /** [Req-ID]: REQ-SEW-003 */
    @Test
    void keepsTheLeaseHealthyUntilPersistedAcceptanceThenStopsTheHeartbeat() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        StructuredWorkLeaseHeartbeat heartbeat = mock(StructuredWorkLeaseHeartbeat.class);
        StructuredWorkLeaseHeartbeat.ActiveLease activeLease = mock(StructuredWorkLeaseHeartbeat.ActiveLease.class);
        CreateGenerationTaskRequest request = mock(CreateGenerationTaskRequest.class);
        when(heartbeat.start(any(), any())).thenReturn(activeLease);
        when(request.agentId()).thenReturn("agent-1");
        when(request.requirementScope()).thenReturn(scope("function-list-1"));
        when(sessions.openStructuredSession()).thenReturn("session-heartbeat");
        when(traversal.traverse("task-heartbeat", request, false)).thenReturn(
                new RequirementMaterialTraversalService.TraversalResult(List.of(
                        material("function-list-1", "FUNCTION_LIST", "fn-unit", "无功能条目"))));
        AtomicReference<StructuredGenerationAcceptanceStore.WorkRegistration> registration = new AtomicReference<>();
        when(store.register(any())).thenAnswer(invocation -> {
            registration.set(invocation.getArgument(0));
            return "work-heartbeat";
        });
        when(store.claimRegistered("task-heartbeat", "work-heartbeat", "structured-all-worker"))
                .thenAnswer(invocation -> java.util.Optional.of(new StructuredGenerationAcceptanceStore.WorkClaim(
                        "work-heartbeat", "attempt-heartbeat", "task-heartbeat", registration.get().identityKey(),
                        registration.get().skillName(), registration.get().operationName(), 1,
                        registration.get().ordinalStart(), registration.get().ordinalEnd(), registration.get().materialKey(),
                        registration.get().allowedEvidenceKeys(), "structured-all-worker")));
        stubNoFunctionCandidates(skills);
        when(store.acceptedInputs("task-heartbeat")).thenReturn(
                new StructuredGenerationAcceptanceStore.AcceptedInputs(List.of(), List.of()));
        StructuredWorkbookExportRequest rows = new StructuredWorkbookExportRequest("task-heartbeat", List.of(), List.of());
        WorkbookArtifact artifact = new WorkbookArtifact("artifact-heartbeat", "d".repeat(64), Path.of("heartbeat.xlsx"));
        when(repository.structuredWorkbookRequest("task-heartbeat")).thenReturn(rows);
        when(exporter.exportStructured(rows)).thenReturn(artifact);
        when(store.aggregateState("task-heartbeat")).thenReturn(
                new StructuredGenerationAcceptanceStore.AggregateState(0, 0, 0, 0, 0, 1, 0, true));

        new DefaultStructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                store, exporter, new ObjectMapper(), heartbeat).execute("task-heartbeat", request);

        InOrder order = inOrder(skills, activeLease, store);
        order.verify(skills).extractFunctionCandidates(any());
        order.verify(activeLease).requireActive();
        order.verify(store).acceptFunctionCandidates(any(), any());
        order.verify(activeLease).close();
        verify(store, never()).fail(any(), org.mockito.ArgumentMatchers.eq("business_validation_failed"));
    }

    /** [Req-ID]: REQ-SEW-003 */
    @Test
    void cancellationObservedAfterTheResponseStopsHeartbeatAndPreventsAcceptance() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        StructuredWorkLeaseHeartbeat heartbeat = mock(StructuredWorkLeaseHeartbeat.class);
        StructuredWorkLeaseHeartbeat.ActiveLease activeLease = mock(StructuredWorkLeaseHeartbeat.ActiveLease.class);
        CreateGenerationTaskRequest request = mock(CreateGenerationTaskRequest.class);
        when(request.agentId()).thenReturn("agent-1");
        when(request.requirementScope()).thenReturn(scope("function-list-1"));
        when(sessions.openStructuredSession()).thenReturn("session-cancelled-heartbeat");
        when(traversal.traverse("task-cancelled-heartbeat", request, false)).thenReturn(
                new RequirementMaterialTraversalService.TraversalResult(List.of(
                        material("function-list-1", "FUNCTION_LIST", "fn-unit", "无功能条目"))));
        AtomicReference<StructuredGenerationAcceptanceStore.WorkRegistration> registration = new AtomicReference<>();
        when(store.register(any())).thenAnswer(invocation -> {
            registration.set(invocation.getArgument(0));
            return "work-cancelled-heartbeat";
        });
        when(store.claimRegistered("task-cancelled-heartbeat", "work-cancelled-heartbeat", "structured-all-worker"))
                .thenAnswer(invocation -> java.util.Optional.of(new StructuredGenerationAcceptanceStore.WorkClaim(
                        "work-cancelled-heartbeat", "attempt-cancelled-heartbeat", "task-cancelled-heartbeat",
                        registration.get().identityKey(), registration.get().skillName(), registration.get().operationName(), 1,
                        registration.get().ordinalStart(), registration.get().ordinalEnd(), registration.get().materialKey(),
                        registration.get().allowedEvidenceKeys(), "structured-all-worker")));
        when(heartbeat.start(any(), any())).thenReturn(activeLease);
        stubNoFunctionCandidates(skills);
        org.mockito.Mockito.doThrow(new CancellationException("Structured task was cancelled"))
                .when(activeLease).requireActive();

        new DefaultStructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                store, exporter, new ObjectMapper(), heartbeat).execute("task-cancelled-heartbeat", request);

        verify(activeLease).close();
        verify(store, never()).acceptFunctionCandidates(any(), any());
        verify(store, never()).fail(any(), org.mockito.ArgumentMatchers.eq("business_validation_failed"));
        verify(repository).cancelStructuredTask("task-cancelled-heartbeat",
                com.testcaseagent.structuredgeneration.StructuredCoverageStatus.PENDING);
        verify(exporter, never()).exportStructured(any());
    }

    @ParameterizedTest
    @EnumSource(value = StructuredSkillErrorType.class, names = {"MODEL_UNAVAILABLE", "MODEL_EXECUTION_FAILED"})
    void retriesTheSameWorkOnceForATransientModelFailureThenContinues(StructuredSkillErrorType transientFailure) {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest request = mock(CreateGenerationTaskRequest.class);
        when(request.agentId()).thenReturn("agent-1");
        when(request.requirementScope()).thenReturn(scope("requirement-1", "function-list-1"));
        when(sessions.openStructuredSession()).thenReturn("session-1");
        when(traversal.traverse("task-retry", request, false)).thenReturn(
                new RequirementMaterialTraversalService.TraversalResult(List.of(
                        material("function-list-1", "FUNCTION_LIST", "fn-unit", "无功能条目"))));
        AtomicReference<StructuredGenerationAcceptanceStore.WorkRegistration> registration = new AtomicReference<>();
        when(store.register(any())).thenAnswer(invocation -> {
            registration.set(invocation.getArgument(0));
            return "work-retry";
        });
        AtomicInteger attempts = new AtomicInteger();
        when(store.claimRegistered("task-retry", "work-retry", "structured-all-worker")).thenAnswer(invocation -> {
            int attempt = attempts.incrementAndGet();
            return java.util.Optional.of(new StructuredGenerationAcceptanceStore.WorkClaim(
                    "work-retry", "attempt-" + attempt, "task-retry", registration.get().identityKey(),
                    registration.get().skillName(), registration.get().operationName(), attempt,
                    registration.get().ordinalStart(), registration.get().ordinalEnd(), registration.get().materialKey(),
                    registration.get().allowedEvidenceKeys(), "structured-all-worker"));
        });
        when(skills.extractFunctionCandidates(any()))
                .thenThrow(new StructuredSkillExecutionException(transientFailure, false))
                .thenAnswer(invocation -> success("feature-scope-reconciliation", noFunctionCandidateResult(
                        invocation.getArgument(0, FunctionCandidateExtractionInvocation.class).input())));
        when(store.acceptedInputs("task-retry")).thenReturn(
                new StructuredGenerationAcceptanceStore.AcceptedInputs(List.of(), List.of()));
        when(store.aggregateState("task-retry")).thenReturn(
                new StructuredGenerationAcceptanceStore.AggregateState(0, 0, 0, 0, 0, 1, 0, true,
                        0, 0, 0));

        new DefaultStructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                store, exporter, new ObjectMapper(), heartbeat()).execute("task-retry", request);

        verify(skills, org.mockito.Mockito.times(2)).extractFunctionCandidates(any());
        verify(store, org.mockito.Mockito.times(2)).claimRegistered(
                "task-retry", "work-retry", "structured-all-worker");
        verify(repository).failStructuredTask("task-retry",
                com.testcaseagent.structuredgeneration.StructuredCoverageStatus.UNABLE_TO_GENERATE);
        verify(exporter, never()).exportStructured(any());
    }

    @Test
    void doesNotRetryStructuredOutputInvalidOrFallBackToMarkdown() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        StructuredWorkLeaseHeartbeat heartbeat = mock(StructuredWorkLeaseHeartbeat.class);
        StructuredWorkLeaseHeartbeat.ActiveLease activeLease = mock(StructuredWorkLeaseHeartbeat.ActiveLease.class);
        CreateGenerationTaskRequest request = mock(CreateGenerationTaskRequest.class);
        when(request.agentId()).thenReturn("agent-1");
        when(request.requirementScope()).thenReturn(scope("requirement-1", "function-list-1"));
        when(sessions.openStructuredSession()).thenReturn("session-1");
        when(traversal.traverse("task-invalid", request, false)).thenReturn(
                new RequirementMaterialTraversalService.TraversalResult(List.of(
                        material("function-list-1", "FUNCTION_LIST", "fn-unit", "正文"))));
        AtomicReference<StructuredGenerationAcceptanceStore.WorkRegistration> registration = new AtomicReference<>();
        when(store.register(any())).thenAnswer(invocation -> {
            registration.set(invocation.getArgument(0));
            return "work-invalid";
        });
        when(store.claimRegistered("task-invalid", "work-invalid", "structured-all-worker")).thenAnswer(invocation ->
                java.util.Optional.of(new StructuredGenerationAcceptanceStore.WorkClaim(
                        "work-invalid", "attempt-1", "task-invalid", registration.get().identityKey(),
                        registration.get().skillName(), registration.get().operationName(), 1,
                        registration.get().ordinalStart(), registration.get().ordinalEnd(), registration.get().materialKey(),
                        registration.get().allowedEvidenceKeys(), "structured-all-worker")));
        when(heartbeat.start(any(), any())).thenReturn(activeLease);
        when(skills.extractFunctionCandidates(any())).thenThrow(
                new StructuredSkillExecutionException(StructuredSkillErrorType.STRUCTURED_OUTPUT_INVALID, true));
        when(store.acceptedInputs("task-invalid")).thenReturn(
                new StructuredGenerationAcceptanceStore.AcceptedInputs(List.of(), List.of()));
        when(store.aggregateState("task-invalid")).thenReturn(
                new StructuredGenerationAcceptanceStore.AggregateState(0, 0, 0, 0, 0, 0, 1, true,
                        0, 1, 1));

        var coordinator = new DefaultStructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                store, exporter, new ObjectMapper(), heartbeat);

        coordinator.execute("task-invalid", request);
        verify(skills).extractFunctionCandidates(any());
        verify(store).claimRegistered("task-invalid", "work-invalid", "structured-all-worker");
        verify(store, never()).splitFunctionListExtractionWork(any(), any(), any());
        verify(activeLease).close();
        verify(repository).failStructuredTask("task-invalid",
                com.testcaseagent.structuredgeneration.StructuredCoverageStatus.UNABLE_TO_GENERATE);
        verify(exporter, never()).exportStructured(any());
        verify(exporter, never()).exportMarkdown(any());
    }

    @Test
    void doesNotRetryAFormalGroundingFailureOrPersistAnArtifact() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        StructuredWorkLeaseHeartbeat heartbeat = mock(StructuredWorkLeaseHeartbeat.class);
        StructuredWorkLeaseHeartbeat.ActiveLease activeLease = mock(StructuredWorkLeaseHeartbeat.ActiveLease.class);
        CreateGenerationTaskRequest request = mock(CreateGenerationTaskRequest.class);
        when(request.agentId()).thenReturn("agent-1");
        when(request.requirementScope()).thenReturn(scope("requirement-1", "function-list-1"));
        when(sessions.openStructuredSession()).thenReturn("session-grounding");
        when(traversal.traverse("task-grounding", request, false)).thenReturn(
                new RequirementMaterialTraversalService.TraversalResult(List.of(
                        material("requirement-1", "REQUIREMENT", "req-unit", "账号登录"),
                        material("function-list-1", "FUNCTION_LIST", "fn-unit", "账号登录"))));
        AtomicInteger sequence = new AtomicInteger();
        Map<String, StructuredGenerationAcceptanceStore.WorkRegistration> registrations = new LinkedHashMap<>();
        when(store.register(any())).thenAnswer(invocation -> {
            StructuredGenerationAcceptanceStore.WorkRegistration registration = invocation.getArgument(0);
            String workId = "grounding-work-" + sequence.incrementAndGet();
            registrations.put(workId, registration);
            return workId;
        });
        when(store.isCompleted(anyString())).thenAnswer(invocation -> !"FUNCTIONAL_TESTCASE_DESIGN".equals(
                registrations.get(invocation.getArgument(0, String.class)).operationName()));
        when(store.claimRegistered(org.mockito.ArgumentMatchers.eq("task-grounding"), anyString(),
                org.mockito.ArgumentMatchers.eq("structured-all-worker"))).thenAnswer(invocation -> {
            String workId = invocation.getArgument(1);
            StructuredGenerationAcceptanceStore.WorkRegistration registration = registrations.get(workId);
            return java.util.Optional.of(new StructuredGenerationAcceptanceStore.WorkClaim(
                    workId, "grounding-attempt", "task-grounding", registration.identityKey(),
                    registration.skillName(), registration.operationName(), 1, registration.ordinalStart(),
                    registration.ordinalEnd(), registration.materialKey(), registration.allowedEvidenceKeys(),
                    "structured-all-worker"));
        });
        StructuredGenerationAcceptanceStore.AcceptedFact fact = new StructuredGenerationAcceptanceStore.AcceptedFact(
                "fact-grounding", "账号登录", List.of("已注册用户"), List.of("提交账号和正确密码"),
                List.of("账号", "正确密码"), List.of(), List.of("进入首页"), List.of(), List.of(),
                List.of(), List.of(), List.of("req-unit"), Map.of("req-unit", "已注册用户提交账号和正确密码后进入首页"));
        when(heartbeat.start(any(), any())).thenReturn(activeLease);
        StructuredGenerationAcceptanceStore.AcceptedFunctionItem item =
                new StructuredGenerationAcceptanceStore.AcceptedFunctionItem(
                        "item-grounding", "用户中心/账号登录", "账号登录", List.of("fn-unit"));
        when(store.acceptedInputs("task-grounding")).thenReturn(
                new StructuredGenerationAcceptanceStore.AcceptedInputs(List.of(fact), List.of(item)));
        when(store.acceptedConfirmedFunctions("task-grounding")).thenReturn(List.of(
                new StructuredGenerationAcceptanceStore.AcceptedConfirmedFunction(
                        "reconciliation-grounding", List.of(item), List.of(fact))));
        when(skills.designFunctionalTestcases(any())).thenAnswer(invocation -> {
            var input = invocation.getArgument(0,
                    com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignInvocation.class).input();
            assertThat(input.formalSupports()).singleElement().satisfies(support -> {
                assertThat(support.inputs()).containsExactly("账号", "正确密码");
                assertThat(support.evidenceKeys()).containsExactly("req-unit");
                assertThat(support.evidenceTexts()).containsExactly("已注册用户提交账号和正确密码后进入首页");
                assertThat(support.inputs()).noneMatch(value -> value.contains("手机号"));
                assertThat(support.evidenceTexts()).noneMatch(value -> value.contains("Token"));
            });
            return success("functional-testcase-design", new FunctionalTestcaseDesignResult(
                    input.functionKey(), input.testPoint().testPointKey(), List.of(
                            new FunctionalTestcaseDesignResult.Testcase("case-grounding", "手机号登录", List.of("已绑定手机号"),
                                    List.of(new FunctionalTestcaseDesignResult.Step(1, "提交手机号和正确密码", "进入首页")),
                                    input.testPoint().requirementFactKeys(), input.testPoint().evidenceKeys(),
                                    FunctionalTestcaseDesignResult.CaseStatus.FORMAL, List.of()))));
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            com.testcaseagent.validation.FunctionalTestcaseResultValidator validator = invocation.getArgument(1);
            validator.validate(invocation.getArgument(2), invocation.getArgument(3));
            return null;
        }).when(store).acceptTestcases(any(), any(), any(), any());

        var coordinator = new DefaultStructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                store, exporter, new ObjectMapper(), heartbeat);

        assertThatThrownBy(() -> coordinator.execute("task-grounding", request))
                .isInstanceOf(IllegalArgumentException.class);
        verify(skills).designFunctionalTestcases(any());
        verify(store).claimRegistered(org.mockito.ArgumentMatchers.eq("task-grounding"), anyString(),
                org.mockito.ArgumentMatchers.eq("structured-all-worker"));
        verify(store).fail(any(), org.mockito.ArgumentMatchers.eq("business_validation_failed"));
        verify(activeLease, org.mockito.Mockito.atLeastOnce()).close();
        verify(repository).failStructuredTask(eq("task-grounding"),
                eq(com.testcaseagent.structuredgeneration.StructuredCoverageStatus.PENDING),
                any(com.testcaseagent.validation.StructuredValidationFailure.class));
        verify(exporter, never()).exportStructured(any());
        verify(exporter, never()).exportMarkdown(any());
    }

    /** [Req-ID]: REQ-SEW-003 */
    @Test
    void leaseLossStopsAcceptanceAndDoesNotRewriteTheAttemptAsBusinessValidationFailure() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        StructuredWorkLeaseHeartbeat heartbeat = mock(StructuredWorkLeaseHeartbeat.class);
        StructuredWorkLeaseHeartbeat.ActiveLease activeLease = mock(StructuredWorkLeaseHeartbeat.ActiveLease.class);
        CreateGenerationTaskRequest request = mock(CreateGenerationTaskRequest.class);
        when(request.agentId()).thenReturn("agent-1");
        when(request.requirementScope()).thenReturn(scope("function-list-1"));
        when(sessions.openStructuredSession()).thenReturn("session-lost-lease");
        when(traversal.traverse("task-lost-lease", request, false)).thenReturn(
                new RequirementMaterialTraversalService.TraversalResult(List.of(
                        material("function-list-1", "FUNCTION_LIST", "fn-unit", "无功能条目"))));
        AtomicReference<StructuredGenerationAcceptanceStore.WorkRegistration> registration = new AtomicReference<>();
        when(store.register(any())).thenAnswer(invocation -> {
            registration.set(invocation.getArgument(0));
            return "work-lost-lease";
        });
        when(store.claimRegistered("task-lost-lease", "work-lost-lease", "structured-all-worker"))
                .thenAnswer(invocation -> java.util.Optional.of(new StructuredGenerationAcceptanceStore.WorkClaim(
                        "work-lost-lease", "attempt-lost-lease", "task-lost-lease", registration.get().identityKey(),
                        registration.get().skillName(), registration.get().operationName(), 1,
                        registration.get().ordinalStart(), registration.get().ordinalEnd(), registration.get().materialKey(),
                        registration.get().allowedEvidenceKeys(), "structured-all-worker")));
        when(heartbeat.start(any(), any())).thenReturn(activeLease);
        stubNoFunctionCandidates(skills);
        org.mockito.Mockito.doThrow(new StructuredWorkLeaseLostException()).when(activeLease).requireActive();

        assertThatThrownBy(() -> new DefaultStructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                store, exporter, new ObjectMapper(), heartbeat).execute("task-lost-lease", request))
                .isInstanceOf(StructuredWorkLeaseLostException.class);

        verify(activeLease).close();
        verify(store, never()).acceptFunctionCandidates(any(), any());
        verify(store, never()).fail(any(), anyString());
        verify(repository).failStructuredTask(eq("task-lost-lease"),
                eq(com.testcaseagent.structuredgeneration.StructuredCoverageStatus.PENDING),
                any(com.testcaseagent.validation.StructuredValidationFailure.class));
        verify(exporter, never()).exportStructured(any());
    }

    @Test
    void recordsTraversalCancellationAsCancelledWithoutCallingKeeOrPublishingAnArtifact() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest request = mock(CreateGenerationTaskRequest.class);
        when(traversal.traverse("task-cancel-traversal", request, false)).thenThrow(new CancellationException("cancelled"));

        new DefaultStructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                store, exporter, new ObjectMapper(), heartbeat()).execute("task-cancel-traversal", request);

        verify(repository).cancelStructuredTask("task-cancel-traversal",
                com.testcaseagent.structuredgeneration.StructuredCoverageStatus.PENDING);
        verify(repository, never()).failStructuredTask(anyString(), any());
        verifyNoStructuredCalls(skills, exporter);
    }

    @Test
    void stopsBetweenTwoSlicesWhenCancellationIsRequested() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest request = mock(CreateGenerationTaskRequest.class);
        when(request.agentId()).thenReturn("agent-1");
        when(request.requirementScope()).thenReturn(scope("requirement-1", "function-list-1"));
        when(sessions.openStructuredSession()).thenReturn("session-1");
        when(traversal.traverse("task-cancel-between", request, false)).thenReturn(
                new RequirementMaterialTraversalService.TraversalResult(List.of(functionMaterialWithUnits(33))));
        AtomicBoolean cancellationRequested = new AtomicBoolean();
        when(repository.isCancellationRequested("task-cancel-between"))
                .thenAnswer(ignored -> cancellationRequested.get());
        AtomicReference<StructuredGenerationAcceptanceStore.WorkRegistration> registration = new AtomicReference<>();
        AtomicInteger workSequence = new AtomicInteger();
        when(store.register(any())).thenAnswer(invocation -> {
            registration.set(invocation.getArgument(0));
            return "work-" + workSequence.incrementAndGet();
        });
        when(store.claimRegistered(anyString(), anyString(), anyString())).thenAnswer(invocation ->
                java.util.Optional.of(new StructuredGenerationAcceptanceStore.WorkClaim(
                        invocation.getArgument(1), "attempt-1", "task-cancel-between", registration.get().identityKey(),
                        registration.get().skillName(), registration.get().operationName(), 1,
                        registration.get().ordinalStart(), registration.get().ordinalEnd(), registration.get().materialKey(),
                        registration.get().allowedEvidenceKeys(), "structured-all-worker")));
        stubNoFunctionCandidates(skills);
        doAnswer(ignored -> {
            // Make cancellation visible only after the first slice is durably accepted. This ties
            // the test to the business boundary instead of the number of internal checkpoints.
            cancellationRequested.set(true);
            return null;
        }).when(store).acceptFunctionCandidates(any(), any());

        new DefaultStructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                store, exporter, new ObjectMapper(), heartbeat()).execute("task-cancel-between", request);

        verify(skills).extractFunctionCandidates(any());
        verify(repository).cancelStructuredTask("task-cancel-between",
                com.testcaseagent.structuredgeneration.StructuredCoverageStatus.PENDING);
        verify(repository, never()).failStructuredTask(anyString(), any());
        verify(exporter, never()).exportStructured(any());
    }

    @Test
    void freshCoordinatorResumesFromPersistedConfirmedMappingsWithoutRepeatingCompletedKeeWork() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest request = mock(CreateGenerationTaskRequest.class);
        when(request.agentId()).thenReturn("agent-1");
        when(request.requirementScope()).thenReturn(scope("requirement-1", "function-list-1"));
        when(sessions.openStructuredSession()).thenReturn("session-restart");
        var persistedRegistry = com.testcaseagent.validation.StructuredValidationRegistry.forTask("task-restart");
        persistedRegistry.registerEvidence(new com.testcaseagent.validation.StructuredEvidence(
                "req-unit", "task-restart", "requirement-1", false, false, true));
        persistedRegistry.registerEvidence(new com.testcaseagent.validation.StructuredEvidence(
                "fn-unit", "task-restart", "function-list-1", false, false, true));
        when(store.hasCompletedMaterialStages("task-restart")).thenReturn(true);
        when(store.persistedValidationRegistry("task-restart")).thenReturn(persistedRegistry);
        AtomicInteger identities = new AtomicInteger();
        Map<String, StructuredGenerationAcceptanceStore.WorkRegistration> registrations = new LinkedHashMap<>();
        when(store.register(any())).thenAnswer(invocation -> {
            StructuredGenerationAcceptanceStore.WorkRegistration value = invocation.getArgument(0);
            String workId = "restart-work-" + identities.incrementAndGet();
            registrations.put(workId, value);
            return workId;
        });
        when(store.isCompleted(anyString())).thenAnswer(invocation -> !"FUNCTIONAL_TESTCASE_DESIGN".equals(
                registrations.get(invocation.getArgument(0, String.class)).operationName()));
        when(store.claimRegistered(org.mockito.ArgumentMatchers.eq("task-restart"), anyString(),
                org.mockito.ArgumentMatchers.eq("structured-all-worker"))).thenAnswer(invocation -> {
            String workId = invocation.getArgument(1);
            StructuredGenerationAcceptanceStore.WorkRegistration value = registrations.get(workId);
            return java.util.Optional.of(new StructuredGenerationAcceptanceStore.WorkClaim(
                    workId, "restart-attempt-" + identities.get(), "task-restart", value.identityKey(),
                    value.skillName(), value.operationName(), 1, value.ordinalStart(), value.ordinalEnd(),
                    value.materialKey(), value.allowedEvidenceKeys(), "structured-all-worker"));
        });
        StructuredGenerationAcceptanceStore.AcceptedFact fact =
                new StructuredGenerationAcceptanceStore.AcceptedFact("fact-persisted", "不用于猜测功能名",
                        List.of(), List.of("执行"), List.of("订单"), List.of("金额大于零"), List.of("成功"),
                        List.of(), List.of(), List.of(), List.of(), List.of("req-unit"),
                        Map.of("req-unit", "执行后成功"));
        StructuredGenerationAcceptanceStore.AcceptedFunctionItem item =
                new StructuredGenerationAcceptanceStore.AcceptedFunctionItem(
                        "item-persisted", "订单/持久化确认功能", "确认功能", List.of("fn-unit"));
        when(store.acceptedInputs("task-restart")).thenReturn(
                new StructuredGenerationAcceptanceStore.AcceptedInputs(List.of(fact), List.of(item)));
        when(store.hasCompletedReconciliationWork("task-restart")).thenReturn(true);
        when(store.acceptedConfirmedFunctions("task-restart")).thenReturn(List.of(
                new StructuredGenerationAcceptanceStore.AcceptedConfirmedFunction(
                        "reconciliation-persisted", List.of(item), List.of(fact))));
        when(skills.designFunctionalTestcases(any())).thenAnswer(invocation -> {
            var input = invocation.getArgument(0,
                    com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignInvocation.class).input();
            assertThat(input.functionName()).isEqualTo("订单/持久化确认功能");
            assertThat(input.testPoint().requirementFactKeys()).containsExactly("fact-persisted");
            var wireInput = new ObjectMapper().valueToTree(input);
            assertThat(wireInput.path("formal_supports")).hasSize(1);
            assertThat(wireInput.path("formal_supports").path(0).path("fact_key").asText())
                    .isEqualTo("fact-persisted");
            assertThat(wireInput.path("formal_supports").path(0).path("trigger_conditions"))
                    .extracting(node -> node.asText()).containsExactly("执行");
            assertThat(wireInput.path("formal_supports").path(0).path("outputs"))
                    .extracting(node -> node.asText()).containsExactly("成功");
            assertThat(wireInput.path("formal_supports").path(0).path("evidence_texts"))
                    .extracting(node -> node.asText()).containsExactly("执行后成功");
            assertThat(wireInput.path("formal_supports").path(0).path("evidence_keys"))
                    .extracting(node -> node.asText()).containsExactly("req-unit");
            return success("functional-testcase-design", new FunctionalTestcaseDesignResult(
                    input.functionKey(), input.testPoint().testPointKey(), List.of(
                            new FunctionalTestcaseDesignResult.Testcase("restart-case", "不用于猜测功能名", List.of(),
                                    List.of(new FunctionalTestcaseDesignResult.Step(1, "执行", "成功")),
                                    input.testPoint().requirementFactKeys(), input.testPoint().evidenceKeys(),
                                    FunctionalTestcaseDesignResult.CaseStatus.FORMAL, List.of()))));
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            com.testcaseagent.validation.FunctionalTestcaseResultValidator validator = invocation.getArgument(1);
            com.testcaseagent.validation.FunctionalTestcaseResultValidator.WorkItem workItem = invocation.getArgument(2);
            com.testcaseagent.validation.FunctionalTestcaseResultValidator.Result result = invocation.getArgument(3);
            assertThat(workItem.description()).doesNotContain("执行后成功");
            assertThat(workItem.formalSupports()).singleElement().satisfies(support -> {
                assertThat(support.factKey()).isEqualTo("fact-persisted");
                assertThat(support.triggerConditions()).containsExactly("执行");
                assertThat(support.outputs()).containsExactly("成功");
                assertThat(support.evidenceTexts()).containsEntry("req-unit", "执行后成功");
            });
            validator.validate(workItem, result);
            return null;
        }).when(store).acceptTestcases(any(), any(), any(), any());
        StructuredWorkbookExportRequest rows = new StructuredWorkbookExportRequest("task-restart", List.of(), List.of());
        WorkbookArtifact artifact = new WorkbookArtifact("artifact-restart", "d".repeat(64), Path.of("restart.xlsx"));
        when(repository.structuredWorkbookRequest("task-restart")).thenReturn(rows);
        when(exporter.exportStructured(rows)).thenReturn(artifact);
        when(store.aggregateState("task-restart")).thenReturn(
                new StructuredGenerationAcceptanceStore.AggregateState(1, 1, 1, 1, 0, 4, 0, true,
                        1, 0, 0));
        when(store.hasFunctionCandidateAudit("task-restart")).thenReturn(true);

        new DefaultStructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                store, exporter, new ObjectMapper(), heartbeat()).execute("task-restart", request);

        verify(skills, never()).reviewRequirementMaterial(any());
        verify(skills, never()).extractFunctionList(any());
        verify(skills, never()).extractFunctionCandidates(any());
        verify(skills, never()).reconcileFeatureScope(any());
        verify(skills, never()).reconcileFeatureScopePage(any());
        verify(traversal, never()).traverse(anyString(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(skills, org.mockito.Mockito.atLeastOnce()).designFunctionalTestcases(any());
        verify(exporter).exportStructured(rows);
        verify(exporter, never()).exportMarkdown(any());
        verify(repository).completeStructuredTask("task-restart", artifact,
                com.testcaseagent.structuredgeneration.StructuredProcessingStatus.COMPLETED,
                com.testcaseagent.structuredgeneration.StructuredCoverageStatus.COMPLETE, true);
    }

    /** [Req-ID]: REQ-ESR-008 */
    @Test
    void completedReviewsAreNotReconstructedWhenOnlyFunctionExtractionRemainsQueued() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest request = mock(CreateGenerationTaskRequest.class);
        MaterialInventoryDocument reviewedMaterial = prototypeMaterialWithUnits(32);
        MaterialInventoryDocument functionMaterial = functionMaterialWithUnits(1);
        when(request.agentId()).thenReturn("agent-1");
        when(request.requirementScope()).thenReturn(scope("prototype-1", "function-list-1"));
        when(repository.hasCompleteMaterialInventory("task-review-stage-resume", request.requirementScope()))
                .thenReturn(true);
        when(repository.materialInventoryDocuments("task-review-stage-resume"))
                .thenReturn(List.of(reviewedMaterial, functionMaterial));
        when(store.hasCompletedReviewStage("task-review-stage-resume")).thenReturn(true);
        when(store.hasCompletedMaterialStages("task-review-stage-resume")).thenReturn(false);
        var persistedRegistry = com.testcaseagent.validation.StructuredValidationRegistry
                .forTask("task-review-stage-resume");
        persistedRegistry.registerEvidence(new com.testcaseagent.validation.StructuredEvidence(
                "prototype-unit-1", "task-review-stage-resume", "prototype-1", false, false, true));
        persistedRegistry.registerEvidence(new com.testcaseagent.validation.StructuredEvidence(
                "fn-unit-1", "task-review-stage-resume", "function-list-1", false, false, true));
        persistedRegistry.register(StructuredKeyType.MATERIAL, "prototype-1");
        persistedRegistry.register(StructuredKeyType.MATERIAL, "function-list-1");
        persistedRegistry.register(StructuredKeyType.REQUIREMENT_FACT, "fact-already-accepted");
        when(store.persistedValidationRegistry("task-review-stage-resume")).thenReturn(persistedRegistry);
        when(sessions.openStructuredSession()).thenReturn("session-review-stage-resume");
        when(store.materialWindowPlans("task-review-stage-resume", "REQUIREMENT_MATERIAL_REVIEW", "prototype-1"))
                .thenReturn(List.of(legacyWindow("SPLIT")));
        when(store.materialWindowPlans("task-review-stage-resume", "FEATURE_SCOPE_EXTRACT", "function-list-1"))
                .thenReturn(List.of());
        when(store.register(any())).thenAnswer(invocation -> {
            StructuredGenerationAcceptanceStore.WorkRegistration registration = invocation.getArgument(0);
            if ("REQUIREMENT_MATERIAL_REVIEW".equals(registration.operationName())) {
                if (registration.splitDepth() > 0 && registration.materialDocumentId() == null) {
                    throw new IllegalArgumentException(
                            "Historical material work cannot carry split lineage without a document");
                }
                return "historical-review-root";
            }
            return "queued-extraction";
        });
        when(store.isSplit("historical-review-root")).thenReturn(true);
        when(store.claimRegistered("task-review-stage-resume", "queued-extraction", "structured-all-worker"))
                .thenReturn(java.util.Optional.of(new StructuredGenerationAcceptanceStore.WorkClaim(
                        "queued-extraction", "attempt-extraction", "task-review-stage-resume", "b".repeat(64),
                        "feature-scope-reconciliation", "FEATURE_SCOPE_EXTRACT", 1, 1, 1,
                        "function-list-1", List.of("fn-unit-1"), "structured-all-worker")));
        stubNoFunctionCandidates(skills);
        StructuredGenerationAcceptanceStore.AcceptedFact acceptedFact =
                new StructuredGenerationAcceptanceStore.AcceptedFact(
                        "fact-already-accepted", "已验收事实", List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of("prototype-unit-1"),
                        Map.of("prototype-unit-1", "已验收事实"));
        when(store.acceptedInputs("task-review-stage-resume")).thenReturn(
                new StructuredGenerationAcceptanceStore.AcceptedInputs(List.of(acceptedFact), List.of()));
        StructuredWorkbookExportRequest rows = new StructuredWorkbookExportRequest(
                "task-review-stage-resume", List.of(), List.of());
        WorkbookArtifact artifact = new WorkbookArtifact(
                "artifact-review-stage-resume", "f".repeat(64), Path.of("review-stage-resume.xlsx"));
        when(repository.structuredWorkbookRequest("task-review-stage-resume")).thenReturn(rows);
        when(exporter.exportStructured(rows)).thenReturn(artifact);
        when(store.aggregateState("task-review-stage-resume")).thenReturn(
                new StructuredGenerationAcceptanceStore.AggregateState(67, 67, 0, 0, 0, 68, 0, true));

        new DefaultStructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                store, exporter, new ObjectMapper(), heartbeat()).execute("task-review-stage-resume", request);

        verify(store, never()).materialWindowPlans(
                "task-review-stage-resume", "REQUIREMENT_MATERIAL_REVIEW", "prototype-1");
        verify(skills, never()).reviewRequirementMaterial(any());
        verify(skills).extractFunctionCandidates(any());
        verify(store).acceptFunctionCandidates(any(), any());
        verify(traversal, never()).traverse(anyString(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    /** [Req-ID]: REQ-ESR-003 */
    @Test
    void explicitRetrySkipsTwoCompletedSlicesAndInvokesOnlyTheRecoveredThirdSlice() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest request = mock(CreateGenerationTaskRequest.class);
        when(request.agentId()).thenReturn("agent-1");
        when(request.requirementScope()).thenReturn(scope("prototype-1"));
        when(sessions.openStructuredSession()).thenReturn("session-explicit-retry");
        when(store.materialWindowPlans("task-explicit-retry", "REQUIREMENT_MATERIAL_REVIEW", "prototype-1"))
                .thenReturn(List.of(legacyWindow("FAILED")));
        when(traversal.traverse("task-explicit-retry", request, false)).thenReturn(
                new RequirementMaterialTraversalService.TraversalResult(List.of(prototypeMaterialWithUnits(96))));
        when(store.register(any())).thenAnswer(invocation -> {
            StructuredGenerationAcceptanceStore.WorkRegistration registration = invocation.getArgument(0);
            return "work-" + registration.ordinalStart();
        });
        when(store.isCompleted("work-1")).thenReturn(true);
        when(store.isCompleted("work-33")).thenReturn(true);
        when(store.isCompleted("work-65")).thenReturn(false);
        when(store.claimRegistered("task-explicit-retry", "work-65", "structured-all-worker"))
                .thenReturn(java.util.Optional.of(new StructuredGenerationAcceptanceStore.WorkClaim(
                        "work-65", "attempt-2", "task-explicit-retry", "3".repeat(64),
                        "requirement-material-quality-review", "REQUIREMENT_MATERIAL_REVIEW", 2,
                        65, 96, "prototype-1", java.util.stream.IntStream.rangeClosed(65, 96)
                                .mapToObj(index -> "prototype-unit-" + index).toList(), "structured-all-worker")));
        when(skills.reviewRequirementMaterial(any())).thenReturn(success("requirement-material-quality-review",
                new RequirementMaterialQualityReviewResult(List.of(), List.of(
                        new RequirementMaterialQualityReviewResult.ReviewFinding("finding-retried",
                                RequirementMaterialQualityReviewResult.RootCauseKind.MISSING_FUNCTION_SCOPE,
                                "功能范围需要补充", new RequirementMaterialQualityReviewResult.AffectedScope(
                                        List.of("prototype-unit-65"), "原型范围"),
                                new RequirementMaterialQualityReviewResult.BadSourceExample(
                                        "prototype-unit-65", "原型内容 65"),
                                new RequirementMaterialQualityReviewResult.ProposedGoodExample(
                                        RequirementMaterialQualityReviewResult.ProposalStatus.PENDING_CONFIRMATION,
                                        "待需求方确认：补充功能范围"),
                                "原型未说明范围", List.of("prototype-unit-65"), "影响范围用例设计", "本项目待确认",
                                "设计中心补充范围模板", RequirementMaterialQualityReviewResult.HandlingLevel.IMPROVEMENT)))));
        when(store.acceptedInputs("task-explicit-retry")).thenReturn(
                new StructuredGenerationAcceptanceStore.AcceptedInputs(List.of(), List.of()));
        StructuredWorkbookExportRequest rows = new StructuredWorkbookExportRequest("task-explicit-retry", List.of(), List.of());
        WorkbookArtifact artifact = new WorkbookArtifact("artifact-explicit-retry", "e".repeat(64), Path.of("retry.xlsx"));
        when(repository.structuredWorkbookRequest("task-explicit-retry")).thenReturn(rows);
        when(exporter.exportStructured(rows)).thenReturn(artifact);
        when(store.aggregateState("task-explicit-retry")).thenReturn(
                new StructuredGenerationAcceptanceStore.AggregateState(3, 3, 0, 0, 0, 3, 0, true));

        new DefaultStructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                store, exporter, new ObjectMapper(), heartbeat()).execute("task-explicit-retry", request);

        var invocation = org.mockito.ArgumentCaptor.forClass(
                com.testcaseagent.knowledgeagent.RequirementMaterialQualityReviewInvocation.class);
        verify(skills).reviewRequirementMaterial(invocation.capture());
        assertThat(invocation.getValue().input().units()).hasSize(32);
        assertThat(invocation.getValue().input().units()).extracting(
                com.testcaseagent.knowledgeagent.RequirementMaterialQualityReviewInput.MaterialUnit::ordinal)
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(65, 96).boxed().toList());
        verify(store, never()).claimRegistered("task-explicit-retry", "work-1", "structured-all-worker");
        verify(store, never()).claimRegistered("task-explicit-retry", "work-33", "structured-all-worker");
        verify(store).claimRegistered("task-explicit-retry", "work-65", "structured-all-worker");
        verify(store).acceptReview(any(), any(), any(), any());
    }

    private static StructuredGenerationAcceptanceStore.MaterialWindowPlan legacyWindow(String status) {
        return new StructuredGenerationAcceptanceStore.MaterialWindowPlan(
                "legacy-work", "a".repeat(64), status, 1, 32, null, List.of(), List.of(), null, 0);
    }

    private static MaterialInventoryDocument material(String documentId, String role, String unitId, String content) {
        return new MaterialInventoryDocument(documentId, documentId, role, 1, true,
                List.of(new MaterialInventoryUnit(documentId, role, unitId, 0, 1, content, 0, content.length())));
    }

    private static RequirementScope scope(String... documentIds) {
        return RequirementScope.freeze("kb-1", "system-1", "version-1", "requirements_spec", "project-1",
                java.util.Arrays.stream(documentIds).map(RequirementDocumentCoordinate::new).toList());
    }

    private static MaterialInventoryDocument functionMaterialWithUnits(int count) {
        List<MaterialInventoryUnit> units = java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(index -> new MaterialInventoryUnit("function-list-1", "FUNCTION_LIST", "fn-unit-" + index,
                        index - 1, index, "功能 " + index, 0, 4)).toList();
        return new MaterialInventoryDocument("function-list-1", "功能清单", "FUNCTION_LIST", count, true, units);
    }

    private static MaterialInventoryDocument prototypeMaterialWithUnits(int count) {
        return prototypeMaterialWithUnits(1, count);
    }

    private static MaterialInventoryDocument prototypeMaterialWithUnits(int firstOrdinal, int lastOrdinal) {
        List<MaterialInventoryUnit> units = java.util.stream.IntStream.rangeClosed(firstOrdinal, lastOrdinal)
                .mapToObj(index -> new MaterialInventoryUnit("prototype-1", "PROTOTYPE", "prototype-unit-" + index,
                        index - 1, index, "原型内容 " + index, 0, 7)).toList();
        return new MaterialInventoryDocument("prototype-1", "原型", "PROTOTYPE", units.size(), true, units);
    }

    private static void verifyNoStructuredCalls(StructuredSkillExecutionPort skills, WorkbookExporter exporter) {
        verify(skills, never()).reviewRequirementMaterial(any());
        verify(skills, never()).extractFunctionList(any());
        verify(skills, never()).extractFunctionCandidates(any());
        verify(skills, never()).reconcileFeatureScope(any());
        verify(skills, never()).designFunctionalTestcases(any());
        verify(exporter, never()).exportStructured(any());
        verify(exporter, never()).exportMarkdown(any());
    }

    private static StructuredWorkLeaseHeartbeat heartbeat() {
        StructuredWorkLeaseHeartbeat heartbeat = mock(StructuredWorkLeaseHeartbeat.class);
        StructuredWorkLeaseHeartbeat.ActiveLease activeLease = mock(StructuredWorkLeaseHeartbeat.ActiveLease.class);
        when(heartbeat.start(any(), any())).thenReturn(activeLease);
        return heartbeat;
    }

    private static <T> StructuredSkillSuccessEnvelope<T> success(String skillName, T result) {
        return new StructuredSkillSuccessEnvelope<>(true, new StructuredSkillSuccess<>("1.0", skillName, false, result));
    }

    private static FunctionCandidateExtractionResult noFunctionCandidateResult(
            FunctionCandidateExtractionInput input) {
        return new FunctionCandidateExtractionResult("extract_function_candidates", "1", input.windowKey(),
                input.units().stream().map(unit -> new FunctionCandidateExtractionResult.SourceOutcome(
                        unit.unitKey(), FunctionCandidateExtractionResult.Disposition.NO_FUNCTION,
                        List.of(), "non_functional_content")).toList(),
                List.of(), new FunctionCandidateExtractionResult.NormalizationSummary(0, 0, 0, 0));
    }

    private static void stubNoFunctionCandidates(StructuredSkillExecutionPort skills) {
        when(skills.extractFunctionCandidates(any())).thenAnswer(invocation -> success(
                "feature-scope-reconciliation", noFunctionCandidateResult(
                        invocation.getArgument(0, FunctionCandidateExtractionInvocation.class).input())));
    }
}
