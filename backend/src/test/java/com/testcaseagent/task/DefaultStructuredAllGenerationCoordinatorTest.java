package com.testcaseagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.testcaseagent.knowledgeagent.FeatureScopeReconciliationResult;
import com.testcaseagent.knowledgeagent.FunctionListExtractionResult;
import com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignResult;
import com.testcaseagent.knowledgeagent.RequirementMaterialQualityReviewResult;
import com.testcaseagent.knowledgeagent.StructuredSkillExecutionPort;
import com.testcaseagent.knowledgeagent.StructuredSkillExecutionException;
import com.testcaseagent.knowledgeagent.StructuredSkillErrorType;
import com.testcaseagent.knowledgeagent.StructuredSkillSessionPort;
import com.testcaseagent.knowledgeagent.StructuredSkillSuccess;
import com.testcaseagent.knowledgeagent.StructuredSkillSuccessEnvelope;
import com.testcaseagent.scope.RequirementScope;
import com.testcaseagent.structuredgeneration.StructuredGenerationAcceptanceStore;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;

/** Orchestration tests for the production structured ALL route. [Req-ID]: REQ-STG-001~007, REQ-FTG-005 */
class DefaultStructuredAllGenerationCoordinatorTest {

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
        when(request.requirementScope()).thenReturn(mock(RequirementScope.class));
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
                store, exporter, new ObjectMapper());

        assertThatThrownBy(() -> coordinator.execute("task-review-grounding", request))
                .isInstanceOf(IllegalArgumentException.class);
        verify(store).fail(any(), org.mockito.ArgumentMatchers.eq("business_validation_failed"));
        verify(skills).reviewRequirementMaterial(any());
        verify(skills, never()).extractFunctionList(any());
        verify(skills, never()).reconcileFeatureScope(any());
        verify(skills, never()).designFunctionalTestcases(any());
        verify(exporter, never()).exportStructured(any());
        verify(exporter, never()).exportMarkdown(any());
    }

    @Test
    void runsReviewExtractReconcileAndTestcaseInOrderThenExportsOnlyPersistedStructuredRows() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest request = mock(CreateGenerationTaskRequest.class);
        RequirementScope scope = mock(RequirementScope.class);
        when(request.agentId()).thenReturn("agent-1");
        when(request.requirementScope()).thenReturn(scope);
        when(sessions.openStructuredSession()).thenReturn("session-1");
        when(traversal.traverse("task-1", request, false)).thenReturn(new RequirementMaterialTraversalService.TraversalResult(List.of(
                material("requirement-1", "REQUIREMENT", "req-unit", "需求正文"),
                material("function-list-1", "FUNCTION_LIST", "fn-unit", "功能清单正文"))));
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
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of("req-unit"))), List.of())));
        when(skills.extractFunctionList(any())).thenReturn(success("feature-scope-reconciliation",
                new FunctionListExtractionResult(List.of(new FunctionListExtractionResult.FunctionListItem(
                        "订单/提交", "提交订单", List.of("fn-unit"))))));
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
        when(skills.reconcileFeatureScope(any())).thenAnswer(invocation -> {
            FeatureScopeReconciliationInput input = invocation.getArgument(0, com.testcaseagent.knowledgeagent.FeatureScopeReconciliationInvocation.class).input();
            return success("feature-scope-reconciliation", new FeatureScopeReconciliationResult(List.of(
                    new FeatureScopeReconciliationResult.Reconciliation("model-reconciliation",
                            List.of(input.functionListItems().get(0).itemKey()), List.of(input.requirementFacts().get(0).factKey()),
                            FeatureScopeReconciliationResult.Classification.EXACT_MATCH, List.of("fn-unit", "req-unit"),
                            "保持范围", FeatureScopeReconciliationResult.ConfirmationStatus.CONFIRMED))));
        });
        when(skills.designFunctionalTestcases(any())).thenAnswer(invocation -> {
            var input = invocation.getArgument(0, com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignInvocation.class).input();
            assertThat(input.functionName()).isEqualTo("订单/提交");
            assertThat(input.testPoint().requirementFactKeys()).containsExactly("fact-task");
            return success("functional-testcase-design", new FunctionalTestcaseDesignResult(input.functionKey(),
                    input.testPoint().testPointKey(), List.of(new FunctionalTestcaseDesignResult.Testcase(
                            "model-case", "正常提交订单", List.of(),
                            List.of(new FunctionalTestcaseDesignResult.Step(1, "提交", "订单创建成功")),
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
                1, 1, 1, 1, 0, 4, 0, true));
        var coordinator = new DefaultStructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                store, exporter, new ObjectMapper());

        coordinator.execute("task-1", request);

        InOrder order = inOrder(skills, exporter);
        order.verify(skills).reviewRequirementMaterial(any());
        order.verify(skills).extractFunctionList(any());
        order.verify(skills).reconcileFeatureScope(any());
        order.verify(skills, org.mockito.Mockito.atLeastOnce()).designFunctionalTestcases(any());
        order.verify(exporter).exportStructured(persistedRows);
        verify(exporter, never()).exportMarkdown(any());
        verify(repository).completeStructuredTask("task-1", artifact,
                com.testcaseagent.structuredgeneration.StructuredProcessingStatus.COMPLETED,
                com.testcaseagent.structuredgeneration.StructuredCoverageStatus.COMPLETE);
    }

    @Test
    void completesZeroFunctionResultOnlyAfterExportingAndVerifyingThePersistedTwoSheetWorkbook() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest request = mock(CreateGenerationTaskRequest.class);
        when(request.agentId()).thenReturn("agent-1");
        when(request.requirementScope()).thenReturn(mock(RequirementScope.class));
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
        when(skills.extractFunctionList(any())).thenReturn(success("feature-scope-reconciliation",
                new FunctionListExtractionResult(List.of())));
        when(store.acceptedInputs("task-empty")).thenReturn(
                new StructuredGenerationAcceptanceStore.AcceptedInputs(List.of(), List.of()));
        StructuredWorkbookExportRequest emptyRows = new StructuredWorkbookExportRequest("task-empty", List.of(), List.of());
        when(repository.structuredWorkbookRequest("task-empty")).thenReturn(emptyRows);
        WorkbookArtifact artifact = new WorkbookArtifact("artifact-empty", "b".repeat(64), Path.of("empty.xlsx"));
        when(exporter.exportStructured(emptyRows)).thenReturn(artifact);
        when(store.aggregateState("task-empty")).thenReturn(
                new StructuredGenerationAcceptanceStore.AggregateState(0, 0, 0, 0, 0, 1, 0, true));

        new DefaultStructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                store, exporter, new ObjectMapper()).execute("task-empty", request);

        verify(exporter).exportStructured(emptyRows);
        verify(repository).completeStructuredTask("task-empty", artifact,
                com.testcaseagent.structuredgeneration.StructuredProcessingStatus.COMPLETED,
                com.testcaseagent.structuredgeneration.StructuredCoverageStatus.UNABLE_TO_GENERATE);
        verify(skills, never()).reconcileFeatureScope(any());
        verify(skills, never()).designFunctionalTestcases(any());
        verify(exporter, never()).exportMarkdown(any());
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
        when(request.requirementScope()).thenReturn(mock(RequirementScope.class));
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
        when(skills.extractFunctionList(any()))
                .thenThrow(new StructuredSkillExecutionException(transientFailure, false))
                .thenReturn(success("feature-scope-reconciliation", new FunctionListExtractionResult(List.of())));
        when(store.acceptedInputs("task-retry")).thenReturn(
                new StructuredGenerationAcceptanceStore.AcceptedInputs(List.of(), List.of()));
        StructuredWorkbookExportRequest rows = new StructuredWorkbookExportRequest("task-retry", List.of(), List.of());
        WorkbookArtifact artifact = new WorkbookArtifact("artifact-retry", "c".repeat(64), Path.of("retry.xlsx"));
        when(repository.structuredWorkbookRequest("task-retry")).thenReturn(rows);
        when(exporter.exportStructured(rows)).thenReturn(artifact);
        when(store.aggregateState("task-retry")).thenReturn(
                new StructuredGenerationAcceptanceStore.AggregateState(0, 0, 0, 0, 0, 1, 0, true));

        new DefaultStructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                store, exporter, new ObjectMapper()).execute("task-retry", request);

        verify(skills, org.mockito.Mockito.times(2)).extractFunctionList(any());
        verify(store, org.mockito.Mockito.times(2)).claimRegistered(
                "task-retry", "work-retry", "structured-all-worker");
        verify(repository).completeStructuredTask("task-retry", artifact,
                com.testcaseagent.structuredgeneration.StructuredProcessingStatus.COMPLETED,
                com.testcaseagent.structuredgeneration.StructuredCoverageStatus.UNABLE_TO_GENERATE);
    }

    @Test
    void doesNotRetryStructuredOutputInvalidOrFallBackToMarkdown() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        RequirementMaterialTraversalService traversal = mock(RequirementMaterialTraversalService.class);
        StructuredSkillExecutionPort skills = mock(StructuredSkillExecutionPort.class);
        StructuredSkillSessionPort sessions = mock(StructuredSkillSessionPort.class);
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        WorkbookExporter exporter = mock(WorkbookExporter.class);
        CreateGenerationTaskRequest request = mock(CreateGenerationTaskRequest.class);
        when(request.agentId()).thenReturn("agent-1");
        when(request.requirementScope()).thenReturn(mock(RequirementScope.class));
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
        when(skills.extractFunctionList(any())).thenThrow(
                new StructuredSkillExecutionException(StructuredSkillErrorType.STRUCTURED_OUTPUT_INVALID, true));

        var coordinator = new DefaultStructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                store, exporter, new ObjectMapper());

        assertThatThrownBy(() -> coordinator.execute("task-invalid", request))
                .isInstanceOf(StructuredSkillExecutionException.class);
        verify(skills).extractFunctionList(any());
        verify(store).claimRegistered("task-invalid", "work-invalid", "structured-all-worker");
        verify(repository).failStructuredTask("task-invalid",
                com.testcaseagent.structuredgeneration.StructuredCoverageStatus.PENDING);
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
        CreateGenerationTaskRequest request = mock(CreateGenerationTaskRequest.class);
        when(request.agentId()).thenReturn("agent-1");
        when(request.requirementScope()).thenReturn(mock(RequirementScope.class));
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
                store, exporter, new ObjectMapper());

        assertThatThrownBy(() -> coordinator.execute("task-grounding", request))
                .isInstanceOf(IllegalArgumentException.class);
        verify(skills).designFunctionalTestcases(any());
        verify(store).claimRegistered(org.mockito.ArgumentMatchers.eq("task-grounding"), anyString(),
                org.mockito.ArgumentMatchers.eq("structured-all-worker"));
        verify(store).fail(any(), org.mockito.ArgumentMatchers.eq("business_validation_failed"));
        verify(repository).failStructuredTask("task-grounding",
                com.testcaseagent.structuredgeneration.StructuredCoverageStatus.PENDING);
        verify(exporter, never()).exportStructured(any());
        verify(exporter, never()).exportMarkdown(any());
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
                store, exporter, new ObjectMapper()).execute("task-cancel-traversal", request);

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
        when(request.requirementScope()).thenReturn(mock(RequirementScope.class));
        when(sessions.openStructuredSession()).thenReturn("session-1");
        when(traversal.traverse("task-cancel-between", request, false)).thenReturn(
                new RequirementMaterialTraversalService.TraversalResult(List.of(functionMaterialWithUnits(33))));
        when(repository.isCancellationRequested("task-cancel-between")).thenReturn(false, false, false, false, true);
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
        when(skills.extractFunctionList(any())).thenReturn(success("feature-scope-reconciliation",
                new FunctionListExtractionResult(List.of())));

        new DefaultStructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                store, exporter, new ObjectMapper()).execute("task-cancel-between", request);

        verify(skills).extractFunctionList(any());
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
        when(request.requirementScope()).thenReturn(mock(RequirementScope.class));
        when(sessions.openStructuredSession()).thenReturn("session-restart");
        when(traversal.traverse("task-restart", request, false)).thenReturn(
                new RequirementMaterialTraversalService.TraversalResult(List.of(
                        material("requirement-1", "REQUIREMENT", "req-unit", "需求正文"),
                        material("function-list-1", "FUNCTION_LIST", "fn-unit", "功能正文"))));
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
                new StructuredGenerationAcceptanceStore.AggregateState(1, 1, 1, 1, 0, 4, 0, true));

        new DefaultStructuredAllGenerationCoordinator(repository, traversal, skills, sessions,
                store, exporter, new ObjectMapper()).execute("task-restart", request);

        verify(skills, never()).reviewRequirementMaterial(any());
        verify(skills, never()).extractFunctionList(any());
        verify(skills, never()).reconcileFeatureScope(any());
        verify(skills, org.mockito.Mockito.atLeastOnce()).designFunctionalTestcases(any());
        verify(exporter).exportStructured(rows);
        verify(exporter, never()).exportMarkdown(any());
        verify(repository).completeStructuredTask("task-restart", artifact,
                com.testcaseagent.structuredgeneration.StructuredProcessingStatus.COMPLETED,
                com.testcaseagent.structuredgeneration.StructuredCoverageStatus.COMPLETE);
    }

    private static MaterialInventoryDocument material(String documentId, String role, String unitId, String content) {
        return new MaterialInventoryDocument(documentId, documentId, role, 1, true,
                List.of(new MaterialInventoryUnit(documentId, role, unitId, 0, 1, content, 0, content.length())));
    }

    private static MaterialInventoryDocument functionMaterialWithUnits(int count) {
        List<MaterialInventoryUnit> units = java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(index -> new MaterialInventoryUnit("function-list-1", "FUNCTION_LIST", "fn-unit-" + index,
                        index - 1, index, "功能 " + index, 0, 4)).toList();
        return new MaterialInventoryDocument("function-list-1", "功能清单", "FUNCTION_LIST", count, true, units);
    }

    private static void verifyNoStructuredCalls(StructuredSkillExecutionPort skills, WorkbookExporter exporter) {
        verify(skills, never()).reviewRequirementMaterial(any());
        verify(skills, never()).extractFunctionList(any());
        verify(skills, never()).reconcileFeatureScope(any());
        verify(skills, never()).designFunctionalTestcases(any());
        verify(exporter, never()).exportStructured(any());
        verify(exporter, never()).exportMarkdown(any());
    }

    private static <T> StructuredSkillSuccessEnvelope<T> success(String skillName, T result) {
        return new StructuredSkillSuccessEnvelope<>(true, new StructuredSkillSuccess<>("1.0", skillName, false, result));
    }
}
