package com.testcaseagent.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testcaseagent.diagnostics.WorkflowDiagnostics;
import com.testcaseagent.export.StructuredWorkbookExportRequest;
import com.testcaseagent.export.WorkbookArtifact;
import com.testcaseagent.export.WorkbookExporter;
import com.testcaseagent.featureaudit.MaterialInventoryDocument;
import com.testcaseagent.featureaudit.MaterialInventoryUnit;
import com.testcaseagent.featureaudit.RequirementMaterialTraversalService;
import com.testcaseagent.identity.LengthPrefixedSha256;
import com.testcaseagent.knowledgeagent.FeatureScopeReconciliationInput;
import com.testcaseagent.knowledgeagent.FeatureScopeReconciliationInvocation;
import com.testcaseagent.knowledgeagent.FeatureScopeReconciliationPageInput;
import com.testcaseagent.knowledgeagent.FeatureScopeReconciliationPageInvocation;
import com.testcaseagent.knowledgeagent.FeatureScopeReconciliationPageResult;
import com.testcaseagent.knowledgeagent.FunctionListExtractionInput;
import com.testcaseagent.knowledgeagent.FunctionListExtractionInvocation;
import com.testcaseagent.knowledgeagent.FunctionCandidateExtractionInput;
import com.testcaseagent.knowledgeagent.FunctionCandidateExtractionInvocation;
import com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignInput;
import com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignInvocation;
import com.testcaseagent.knowledgeagent.FormalSupport;
import com.testcaseagent.knowledgeagent.MaterialContentTypeKey;
import com.testcaseagent.knowledgeagent.RequirementMaterialQualityReviewInput;
import com.testcaseagent.knowledgeagent.RequirementMaterialQualityReviewInvocation;
import com.testcaseagent.knowledgeagent.StructuredSkillExecutionException;
import com.testcaseagent.knowledgeagent.StructuredSkillErrorType;
import com.testcaseagent.knowledgeagent.StructuredSkillExecutionPort;
import com.testcaseagent.knowledgeagent.StructuredSkillSessionPort;
import com.testcaseagent.scope.RequirementScope;
import com.testcaseagent.structuredgeneration.StructuredCompletionGate;
import com.testcaseagent.structuredgeneration.StructuredCoverageStatus;
import com.testcaseagent.structuredgeneration.StructuredGenerationAcceptanceStore;
import com.testcaseagent.structuredgeneration.StructuredMaterialSlicePlanner;
import com.testcaseagent.structuredgeneration.StructuredProcessingStatus;
import com.testcaseagent.structuredgeneration.StructuredReconciliationV2Planner;
import com.testcaseagent.structuredgeneration.StructuredReconciliationV2Validator;
import com.testcaseagent.structuredgeneration.StructuredSkillResultMapper;
import com.testcaseagent.structuredgeneration.StructuredTestPointPlanner;
import com.testcaseagent.validation.FeatureReconciliationValidator;
import com.testcaseagent.validation.FunctionListExtractionValidator;
import com.testcaseagent.validation.FunctionCandidateExtractionValidator;
import com.testcaseagent.validation.FunctionalTestcaseResultValidator;
import com.testcaseagent.validation.RequirementMaterialReviewValidator;
import com.testcaseagent.validation.StructuredEvidence;
import com.testcaseagent.validation.StructuredKeyType;
import com.testcaseagent.validation.StructuredValidationRegistry;
import com.testcaseagent.validation.StructuredValidationException;
import com.testcaseagent.validation.StructuredValidationFailure;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Function;

/** Production implementation of the parsed-units to structured two-sheet ALL workflow. [Req-ID]: REQ-STG-001~007, REQ-FTG-003, REQ-FTG-006~009 */
public final class DefaultStructuredAllGenerationCoordinator implements StructuredAllGenerationCoordinator {
    private static final String OWNER = "structured-all-worker";
    private final GenerationTaskRepository repository;
    private final RequirementMaterialTraversalService traversal;
    private final StructuredSkillExecutionPort skills;
    private final StructuredSkillSessionPort sessions;
    private final StructuredGenerationAcceptanceStore store;
    private final WorkbookExporter exporter;
    private final ObjectMapper objectMapper;
    private final StructuredWorkLeaseHeartbeat leaseHeartbeat;
    private final StructuredReconciliationV2Planner reconciliationV2Planner;
    private final StructuredReconciliationV2Validator reconciliationV2Validator;
    private final StructuredMaterialSlicePlanner materialPlanner = new StructuredMaterialSlicePlanner();
    private final FunctionListExtractionValidator extractionValidator = new FunctionListExtractionValidator();
    private final FunctionCandidateExtractionValidator candidateExtractionValidator =
            new FunctionCandidateExtractionValidator();
    private final RequirementMaterialReviewValidator reviewValidator = new RequirementMaterialReviewValidator();
    private final FeatureReconciliationValidator reconciliationValidator = new FeatureReconciliationValidator();
    private final FunctionalTestcaseResultValidator testcaseValidator = new FunctionalTestcaseResultValidator();
    private final StructuredTestPointPlanner testPointPlanner = new StructuredTestPointPlanner();
    private final StructuredCompletionGate completionGate = new StructuredCompletionGate();

    /** Creates the only production coordinator used by ALL tasks. */
    public DefaultStructuredAllGenerationCoordinator(GenerationTaskRepository repository,
            RequirementMaterialTraversalService traversal, StructuredSkillExecutionPort skills,
            StructuredSkillSessionPort sessions, StructuredGenerationAcceptanceStore store,
            WorkbookExporter exporter, ObjectMapper objectMapper,
            StructuredWorkLeaseHeartbeat leaseHeartbeat) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.traversal = Objects.requireNonNull(traversal, "traversal must not be null");
        this.skills = Objects.requireNonNull(skills, "skills must not be null");
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.exporter = Objects.requireNonNull(exporter, "exporter must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.leaseHeartbeat = Objects.requireNonNull(leaseHeartbeat, "leaseHeartbeat must not be null");
        this.reconciliationV2Planner = new StructuredReconciliationV2Planner();
        this.reconciliationV2Validator = new StructuredReconciliationV2Validator(objectMapper);
    }

    @Override
    public void execute(String taskId, CreateGenerationTaskRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        StructuredCoordinatorFailure.Stage stage = StructuredCoordinatorFailure.Stage.TASK_START_STATE_RESUME;
        try {
            store.updateTaskState(taskId, new StructuredGenerationAcceptanceStore.StructuredTaskState(
                    StructuredProcessingStatus.RUNNING, StructuredCoverageStatus.PENDING));
            cancellationCheckpoint(taskId);
            stage = StructuredCoordinatorFailure.Stage.INVENTORY_RESUME_TRAVERSAL;
            boolean resumeMaterialStages = store.hasCompletedMaterialStages(taskId);
            boolean candidateProtocol = resumeMaterialStages && store.hasFunctionCandidateAudit(taskId);
            // Review and extraction are separate durable stages. A queued extraction must not make recovery
            // reconstruct historical review split trees whose accepted facts and findings are already immutable.
            // Full material-stage completion implies review completion and preserves legacy restart tests/callers.
            boolean resumeReviewStage = resumeMaterialStages || store.hasCompletedReviewStage(taskId);
            boolean hasFrozenInventory = repository.hasCompleteMaterialInventory(taskId, request.requirementScope());
            StructuredValidationRegistry registry;
            List<MaterialInventoryDocument> materials = List.of();
            if (resumeMaterialStages) {
                // Recovery trusts only the complete application-owned snapshot; it must not repeat
                // remote parsed-unit traversal or any already accepted review/extraction call.
                registry = store.persistedValidationRegistry(taskId);
            } else {
                if (resumeReviewStage && !hasFrozenInventory) {
                    throw new IllegalStateException("Completed review recovery requires its complete frozen inventory");
                }
                // A partially completed structured task must resume from the task-owned frozen source.
                // Re-reading the remote catalog here could silently change a child split identity after a restart.
                materials = hasFrozenInventory
                        ? repository.materialInventoryDocuments(taskId)
                        : traversal.traverse(taskId, request, false).documents();
                cancellationCheckpoint(taskId);
                registry = resumeReviewStage
                        ? store.persistedValidationRegistry(taskId)
                        : inventoryRegistry(taskId, materials);
            }
            cancellationCheckpoint(taskId);
            stage = StructuredCoordinatorFailure.Stage.SESSION_OPEN;
            String sessionId = sessions.openStructuredSession();
            if (!resumeReviewStage) {
                stage = StructuredCoordinatorFailure.Stage.MATERIAL_REVIEW;
                executeReviews(taskId, request, sessionId, registry, materials);
            }
            if (!resumeMaterialStages) {
                stage = StructuredCoordinatorFailure.Stage.FUNCTION_EXTRACTION_PRE_SPLIT;
                candidateProtocol = executeExtractions(taskId, request, sessionId, registry, materials);
            }

            cancellationCheckpoint(taskId);
            stage = StructuredCoordinatorFailure.Stage.RECONCILIATION;
            StructuredGenerationAcceptanceStore.AcceptedInputs accepted = store.acceptedInputs(taskId);
            accepted.facts().forEach(fact -> registry.requireOrRegister(StructuredKeyType.REQUIREMENT_FACT, fact.factKey()));
            accepted.functionItems().forEach(item -> registry.requireOrRegister(StructuredKeyType.FUNCTION_LIST_ITEM, item.itemKey()));
            if (accepted.functionItems().isEmpty()) {
                stage = StructuredCoordinatorFailure.Stage.TESTCASE_EXPORT;
                complete(taskId, false, true, candidateProtocol);
                return;
            }
            if (!store.hasCompletedReconciliationWork(taskId)) {
                executeReconciliationV2(taskId, request, sessionId, accepted);
            }
            cancellationCheckpoint(taskId);
            stage = StructuredCoordinatorFailure.Stage.TESTCASE_EXPORT;
            executeTestcases(taskId, request, sessionId, registry, store.acceptedConfirmedFunctions(taskId));
            complete(taskId, true, false, candidateProtocol);
        } catch (CancellationException exception) {
            repository.cancelStructuredTask(taskId, StructuredCoverageStatus.PENDING);
        } catch (RuntimeException exception) {
            if (exception instanceof StructuredValidationException validationException) {
                repository.failStructuredTask(taskId, StructuredCoverageStatus.PENDING, validationException.failure());
                WorkflowDiagnostics.structuredValidationFailure(taskId, null, null, 0,
                        validationException.failure());
            } else {
                StructuredValidationFailure failure = StructuredCoordinatorFailure.from(stage, exception);
                repository.failStructuredTask(taskId, StructuredCoverageStatus.PENDING, failure);
                WorkflowDiagnostics.structuredCoordinatorFailure(taskId, failure);
                // These two exceptions already carry only fixed enum state needed by the existing capacity and
                // lease control flow. Every arbitrary RuntimeException is replaced by a safe exception without
                // retaining its message, cause, or stack-bearing object.
                if (!(exception instanceof StructuredSkillExecutionException)
                        && !(exception instanceof StructuredWorkLeaseLostException)) {
                    throw new StructuredValidationException(failure);
                }
            }
            throw exception;
        }
    }

    private void executeReviews(String taskId, CreateGenerationTaskRequest request, String sessionId,
            StructuredValidationRegistry registry, List<MaterialInventoryDocument> materials) {
        for (MaterialInventoryDocument material : materials) {
            if ("FUNCTION_LIST".equals(material.documentRole())) continue;
            MaterialContentTypeKey contentType = contentType(material.documentRole());
            String sourceLabel = sourceLabel(material);
            List<StructuredGenerationAcceptanceStore.MaterialWindowPlan> durable = store.materialWindowPlans(
                    taskId, "REQUIREMENT_MATERIAL_REVIEW", material.documentId());
            boolean historical = durable.stream().anyMatch(window -> window.materialDocumentId() == null);
            List<RequirementMaterialQualityReviewInput> inputs = historical
                    ? materialPlanner.legacyReviewPlan(material.documentId(), contentType, sourceLabel, material.units())
                    : materialPlanner.plan(material.documentId(), contentType, sourceLabel, material.units());
            for (RequirementMaterialQualityReviewInput input : inputs) {
                executeReviewWindow(taskId, request, sessionId, registry, material, input,
                        historical ? null : material.documentId(), null, 0);
            }
        }
    }

    /**
     * Executes one frozen review leaf or resumes its deterministic children after a capacity split.
     * The recursion follows persisted SPLIT markers, so it never depends on an in-memory retry tree.
     * [Req-ID]: REQ-FTG-010
     */
    private void executeReviewWindow(String taskId, CreateGenerationTaskRequest request, String sessionId,
            StructuredValidationRegistry registry, MaterialInventoryDocument material,
            RequirementMaterialQualityReviewInput input, String materialDocumentId,
            String parentWorkItemId, int splitDepth) {
        cancellationCheckpoint(taskId);
        List<String> evidence = input.units().stream()
                .map(RequirementMaterialQualityReviewInput.MaterialUnit::unitKey).toList();
        Map<String, String> evidenceTexts = new LinkedHashMap<>();
        input.units().forEach(unit -> {
            if (evidenceTexts.putIfAbsent(unit.unitKey(), unit.content()) != null) {
                throw new IllegalArgumentException("Review slice unit keys must be unique");
            }
        });
        String identity = identity(taskId, "REQUIREMENT_MATERIAL_REVIEW", input);
        RequirementScope reviewScope = request.requirementScope().singleDocumentAuthorization(material.documentId());
        var registration = reviewRegistration(taskId, identity, input, evidence,
                materialDocumentId, parentWorkItemId, splitDepth);
        String workId = store.register(registration);
        if (store.isCompleted(workId)) return;
        List<RequirementMaterialQualityReviewInput> children = input.units().size() < 2 ? List.of()
                : materialPlanner.bisect(material.units(), materialPlanner.restoreWindow(
                                material.units(), evidence, input.contextUnits().stream()
                                        .map(RequirementMaterialQualityReviewInput.MaterialUnit::unitKey).toList()))
                        .stream().map(window -> materialPlanner.reviewInput(input.materialKey(),
                                input.contentTypeKey(), input.sourceLabel(), window)).toList();
        if (store.isSplit(workId)) {
            children.forEach(child -> executeReviewWindow(taskId, request, sessionId, registry, material, child,
                    materialDocumentId, materialDocumentId == null ? null : workId, splitDepth + 1));
            return;
        }
        executeRegisteredWork(registration, workId, claim -> {
            var result = StructuredSkillResultMapper.review(skills.reviewRequirementMaterial(
                    new RequirementMaterialQualityReviewInvocation(sessionId, request.agentId(), reviewScope, input)).data().result());
            return namespaceReview(taskId, identity, result);
        }, (claim, namespaced) -> store.acceptReview(claim, reviewValidator,
                new RequirementMaterialReviewValidator.WorkItem(
                        registry, input.materialKey(), input.contentTypeKey().wireValue(), evidence, evidenceTexts), namespaced),
                (claim, failure) -> {
                    if (!isResponseTooLarge(failure) || children.isEmpty()) return false;
                    cancellationCheckpoint(taskId);
                    store.splitReviewWork(claim,
                            reviewRegistration(taskId,
                                    identity(taskId, "REQUIREMENT_MATERIAL_REVIEW", children.get(0)),
                                    children.get(0), children.get(0).units().stream()
                                            .map(RequirementMaterialQualityReviewInput.MaterialUnit::unitKey).toList(),
                                    materialDocumentId, materialDocumentId == null ? null : workId, splitDepth + 1),
                            reviewRegistration(taskId,
                                    identity(taskId, "REQUIREMENT_MATERIAL_REVIEW", children.get(1)),
                                    children.get(1), children.get(1).units().stream()
                                            .map(RequirementMaterialQualityReviewInput.MaterialUnit::unitKey).toList(),
                                    materialDocumentId, materialDocumentId == null ? null : workId, splitDepth + 1));
                    return true;
                });
        if (store.isSplit(workId)) {
            children.forEach(child -> executeReviewWindow(taskId, request, sessionId, registry, material, child,
                    materialDocumentId, materialDocumentId == null ? null : workId, splitDepth + 1));
        }
    }

    private static StructuredGenerationAcceptanceStore.WorkRegistration reviewRegistration(
            String taskId, String identity, RequirementMaterialQualityReviewInput input, List<String> evidence,
            String materialDocumentId, String parentWorkItemId, int splitDepth) {
        return new StructuredGenerationAcceptanceStore.WorkRegistration(taskId, identity,
                "requirement-material-quality-review", "REQUIREMENT_MATERIAL_REVIEW",
                input.units().get(0).ordinal(), input.units().get(input.units().size() - 1).ordinal(),
                input.materialKey(), input.sourceLabel(), evidence, null, null, materialDocumentId,
                input.contextUnits().stream().map(RequirementMaterialQualityReviewInput.MaterialUnit::unitKey).toList(),
                parentWorkItemId, splitDepth);
    }

    private static boolean isResponseTooLarge(RuntimeException failure) {
        return failure instanceof StructuredSkillExecutionException structured
                && structured.type() == StructuredSkillErrorType.RESPONSE_TOO_LARGE;
    }

    private static boolean isCandidateSplitFailure(RuntimeException failure, int targetCount) {
        if (!(failure instanceof StructuredSkillExecutionException structured) || targetCount < 2) return false;
        return structured.type() == StructuredSkillErrorType.REQUEST_TOO_LARGE
                || structured.type() == StructuredSkillErrorType.RESPONSE_TOO_LARGE
                || structured.type() == StructuredSkillErrorType.STRUCTURED_OUTPUT_INVALID;
    }

    private boolean executeExtractions(String taskId, CreateGenerationTaskRequest request, String sessionId,
            StructuredValidationRegistry registry, List<MaterialInventoryDocument> materials) {
        boolean candidateProtocol = false;
        for (MaterialInventoryDocument material : materials) {
            if (!"FUNCTION_LIST".equals(material.documentRole())) continue;
            List<StructuredGenerationAcceptanceStore.MaterialWindowPlan> durable = store.materialWindowPlans(
                    taskId, "FEATURE_SCOPE_EXTRACT", material.documentId());
            boolean historical = durable.stream().anyMatch(window -> window.materialDocumentId() == null);
            if (historical) {
                for (FunctionListExtractionInput input : materialPlanner.legacyExtractionPlan(
                        material.documentId(), sourceLabel(material), material.units())) {
                    executeLegacyExtractionWindow(taskId, request, sessionId, registry, material, input,
                            null, null, 0);
                }
            } else {
                candidateProtocol = true;
                for (FunctionCandidateExtractionInput input : materialPlanner.planCandidateExtraction(
                        taskId, material.documentId(), sourceLabel(material), material.units())) {
                    executeCandidateExtractionWindow(taskId, request, sessionId, material, input,
                            material.documentId(), null, 0);
                }
            }
        }
        return candidateProtocol;
    }

    /**
     * Executes one auditable protocol V1 window and persists only Java-validated candidate decisions.
     * Capacity failures and malformed multi-target results split target ownership at the deterministic midpoint;
     * context is recomputed from the same frozen inventory and never becomes output evidence.
     * [Req-ID]: REQ-AFCE-001, REQ-AFCE-005, REQ-AFCE-007, REQ-AFCE-008
     */
    private void executeCandidateExtractionWindow(String taskId, CreateGenerationTaskRequest request,
            String sessionId, MaterialInventoryDocument material, FunctionCandidateExtractionInput input,
            String materialDocumentId, String parentWorkItemId, int splitDepth) {
        cancellationCheckpoint(taskId);
        List<String> evidence = input.units().stream()
                .map(FunctionCandidateExtractionInput.Unit::unitKey).toList();
        RequirementScope extractionScope = request.requirementScope()
                .singleDocumentAuthorization(material.documentId());
        var registration = candidateExtractionRegistration(
                taskId, input, evidence, materialDocumentId, parentWorkItemId, splitDepth);
        String workId = store.register(registration);
        if (store.isCompleted(workId)) return;
        List<FunctionCandidateExtractionInput> children = input.units().size() < 2 ? List.of()
                : materialPlanner.bisect(material.units(), materialPlanner.restoreWindow(
                                material.units(), evidence, input.contextUnits().stream()
                                        .map(FunctionCandidateExtractionInput.Unit::unitKey).toList()))
                        .stream().map(window -> materialPlanner.candidateExtractionInput(
                                taskId, input.materialKey(), input.sourceLabel(), window)).toList();
        if (store.isSplit(workId)) {
            children.forEach(child -> executeCandidateExtractionWindow(taskId, request, sessionId, material, child,
                    materialDocumentId, workId, splitDepth + 1));
            return;
        }
        try {
            executeRegisteredWork(registration, workId, claim -> {
                var result = skills.extractFunctionCandidates(new FunctionCandidateExtractionInvocation(
                        sessionId, request.agentId(), extractionScope, input)).data().result();
                return candidateExtractionValidator.validate(taskId, input, result);
            }, store::acceptFunctionCandidates, (claim, failure) -> {
                if (!isCandidateSplitFailure(failure, input.units().size()) || children.isEmpty()) return false;
                cancellationCheckpoint(taskId);
                store.splitFunctionListExtractionWork(claim,
                        candidateExtractionRegistration(taskId, children.get(0),
                                children.get(0).units().stream()
                                        .map(FunctionCandidateExtractionInput.Unit::unitKey).toList(),
                                materialDocumentId, workId, splitDepth + 1),
                        candidateExtractionRegistration(taskId, children.get(1),
                                children.get(1).units().stream()
                                        .map(FunctionCandidateExtractionInput.Unit::unitKey).toList(),
                                materialDocumentId, workId, splitDepth + 1));
                return true;
            });
        } catch (StructuredSkillExecutionException failure) {
            if (!isPartialEligibleCandidateFailure(failure)) throw failure;
            // A terminal protocol/capacity leaf is durable and may produce a truthful PARTIAL delivery only when
            // other accepted candidates later support formal cases. Authorization and business validation failures
            // never enter this branch.
            return;
        }
        if (store.isSplit(workId)) {
            children.forEach(child -> executeCandidateExtractionWindow(taskId, request, sessionId, material, child,
                    materialDocumentId, workId, splitDepth + 1));
        }
    }

    private static boolean isPartialEligibleCandidateFailure(StructuredSkillExecutionException failure) {
        return switch (failure.type()) {
            case REQUEST_TOO_LARGE, RESPONSE_TOO_LARGE, STRUCTURED_OUTPUT_INVALID,
                    MODEL_UNAVAILABLE, MODEL_EXECUTION_FAILED -> true;
            default -> false;
        };
    }

    private static StructuredGenerationAcceptanceStore.WorkRegistration candidateExtractionRegistration(
            String taskId, FunctionCandidateExtractionInput input, List<String> evidence,
            String materialDocumentId, String parentWorkItemId, int splitDepth) {
        return new StructuredGenerationAcceptanceStore.WorkRegistration(taskId, input.windowKey(),
                "feature-scope-reconciliation", "FEATURE_SCOPE_EXTRACT",
                input.units().get(0).ordinal(), input.units().get(input.units().size() - 1).ordinal(),
                input.materialKey(), input.sourceLabel(), evidence, null, null, materialDocumentId,
                input.contextUnits().stream().map(FunctionCandidateExtractionInput.Unit::unitKey).toList(),
                parentWorkItemId, splitDepth);
    }

    /**
     * Executes one frozen function-list leaf or resumes its deterministic children after a split.
     * New semantic windows split only after an explicit KEE capacity signal, so malformed structured output is never
     * hidden by progressively smaller requests. The one migration exception is a queued pre-V17 no-context window:
     * it is durably divided before claim because its former 32-target shape is no longer a valid outbound request.
     * Each remote call derives a one-document authorization without mutating the task's complete frozen scope.
     * [Req-ID]: REQ-FTG-012, REQ-FTG-015, REQ-FTG-016
     */
    private void executeLegacyExtractionWindow(String taskId, CreateGenerationTaskRequest request, String sessionId,
            StructuredValidationRegistry registry, MaterialInventoryDocument material, FunctionListExtractionInput input,
            String materialDocumentId, String parentWorkItemId, int splitDepth) {
        cancellationCheckpoint(taskId);
        List<String> evidence = input.units().stream().map(FunctionListExtractionInput.Unit::unitKey).toList();
        Map<String, String> targetEvidenceTexts = new LinkedHashMap<>();
        input.units().forEach(unit -> {
            if (targetEvidenceTexts.putIfAbsent(unit.unitKey(), unit.content()) != null) {
                throw new IllegalArgumentException("Function-list target unit keys must be unique");
            }
        });
        String identity = identity(taskId, "FEATURE_SCOPE_EXTRACT", input);
        RequirementScope extractionScope = request.requirementScope()
                .singleDocumentAuthorization(material.documentId());
        var registration = extractionRegistration(taskId, identity, input, evidence,
                materialDocumentId, parentWorkItemId, splitDepth);
        String workId = store.register(registration);
        if (store.isCompleted(workId)) return;
        boolean historical = materialDocumentId == null;
        List<FunctionListExtractionInput> children = input.units().size() < 2 ? List.of()
                : historical ? historicalExtractionChildren(input)
                : materialPlanner.bisect(material.units(), materialPlanner.restoreWindow(
                                material.units(), evidence, input.contextUnits().stream()
                                        .map(FunctionListExtractionInput.Unit::unitKey).toList()))
                        .stream().map(window -> materialPlanner.extractionInput(
                                input.materialKey(), input.sourceLabel(), window)).toList();
        if (store.isSplit(workId)) {
            children.forEach(child -> executeLegacyExtractionWindow(taskId, request, sessionId, registry, material, child,
                    materialDocumentId, historical ? null : workId, historical ? 0 : splitDepth + 1));
            return;
        }
        if (historical && input.units().size() > 16) {
            store.splitQueuedHistoricalFunctionListExtractionWork(workId,
                    extractionRegistration(taskId,
                            identity(taskId, "FEATURE_SCOPE_EXTRACT", children.get(0)), children.get(0),
                            children.get(0).units().stream().map(FunctionListExtractionInput.Unit::unitKey).toList(),
                            null, null, 0),
                    extractionRegistration(taskId,
                            identity(taskId, "FEATURE_SCOPE_EXTRACT", children.get(1)), children.get(1),
                            children.get(1).units().stream().map(FunctionListExtractionInput.Unit::unitKey).toList(),
                            null, null, 0));
            children.forEach(child -> executeLegacyExtractionWindow(taskId, request, sessionId, registry, material, child,
                    null, null, 0));
            return;
        }
        executeRegisteredWork(registration, workId, claim -> {
            FunctionListExtractionValidator.Result mapped = StructuredSkillResultMapper.extraction(
                    skills.extractFunctionList(new FunctionListExtractionInvocation(
                            sessionId, request.agentId(), extractionScope, input)).data().result());
            return extractionValidator.mergeSlices(extractionValidator.validate(
                    new FunctionListExtractionValidator.WorkItem(
                            registry, input.materialKey(), evidence, targetEvidenceTexts), mapped));
        }, (claim, validated) -> store.acceptFunctionListItems(claim, registry, validated.stream().map(row ->
                        new StructuredGenerationAcceptanceStore.FunctionListItem(
                                row.itemKey(), row.path(), row.description(),
                                row.evidenceKeys(), row.targetQuotes())).toList()),
                (claim, failure) -> {
                    if (!isResponseTooLarge(failure) || children.isEmpty()) return false;
                    cancellationCheckpoint(taskId);
                    store.splitFunctionListExtractionWork(claim,
                            extractionRegistration(taskId,
                                    identity(taskId, "FEATURE_SCOPE_EXTRACT", children.get(0)), children.get(0),
                                    children.get(0).units().stream().map(FunctionListExtractionInput.Unit::unitKey).toList(),
                                    materialDocumentId, historical ? null : workId, historical ? 0 : splitDepth + 1),
                            extractionRegistration(taskId,
                                    identity(taskId, "FEATURE_SCOPE_EXTRACT", children.get(1)), children.get(1),
                                    children.get(1).units().stream().map(FunctionListExtractionInput.Unit::unitKey).toList(),
                                    materialDocumentId, historical ? null : workId, historical ? 0 : splitDepth + 1));
                    return true;
                });
        if (store.isSplit(workId)) {
            children.forEach(child -> executeLegacyExtractionWindow(taskId, request, sessionId, registry, material, child,
                    materialDocumentId, historical ? null : workId, historical ? 0 : splitDepth + 1));
        }
    }

    private static List<FunctionListExtractionInput> historicalExtractionChildren(
            FunctionListExtractionInput parent) {
        int middle = parent.units().size() / 2;
        return List.of(
                new FunctionListExtractionInput(parent.materialKey(), parent.sourceLabel(),
                        parent.units().subList(0, middle)),
                new FunctionListExtractionInput(parent.materialKey(), parent.sourceLabel(),
                        parent.units().subList(middle, parent.units().size())));
    }

    private static StructuredGenerationAcceptanceStore.WorkRegistration extractionRegistration(
            String taskId, String identity, FunctionListExtractionInput input, List<String> evidence,
            String materialDocumentId, String parentWorkItemId, int splitDepth) {
        return new StructuredGenerationAcceptanceStore.WorkRegistration(taskId, identity,
                "feature-scope-reconciliation", "FEATURE_SCOPE_EXTRACT",
                input.units().get(0).ordinal(), input.units().get(input.units().size() - 1).ordinal(),
                input.materialKey(), input.sourceLabel(), evidence, null, null, materialDocumentId,
                input.contextUnits().stream().map(FunctionListExtractionInput.Unit::unitKey).toList(),
                parentWorkItemId, splitDepth);
    }

    private void executeReconciliation(String taskId, CreateGenerationTaskRequest request, String sessionId,
            StructuredValidationRegistry registry, StructuredGenerationAcceptanceStore.AcceptedInputs accepted) {
        cancellationCheckpoint(taskId);
        FeatureScopeReconciliationInput input = new FeatureScopeReconciliationInput(
                accepted.functionItems().stream().map(item -> new FeatureScopeReconciliationInput.FunctionListItem(
                        item.itemKey(), item.path(), item.description(), item.evidenceKeys())).toList(),
                accepted.facts().stream().map(fact -> new FeatureScopeReconciliationInput.RequirementFact(
                        fact.factKey(), fact.function(), fact.evidenceKeys())).toList());
        List<String> evidence = distinctEvidence(accepted);
        String identity = identity(taskId, "FEATURE_SCOPE_RECONCILIATION", input);
        var registration = new StructuredGenerationAcceptanceStore.WorkRegistration(taskId, identity,
                "feature-scope-reconciliation", "FEATURE_SCOPE_RECONCILIATION", null, null,
                null, "功能范围双向核对", evidence, null, null);
        executeWork(registration, claim -> {
            FeatureReconciliationValidator.Result mapped = StructuredSkillResultMapper.reconciliation(skills.reconcileFeatureScope(
                    new FeatureScopeReconciliationInvocation(sessionId, request.agentId(), request.requirementScope(), input)).data().result());
            return namespaceReconciliation(taskId, identity, mapped);
        }, (claim, namespaced) -> store.acceptReconciliation(claim, reconciliationValidator,
                new FeatureReconciliationValidator.WorkItem(
                    registry, input.functionListItems().stream().map(FeatureScopeReconciliationInput.FunctionListItem::itemKey).toList(),
                    input.requirementFacts().stream().map(FeatureScopeReconciliationInput.RequirementFact::factKey).toList(),
                    evidenceByFunctionListItem(input), evidenceByRequirementFact(input)),
                    namespaced));
    }

    private void executeTestcases(String taskId, CreateGenerationTaskRequest request, String sessionId,
            StructuredValidationRegistry registry,
            List<StructuredGenerationAcceptanceStore.AcceptedConfirmedFunction> confirmedFunctions) {
        for (StructuredGenerationAcceptanceStore.AcceptedConfirmedFunction confirmed : confirmedFunctions) {
            cancellationCheckpoint(taskId);
            String functionKey = taskKey("function", taskId, confirmed.reconciliationKey());
            String functionName = confirmedFunctionName(confirmed);
            registry.register(StructuredKeyType.FUNCTION, functionKey);
            var definition = new StructuredTestPointPlanner.FunctionDefinition(functionKey, functionName,
                    confirmed.facts().stream().map(DefaultStructuredAllGenerationCoordinator::formalFact).toList(), List.of());
            for (FunctionalTestcaseDesignInput planned : testPointPlanner.plan(definition)) {
                cancellationCheckpoint(taskId);
                FunctionalTestcaseDesignInput input = attachFormalSupports(confirmed, planned);
                registry.register(StructuredKeyType.TEST_POINT, input.testPoint().testPointKey());
                List<String> evidence = input.testPoint().evidenceKeys();
                String identity = identity(taskId, "FUNCTIONAL_TESTCASE_DESIGN", input);
                var registration = new StructuredGenerationAcceptanceStore.WorkRegistration(taskId, identity,
                        "functional-testcase-design", "FUNCTIONAL_TESTCASE_DESIGN", null, null,
                        null, input.functionName() + " / " + input.testPoint().description(), evidence,
                        input.functionKey(), input.testPoint().testPointKey());
                executeWork(registration, claim -> {
                    FunctionalTestcaseResultValidator.Result mapped = StructuredSkillResultMapper.testcases(skills.designFunctionalTestcases(
                            new FunctionalTestcaseDesignInvocation(sessionId, request.agentId(), request.requirementScope(), input)).data().result());
                    return namespaceTestcases(taskId, identity, mapped);
                }, (claim, namespaced) -> store.acceptTestcases(claim, testcaseValidator,
                        new FunctionalTestcaseResultValidator.WorkItem(
                            registry, input.functionKey(), input.functionName(), input.testPoint().testPointKey(), input.testPoint().description(),
                            FunctionalTestcaseResultValidator.TestPointType.valueOf(input.testPoint().type().name()),
                            FunctionalTestcaseResultValidator.Basis.valueOf(input.testPoint().basis().name()),
                            input.testPoint().requirementFactKeys(), evidence, input.testPoint().missingInformation(),
                            formalSupports(confirmed, input.testPoint().requirementFactKeys())), namespaced));
            }
        }
    }

    private static String confirmedFunctionName(
            StructuredGenerationAcceptanceStore.AcceptedConfirmedFunction confirmed) {
        List<String> itemPaths = confirmed.functionItems().stream().map(
                StructuredGenerationAcceptanceStore.AcceptedFunctionItem::path).distinct().sorted().toList();
        if (!itemPaths.isEmpty()) return String.join(" / ", itemPaths);
        List<String> factFunctions = confirmed.facts().stream().map(
                StructuredGenerationAcceptanceStore.AcceptedFact::function).distinct().sorted().toList();
        if (factFunctions.isEmpty()) throw new IllegalStateException("Confirmed function has no reader-facing identity");
        return String.join(" / ", factFunctions);
    }

    /**
     * Executes one durable V2 run whose pages share the complete comparison catalog.
     *
     * <p>Page staging is deliberately not business acceptance. A process restart resumes only
     * missing owner windows; relations become visible only after the validator proves global
     * closure and the store publishes all relations, bindings, and source terminals atomically.</p>
     *
     * [Req-ID]: REQ-FSC-008
     */
    private void executeReconciliationV2(String taskId, CreateGenerationTaskRequest request, String sessionId,
            StructuredGenerationAcceptanceStore.AcceptedInputs accepted) {
        cancellationCheckpoint(taskId);
        StructuredReconciliationV2Planner.RunPlan plan;
        try {
            plan = reconciliationV2Planner.plan(taskId, accepted);
        } catch (IllegalArgumentException exception) {
            throw validationFailure(StructuredValidationFailure.Code.RECONCILIATION_V2_PLANNING_INVALID,
                    "$.reconciliation_run");
        }
        // Read evidence directly from the frozen catalog rows. Looking each source up again would
        // make a large (1000+ page) reconciliation plan quadratic before the first KEE call.
        List<String> evidence = java.util.stream.Stream.concat(
                        plan.catalog().functionListItems().stream().flatMap(item -> item.evidenceKeys().stream()),
                        plan.catalog().requirementFacts().stream().flatMap(fact -> fact.evidenceKeys().stream()))
                .distinct()
                .sorted(com.testcaseagent.knowledgeagent.FeatureScopeReconciliationV2Canonicalizer.utf8Order())
                .toList();
        var registration = new StructuredGenerationAcceptanceStore.WorkRegistration(
                taskId, plan.runKey(), "feature-scope-reconciliation", "FEATURE_SCOPE_RECONCILIATION_V2",
                null, null, null, "功能范围全量核对", evidence, null, null);
        String workId = store.register(registration);
        if (store.isCompleted(workId)) return;

        while (true) {
            cancellationCheckpoint(taskId);
            StructuredGenerationAcceptanceStore.WorkClaim claim = store.claimRegistered(taskId, workId, OWNER)
                    .orElseThrow(() -> new IllegalStateException("Registered V2 reconciliation work could not be claimed"));
            try (StructuredWorkLeaseHeartbeat.ActiveLease activeLease = leaseHeartbeat.start(
                    claim, () -> repository.isCancellationRequested(taskId))) {
                store.initializeReconciliationRun(claim, new StructuredGenerationAcceptanceStore.ReconciliationRunPlan(
                        storeRun(plan), plan.ownerWindows().stream()
                                .map(DefaultStructuredAllGenerationCoordinator::storeWindow).toList()));
                processReconciliationPages(taskId, request, sessionId, claim, activeLease, plan);
                activeLease.requireActive();
                cancellationCheckpoint(taskId);
                var publication = reconciliationV2Validator.validateRun(plan,
                        store.stagedReconciliationPages(workId, plan.runKey(), plan.catalogSha256()));
                store.publishReconciliationRun(claim, publication);
                return;
            } catch (CancellationException exception) {
                store.fail(claim, "model_execution_failed");
                throw exception;
            } catch (StructuredWorkLeaseLostException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                RuntimeException safe = exception;
                if (exception instanceof IllegalArgumentException
                        && !(exception instanceof StructuredValidationException)) {
                    safe = validationFailure(StructuredValidationFailure.Code.RECONCILIATION_V2_RESULT_INVALID,
                            "$.reconciliation_run");
                }
                String failureType = failureType(safe);
                if (safe instanceof StructuredValidationException validationException) {
                    store.fail(claim, failureType, validationException.failure());
                    WorkflowDiagnostics.structuredValidationFailure(taskId, claim.workItemId(), claim.attemptId(),
                            claim.attemptNumber(), validationException.failure());
                } else {
                    store.fail(claim, failureType);
                }
                if (isTransient(failureType)
                        && claim.attemptNumber() < StructuredGenerationAcceptanceStore.MAX_ATTEMPTS) continue;
                throw safe;
            }
        }
    }

    private void processReconciliationPages(String taskId, CreateGenerationTaskRequest request, String sessionId,
            StructuredGenerationAcceptanceStore.WorkClaim claim,
            StructuredWorkLeaseHeartbeat.ActiveLease activeLease,
            StructuredReconciliationV2Planner.RunPlan plan) {
        while (true) {
            cancellationCheckpoint(taskId);
            List<StructuredGenerationAcceptanceStore.ReconciliationOwnerWindow> pending =
                    store.pendingReconciliationPages(claim.workItemId(), plan.runKey(), plan.catalogSha256());
            if (pending.isEmpty()) return;
            FeatureScopeReconciliationPageInput.OwnerWindow window = wireWindow(pending.get(0));
            FeatureScopeReconciliationPageResult result;
            try {
                result = skills.reconcileFeatureScopePage(new FeatureScopeReconciliationPageInvocation(
                        sessionId, request.agentId(), request.requirementScope(), plan.input(window))).data().result();
            } catch (StructuredSkillExecutionException exception) {
                if (exception.type() != StructuredSkillErrorType.RESPONSE_TOO_LARGE
                        || window.ownerSourceRefs().size() < 2) throw exception;
                activeLease.requireActive();
                List<FeatureScopeReconciliationPageInput.OwnerWindow> children =
                        reconciliationV2Planner.bisect(plan, window);
                store.splitReconciliationPage(claim, storeRun(plan), window.pageKey(),
                        storeWindow(children.get(0)), storeWindow(children.get(1)));
                continue;
            }
            activeLease.requireActive();
            cancellationCheckpoint(taskId);
            try {
                store.stageReconciliationPage(claim, reconciliationV2Validator.validatePage(plan, window, result));
            } catch (IllegalArgumentException exception) {
                throw validationFailure(StructuredValidationFailure.Code.RECONCILIATION_V2_RESULT_INVALID,
                        "$.reconciliation_page");
            }
        }
    }

    private static FunctionalTestcaseDesignInput attachFormalSupports(
            StructuredGenerationAcceptanceStore.AcceptedConfirmedFunction confirmed,
            FunctionalTestcaseDesignInput planned) {
        if (planned.testPoint().basis() == FunctionalTestcaseDesignInput.Basis.GENERAL_EXPERIENCE) {
            return new FunctionalTestcaseDesignInput(
                    planned.functionKey(), planned.functionName(), planned.testPoint(), List.of());
        }
        Map<String, StructuredGenerationAcceptanceStore.AcceptedFact> factsByKey = new LinkedHashMap<>();
        for (StructuredGenerationAcceptanceStore.AcceptedFact fact : confirmed.facts()) {
            if (factsByKey.put(fact.factKey(), fact) != null) {
                throw new IllegalStateException("Persisted confirmed mapping contains duplicate fact keys");
            }
        }
        Set<String> resolvedEvidenceKeys = new LinkedHashSet<>();
        List<FormalSupport> supports = new ArrayList<>();
        for (String factKey : planned.testPoint().requirementFactKeys()) {
            StructuredGenerationAcceptanceStore.AcceptedFact fact = factsByKey.get(factKey);
            if (fact == null) {
                throw new IllegalStateException("Test point references a fact outside the persisted confirmed mapping");
            }
            List<String> supportEvidenceKeys = new ArrayList<>();
            List<String> evidenceTexts = new ArrayList<>();
            for (String evidenceKey : planned.testPoint().evidenceKeys()) {
                String evidenceText = fact.evidenceTexts().get(evidenceKey);
                if (evidenceText != null) {
                    resolvedEvidenceKeys.add(evidenceKey);
                    supportEvidenceKeys.add(evidenceKey);
                    evidenceTexts.add(evidenceText);
                }
            }
            if (evidenceTexts.isEmpty()) {
                throw new IllegalStateException("Formal fact has no evidence text in the current test-point closure");
            }
            supports.add(new FormalSupport(fact.factKey(), fact.function(), fact.roles(), fact.triggerConditions(),
                    fact.inputs(), fact.businessRules(), fact.outputs(), fact.permissions(), fact.stateChanges(),
                    fact.exceptionHandling(), fact.externalDependencies(), List.copyOf(supportEvidenceKeys),
                    List.copyOf(evidenceTexts)));
        }
        if (!resolvedEvidenceKeys.equals(new LinkedHashSet<>(planned.testPoint().evidenceKeys()))) {
            throw new IllegalStateException("Test point evidence is outside the persisted formal support closure");
        }
        return new FunctionalTestcaseDesignInput(
                planned.functionKey(), planned.functionName(), planned.testPoint(), supports);
    }

    private void complete(String taskId, boolean reconciled, boolean noFunctionItems, boolean candidateProtocol) {
        cancellationCheckpoint(taskId);
        repository.transitionTask(taskId, GenerationTaskStatus.GENERATING);
        cancellationCheckpoint(taskId);
        repository.transitionTask(taskId, GenerationTaskStatus.VALIDATING);
        cancellationCheckpoint(taskId);
        StructuredGenerationAcceptanceStore.AggregateState aggregate = store.aggregateState(taskId);
        if (candidateProtocol && (aggregate.acceptedFunctionCandidateCount() == 0
                || aggregate.coveredFormalPointCount() == 0)) {
            var outcome = completionGate.evaluate(completionSnapshot(
                    aggregate, reconciled, noFunctionItems, false, candidateProtocol));
            store.updateTaskState(taskId, new StructuredGenerationAcceptanceStore.StructuredTaskState(
                    outcome.processingStatus(), outcome.coverageStatus()));
            repository.failStructuredTask(taskId, outcome.coverageStatus());
            return;
        }
        StructuredWorkbookExportRequest rows = repository.structuredWorkbookRequest(taskId);
        cancellationCheckpoint(taskId);
        WorkbookArtifact artifact = exporter.exportStructured(rows);
        cancellationCheckpoint(taskId);
        var outcome = completionGate.evaluate(completionSnapshot(
                aggregate, reconciled, noFunctionItems, true, candidateProtocol));
        store.updateTaskState(taskId, new StructuredGenerationAcceptanceStore.StructuredTaskState(
                outcome.processingStatus(), outcome.coverageStatus()));
        if (!outcome.artifactPublishable()) {
            repository.failStructuredTask(taskId, outcome.coverageStatus());
            return;
        }
        repository.completeStructuredTask(taskId, artifact, outcome.processingStatus(), outcome.coverageStatus(),
                candidateProtocol);
    }

    private static StructuredCompletionGate.Snapshot completionSnapshot(
            StructuredGenerationAcceptanceStore.AggregateState aggregate, boolean reconciled,
            boolean noFunctionItems, boolean artifactValidated, boolean candidateProtocol) {
        return new StructuredCompletionGate.Snapshot(true,
                aggregate.totalReviewWork(), aggregate.completedReviewWork(), reconciled || noFunctionItems, true,
                aggregate.formalPointTotal(), aggregate.coveredFormalPointCount(), aggregate.pendingCandidateCount(),
                artifactValidated, aggregate.acceptedWorkCount(), aggregate.failedWorkCount(),
                aggregate.allWorkTerminal(), candidateProtocol, aggregate.acceptedFunctionCandidateCount(),
                aggregate.incompleteFunctionScopeCount(), aggregate.failedFunctionCandidateWorkCount(), false);
    }

    private void cancellationCheckpoint(String taskId) {
        if (repository.isCancellationRequested(taskId)) throw new CancellationException("Structured task was cancelled");
    }

    private <T> T executeWork(StructuredGenerationAcceptanceStore.WorkRegistration registration,
            Function<StructuredGenerationAcceptanceStore.WorkClaim, T> invocation,
            BiConsumer<StructuredGenerationAcceptanceStore.WorkClaim, T> acceptance) {
        String workId = store.register(registration);
        if (store.isCompleted(workId)) return null;
        return executeRegisteredWork(registration, workId, invocation, acceptance, (claim, failure) -> false);
    }

    private <T> T executeRegisteredWork(StructuredGenerationAcceptanceStore.WorkRegistration registration,
            String workId, Function<StructuredGenerationAcceptanceStore.WorkClaim, T> invocation,
            BiConsumer<StructuredGenerationAcceptanceStore.WorkClaim, T> acceptance,
            BiPredicate<StructuredGenerationAcceptanceStore.WorkClaim, RuntimeException> recovery) {
        while (true) {
            cancellationCheckpoint(registration.taskId());
            StructuredGenerationAcceptanceStore.WorkClaim claim = store.claimRegistered(
                    registration.taskId(), workId, OWNER)
                    .orElseThrow(() -> new IllegalStateException("Registered structured work could not be claimed"));
            try (StructuredWorkLeaseHeartbeat.ActiveLease activeLease = leaseHeartbeat.start(
                    claim, () -> repository.isCancellationRequested(registration.taskId()))) {
                T result = invocation.apply(claim);
                activeLease.requireActive();
                cancellationCheckpoint(registration.taskId());
                acceptance.accept(claim, result);
                return result;
            } catch (CancellationException exception) {
                store.fail(claim, "model_execution_failed");
                throw exception;
            } catch (StructuredWorkLeaseLostException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                RuntimeException failure = exception;
                try {
                    if (recovery.test(claim, exception)) return null;
                } catch (RuntimeException recoveryFailure) {
                    // A failed split transaction leaves the claim running; route it through the same durable
                    // failure boundary instead of abandoning it until lease expiry.
                    failure = recoveryFailure;
                }
                String failureType = failureType(failure);
                if (failure instanceof StructuredValidationException validationException) {
                    var validationFailure = validationException.failure();
                    store.fail(claim, failureType, validationFailure);
                    WorkflowDiagnostics.structuredValidationFailure(registration.taskId(), claim.workItemId(),
                            claim.attemptId(), claim.attemptNumber(), validationFailure);
                } else {
                    store.fail(claim, failureType);
                }
                if (isTransient(failureType)
                        && claim.attemptNumber() < StructuredGenerationAcceptanceStore.MAX_ATTEMPTS) continue;
                throw failure;
            }
        }
    }

    private static boolean isTransient(String failureType) {
        return "model_unavailable".equals(failureType) || "model_execution_failed".equals(failureType);
    }

    private StructuredValidationRegistry inventoryRegistry(String taskId, List<MaterialInventoryDocument> materials) {
        StructuredValidationRegistry registry = StructuredValidationRegistry.forTask(taskId);
        for (MaterialInventoryDocument material : materials) {
            registry.register(StructuredKeyType.MATERIAL, material.documentId());
            for (MaterialInventoryUnit unit : material.units()) registry.registerEvidence(new StructuredEvidence(
                    unit.unitId(), taskId, material.documentId(), false, false, material.complete()));
        }
        return registry;
    }

    private static MaterialContentTypeKey contentType(String role) {
        return switch (role) {
            case "REQUIREMENT" -> MaterialContentTypeKey.REQUIREMENTS_SPEC;
            case "WORK_ORDER_PLAN" -> MaterialContentTypeKey.WORK_ORDER_PLAN;
            case "PROTOTYPE" -> MaterialContentTypeKey.PROTOTYPE;
            case "REQUIREMENT_LIST" -> MaterialContentTypeKey.REQUIREMENT_LIST;
            default -> throw new IllegalArgumentException("Unsupported structured material role");
        };
    }

    private static String sourceLabel(MaterialInventoryDocument material) {
        return switch (material.documentRole()) {
            case "REQUIREMENT" -> "需求规格说明";
            case "WORK_ORDER_PLAN" -> "工单方案";
            case "PROTOTYPE" -> "原型补充材料";
            case "REQUIREMENT_LIST" -> "需求清单补充材料";
            case "FUNCTION_LIST" -> "功能清单";
            default -> "需求材料";
        };
    }

    private RequirementMaterialReviewValidator.Result namespaceReview(String taskId, String identity,
            RequirementMaterialReviewValidator.Result result) {
        return new RequirementMaterialReviewValidator.Result(result.requirementFacts().stream().map(fact ->
                new RequirementMaterialReviewValidator.RequirementFact(taskKey("fact", taskId, identity, fact.factKey()),
                        fact.function(), fact.roles(), fact.triggerConditions(), fact.inputs(), fact.businessRules(), fact.outputs(),
                        fact.permissions(), fact.stateChanges(), fact.exceptionHandling(), fact.externalDependencies(), fact.evidenceKeys())).toList(),
                result.reviewFindings().stream().map(finding -> new RequirementMaterialReviewValidator.ReviewFinding(
                        taskKey("finding", taskId, identity, finding.findingKey()), finding.rootCauseKind(),
                        finding.issueType(), finding.affectedScope(), finding.badSourceExample(), finding.proposedGoodExample(),
                        finding.description(), finding.evidenceKeys(), finding.testDesignImpact(),
                        finding.currentProjectRecommendation(), finding.designCenterGuidelineRecommendation(),
                        finding.handlingLevel())).toList());
    }

    private FeatureReconciliationValidator.Result namespaceReconciliation(String taskId, String identity,
            FeatureReconciliationValidator.Result result) {
        return new FeatureReconciliationValidator.Result(result.reconciliations().stream().map(row ->
                new FeatureReconciliationValidator.Reconciliation(taskKey("reconciliation", taskId, identity, row.reconciliationKey()),
                        row.functionListItemKeys(), row.requirementFactKeys(), row.classification(), row.evidenceKeys(),
                        row.scopeRecommendation(), row.confirmationStatus())).toList());
    }

    private FunctionalTestcaseResultValidator.Result namespaceTestcases(String taskId, String identity,
            FunctionalTestcaseResultValidator.Result result) {
        return new FunctionalTestcaseResultValidator.Result(result.functionKey(), result.testPointKey(), result.testcases().stream().map(row ->
                new FunctionalTestcaseResultValidator.Testcase(taskKey("case", taskId, identity, row.caseKey()),
                        row.name(), row.title(), row.priority(), row.preconditions(), row.initialization(), row.inputs(),
                        row.steps(), row.expectedResults(), row.evaluationCriteria(), row.resultEvaluationCriteria(),
                        row.terminationConditions(), row.resultCollection(), row.authoringInformation(),
                        row.requirementFactKeys(), row.evidenceKeys(), row.caseStatus(), row.missingInformation())).toList());
    }

    private static StructuredTestPointPlanner.FormalFact formalFact(StructuredGenerationAcceptanceStore.AcceptedFact fact) {
        return new StructuredTestPointPlanner.FormalFact(fact.factKey(), fact.function(), fact.inputs(), fact.businessRules(),
                fact.permissions(), fact.stateChanges(), fact.exceptionHandling(), fact.externalDependencies(), fact.evidenceKeys());
    }

    private static List<FunctionalTestcaseResultValidator.FormalSupport> formalSupports(
            StructuredGenerationAcceptanceStore.AcceptedConfirmedFunction confirmed, List<String> factKeys) {
        List<FunctionalTestcaseResultValidator.FormalSupport> supports = confirmed.facts().stream()
                .filter(fact -> factKeys.contains(fact.factKey()))
                .map(fact -> new FunctionalTestcaseResultValidator.FormalSupport(
                        fact.factKey(), fact.function(), fact.roles(), fact.triggerConditions(), fact.inputs(),
                        fact.businessRules(), fact.outputs(), fact.permissions(), fact.stateChanges(),
                        fact.exceptionHandling(), fact.externalDependencies(), fact.evidenceTexts()))
                .toList();
        if (supports.size() != factKeys.size()) {
            throw new IllegalStateException("Test point references a fact outside the persisted confirmed mapping");
        }
        return supports;
    }

    private static List<String> distinctEvidence(StructuredGenerationAcceptanceStore.AcceptedInputs accepted) {
        LinkedHashSet<String> evidence = new LinkedHashSet<>();
        accepted.functionItems().forEach(item -> evidence.addAll(item.evidenceKeys()));
        accepted.facts().forEach(fact -> evidence.addAll(fact.evidenceKeys()));
        return List.copyOf(evidence);
    }

    private static StructuredGenerationAcceptanceStore.ReconciliationRunIdentity storeRun(
            StructuredReconciliationV2Planner.RunPlan plan) {
        return new StructuredGenerationAcceptanceStore.ReconciliationRunIdentity(
                plan.runKey(), plan.catalogSha256(), plan.catalog().functionListItems().size(),
                plan.catalog().requirementFacts().size());
    }

    private static StructuredGenerationAcceptanceStore.ReconciliationOwnerWindow storeWindow(
            FeatureScopeReconciliationPageInput.OwnerWindow window) {
        return new StructuredGenerationAcceptanceStore.ReconciliationOwnerWindow(window.pageKey(),
                window.ownerSourceRefs().stream().map(ref ->
                        new StructuredGenerationAcceptanceStore.ReconciliationSourceRef(
                                ref.sourceType().wireValue(), ref.sourceKey())).toList());
    }

    private static FeatureScopeReconciliationPageInput.OwnerWindow wireWindow(
            StructuredGenerationAcceptanceStore.ReconciliationOwnerWindow window) {
        return new FeatureScopeReconciliationPageInput.OwnerWindow(window.pageKey(),
                window.ownerSourceRefs().stream().map(ref -> new FeatureScopeReconciliationPageInput.SourceRef(
                        FeatureScopeReconciliationPageInput.SourceType.fromWire(ref.sourceType()), ref.sourceKey()))
                        .toList());
    }

    private static StructuredValidationException validationFailure(
            StructuredValidationFailure.Code code, String path) {
        return new StructuredValidationException(StructuredValidationFailure.of(code, path));
    }

    private static Map<String, List<String>> evidenceByFunctionListItem(FeatureScopeReconciliationInput input) {
        Map<String, List<String>> evidence = new LinkedHashMap<>();
        for (FeatureScopeReconciliationInput.FunctionListItem item : input.functionListItems()) {
            evidence.put(item.itemKey(), item.evidenceKeys());
        }
        return Map.copyOf(evidence);
    }

    private static Map<String, List<String>> evidenceByRequirementFact(FeatureScopeReconciliationInput input) {
        Map<String, List<String>> evidence = new LinkedHashMap<>();
        for (FeatureScopeReconciliationInput.RequirementFact fact : input.requirementFacts()) {
            evidence.put(fact.factKey(), fact.evidenceKeys());
        }
        return Map.copyOf(evidence);
    }

    private String identity(String taskId, String operation, Object input) {
        try {
            return HexFormat.of().formatHex(LengthPrefixedSha256.digest(
                    taskId, operation, objectMapper.writeValueAsString(input)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Structured work identity cannot be serialized", exception);
        }
    }

    private static String taskKey(String prefix, String... fields) {
        return prefix + "-" + HexFormat.of().formatHex(LengthPrefixedSha256.digest(fields), 0, 16);
    }

    private static String failureType(RuntimeException exception) {
        if (exception instanceof StructuredSkillExecutionException structured) return structured.type().wireValue();
        return "business_validation_failed";
    }
}
