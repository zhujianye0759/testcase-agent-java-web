package com.testcaseagent.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testcaseagent.diagnostics.WorkflowDiagnostics;
import com.testcaseagent.export.StructuredWorkbookRowSource;
import com.testcaseagent.export.WorkbookArtifact;
import com.testcaseagent.export.WorkbookExporter;
import com.testcaseagent.featureaudit.MaterialInventoryUnit;
import com.testcaseagent.featureaudit.RequirementMaterialTraversalService;
import com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignV2Invocation;
import com.testcaseagent.knowledgeagent.RequirementFactExtractionV2Invocation;
import com.testcaseagent.knowledgeagent.StructuredSkillErrorType;
import com.testcaseagent.knowledgeagent.StructuredSkillExecutionException;
import com.testcaseagent.knowledgeagent.StructuredSkillExecutionPort;
import com.testcaseagent.knowledgeagent.StructuredSkillSessionPort;
import com.testcaseagent.scope.RequirementDocumentCoordinate;
import com.testcaseagent.scope.RequirementScope;
import com.testcaseagent.structuredgeneration.StructuredCoverageStatus;
import com.testcaseagent.structuredgeneration.StructuredGenerationAcceptanceStore;
import com.testcaseagent.structuredgeneration.StructuredProcessingStatus;
import com.testcaseagent.structuredgeneration.V2GenerationPlanner;
import com.testcaseagent.validation.FunctionalTestcaseV2Validator;
import com.testcaseagent.validation.RequirementFactV2Validator;
import com.testcaseagent.validation.StructuredValidationException;
import com.testcaseagent.validation.StructuredValidationFailure;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * Runs the version 2 fact-to-testcase workflow without invoking any retired material-review or reconciliation API.
 * Each window/test point is an independent transaction; a weak or failed scope becomes auditable pending coverage
 * while unrelated scopes continue.
 *
 * [Req-ID]: REQ-TGV2-002, REQ-TGV2-003, REQ-TGV2-005~REQ-TGV2-008
 */
public final class V2StructuredAllGenerationCoordinator implements StructuredAllGenerationCoordinator {
    private static final String OWNER = "structured-v2-worker";
    private static final int FACT_PAGE_SIZE = 100;
    private final GenerationTaskRepository repository;
    private final RequirementMaterialTraversalService traversal;
    private final StructuredSkillExecutionPort skills;
    private final StructuredSkillSessionPort sessions;
    private final StructuredGenerationAcceptanceStore store;
    private final WorkbookExporter exporter;
    private final StructuredWorkLeaseHeartbeat leaseHeartbeat;
    private final V2GenerationPlanner planner = new V2GenerationPlanner();
    private final RequirementFactV2Validator factValidator = new RequirementFactV2Validator();
    private final FunctionalTestcaseV2Validator testcaseValidator = new FunctionalTestcaseV2Validator();

    public V2StructuredAllGenerationCoordinator(GenerationTaskRepository repository,
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
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.leaseHeartbeat = Objects.requireNonNull(leaseHeartbeat, "leaseHeartbeat must not be null");
    }

    /** Executes only the frozen V2 scope and preserves every independently accepted sibling. */
    @Override
    public void execute(String taskId, CreateGenerationTaskRequest request) {
        if (!Objects.requireNonNull(request, "request must not be null").isV2()) {
            throw new IllegalArgumentException("V2 coordinator requires a V2 task snapshot");
        }
        StructuredCoordinatorFailure.Stage stage = StructuredCoordinatorFailure.Stage.TASK_START_STATE_RESUME;
        try {
            store.updateTaskState(taskId, new StructuredGenerationAcceptanceStore.StructuredTaskState(
                    StructuredProcessingStatus.RUNNING, StructuredCoverageStatus.PENDING));
            cancellationCheckpoint(taskId);
            List<ApprovedFunctionScope.ApprovedFunction> functions = repository.approvedFunctions(taskId);
            if (!functions.equals(request.approvedFunctionScope().functions())) {
                throw new IllegalStateException("Persisted approved function scope does not match the task snapshot");
            }
            stage = StructuredCoordinatorFailure.Stage.INVENTORY_RESUME_TRAVERSAL;
            if (!repository.hasCompleteMaterialInventory(taskId, request.requirementScope())) {
                traversal.traversePagedV2(taskId, request, false);
            }
            List<V2GenerationPlanner.MaterialDescriptor> formalMaterials =
                    repository.formalRequirementMaterials(taskId);
            if (formalMaterials.isEmpty()) {
                throw new IllegalStateException("V2 task has no admitted formal requirement material");
            }
            RequirementScope designScope = formalScope(request.requirementScope());
            stage = StructuredCoordinatorFailure.Stage.SESSION_OPEN;
            String sessionId = sessions.openStructuredSession();
            boolean anyTechnicalFailure = false;
            Map<String, Boolean> factStageComplete = new LinkedHashMap<>();
            functions.forEach(function -> factStageComplete.put(function.functionKey(), true));
            // One bounded persisted-unit neighborhood is reused across approved functions. This keeps heap usage
            // independent of a 1000-page material while preserving every function/window identity. [Req-ID]:
            // REQ-TGV2-003
            for (V2GenerationPlanner.MaterialDescriptor material : formalMaterials) {
                int targetStartOrdinal = material.firstOrdinal();
                while (targetStartOrdinal <= material.lastOrdinal()) {
                    List<MaterialInventoryUnit> neighborhood = repository.materialInventoryPlanningSlice(
                            taskId, material.documentId(), targetStartOrdinal,
                            material.firstOrdinal(), material.lastOrdinal());
                    Integer nextTargetStart = null;
                    for (ApprovedFunctionScope.ApprovedFunction function : functions) {
                        stage = StructuredCoordinatorFailure.Stage.REQUIREMENT_FACT_EXTRACTION;
                        V2GenerationPlanner.FactWindow window = planner.nextFactWindow(
                                taskId, function, material, neighborhood, targetStartOrdinal);
                        int plannedNext = window.registration().ordinalEnd() + 1;
                        if (nextTargetStart != null && nextTargetStart != plannedNext) {
                            throw new IllegalStateException("Fact-window ownership drifted across approved functions");
                        }
                        nextTargetStart = plannedNext;
                        boolean completed = processFactWindow(
                                taskId, request, sessionId, function, material, window);
                        factStageComplete.put(function.functionKey(),
                                Boolean.TRUE.equals(factStageComplete.get(function.functionKey())) && completed);
                    }
                    if (nextTargetStart == null || nextTargetStart <= targetStartOrdinal) {
                        throw new IllegalStateException("Approved function scope cannot advance material traversal");
                    }
                    targetStartOrdinal = nextTargetStart;
                }
            }
            for (ApprovedFunctionScope.ApprovedFunction function : functions) {
                boolean complete = Boolean.TRUE.equals(factStageComplete.get(function.functionKey()));
                anyTechnicalFailure |= !complete;
                stage = StructuredCoordinatorFailure.Stage.TESTCASE_DESIGN;
                boolean foundFact = false;
                String afterFactKey = "";
                List<V2GenerationPlanner.PersistedFact> factPage;
                do {
                    factPage = store.acceptedRequirementFactsV2Page(
                            taskId, function.functionKey(), afterFactKey, FACT_PAGE_SIZE);
                    for (V2GenerationPlanner.PersistedFact fact : factPage) {
                        foundFact = true;
                        boolean completed = processTestPoint(taskId, request, designScope, sessionId,
                                planner.testPoint(taskId, function, fact));
                        anyTechnicalFailure |= !completed;
                    }
                    if (!factPage.isEmpty()) afterFactKey = factPage.get(factPage.size() - 1).factKey();
                } while (factPage.size() == FACT_PAGE_SIZE);
                if (!foundFact) {
                    anyTechnicalFailure |= !processTestPoint(taskId, request, designScope, sessionId,
                            planner.missingFormalFactTestPoint(taskId, function));
                } else if (!complete) {
                    anyTechnicalFailure |= !processTestPoint(taskId, request, designScope, sessionId,
                            planner.incompleteFactWindowsTestPoint(taskId, function));
                }
            }
            stage = StructuredCoordinatorFailure.Stage.ARTIFACT_EXPORT;
            complete(taskId, anyTechnicalFailure);
        } catch (CancellationException exception) {
            repository.cancelStructuredTask(taskId, StructuredCoverageStatus.PENDING);
        } catch (RuntimeException exception) {
            StructuredValidationFailure failure = StructuredCoordinatorFailure.from(stage, exception);
            repository.failStructuredTask(taskId, StructuredCoverageStatus.PENDING, failure);
            WorkflowDiagnostics.structuredCoordinatorFailure(taskId, failure);
            if (!(exception instanceof StructuredSkillExecutionException)
                    && !(exception instanceof StructuredWorkLeaseLostException)) {
                throw new StructuredValidationException(failure);
            }
            throw exception;
        }
    }

    private boolean processFactWindow(String taskId, CreateGenerationTaskRequest request, String sessionId,
            ApprovedFunctionScope.ApprovedFunction function, V2GenerationPlanner.MaterialDescriptor material,
            V2GenerationPlanner.FactWindow window) {
        String workId = store.register(window.registration());
        if (store.isCompleted(workId)) return true;
        if (store.isSplit(workId)) {
            return processFactChildren(taskId, request, sessionId, function, material, window, workId);
        }
        while (true) {
            cancellationCheckpoint(taskId);
            var claim = store.claimRegistered(taskId, workId, OWNER);
            if (claim.isEmpty()) return false;
            try (StructuredWorkLeaseHeartbeat.ActiveLease active = leaseHeartbeat.start(
                    claim.get(), () -> repository.isCancellationRequested(taskId))) {
                var result = skills.extractRequirementFactsV2(new RequirementFactExtractionV2Invocation(
                        sessionId, request.agentId(),
                        request.requirementScope().singleDocumentAuthorization(material.documentId()),
                        window.input())).data().result();
                active.requireActive();
                cancellationCheckpoint(taskId);
                store.acceptRequirementFactsV2(claim.get(), factValidator, window.input(), result);
                return true;
            } catch (CancellationException exception) {
                store.fail(claim.get(), "model_execution_failed");
                throw exception;
            } catch (StructuredWorkLeaseLostException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                if (Thread.currentThread().isInterrupted()) {
                    // A host shutdown or worker interruption is not a model failure and must not consume its budget.
                    store.fail(claim.get(), "worker_interrupted");
                    throw exception;
                }
                if (exception instanceof StructuredSkillExecutionException skillFailure
                        && skillFailure.type() == StructuredSkillErrorType.RESPONSE_TOO_LARGE
                        && window.targetUnits().size() > 1) {
                    List<V2GenerationPlanner.FactWindow> children = planner.bisectFactWindow(taskId, function,
                            material, window, workId);
                    store.splitRequirementFactWork(claim.get(), children.get(0).registration(),
                            children.get(1).registration());
                    return processFactWindow(taskId, request, sessionId, function, material, children.get(0))
                            & processFactWindow(taskId, request, sessionId, function, material, children.get(1));
                }
                fail(claim.get(), exception, StructuredCoordinatorFailure.Stage.REQUIREMENT_FACT_EXTRACTION);
                if (isTransient(exception)
                        && store.hasRemainingModelAttemptBudget(claim.get().workItemId())) {
                    continue;
                }
                return false;
            }
        }
    }

    private boolean processFactChildren(String taskId, CreateGenerationTaskRequest request, String sessionId,
            ApprovedFunctionScope.ApprovedFunction function, V2GenerationPlanner.MaterialDescriptor material,
            V2GenerationPlanner.FactWindow parent, String parentWorkId) {
        List<V2GenerationPlanner.FactWindow> children = planner.bisectFactWindow(
                taskId, function, material, parent, parentWorkId);
        return processFactWindow(taskId, request, sessionId, function, material, children.get(0))
                & processFactWindow(taskId, request, sessionId, function, material, children.get(1));
    }

    private boolean processTestPoint(String taskId, CreateGenerationTaskRequest request,
            RequirementScope designScope, String sessionId, V2GenerationPlanner.TestPointPlan point) {
        boolean noFactFallback = point.input().requirementFacts().isEmpty()
                && V2GenerationPlanner.missingFormalFactPointKey(taskId, point.input().functionKey())
                        .equals(point.input().testPoint().testPointKey());
        String workId = noFactFallback
                ? store.registerMissingFactFallback(point)
                : store.register(point.registration());
        if (store.isCompleted(workId)) return true;
        while (true) {
            cancellationCheckpoint(taskId);
            var claim = store.claimRegistered(taskId, workId, OWNER);
            if (claim.isEmpty()) return false;
            try (StructuredWorkLeaseHeartbeat.ActiveLease active = leaseHeartbeat.start(
                    claim.get(), () -> repository.isCancellationRequested(taskId))) {
                var result = skills.designFunctionalTestcasesV2(new FunctionalTestcaseDesignV2Invocation(
                        sessionId, request.agentId(), designScope, point.input())).data().result();
                active.requireActive();
                cancellationCheckpoint(taskId);
                store.acceptTestcasesV2(claim.get(), testcaseValidator, point.input(), result);
                return true;
            } catch (CancellationException exception) {
                store.fail(claim.get(), "model_execution_failed");
                throw exception;
            } catch (StructuredWorkLeaseLostException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                if (Thread.currentThread().isInterrupted()) {
                    // Preserve the durable recovery signal instead of silently retrying a request the host stopped.
                    store.fail(claim.get(), "worker_interrupted");
                    throw exception;
                }
                fail(claim.get(), exception, StructuredCoordinatorFailure.Stage.TESTCASE_DESIGN);
                if (isTransient(exception)
                        && store.hasRemainingModelAttemptBudget(claim.get().workItemId())) {
                    continue;
                }
                return false;
            }
        }
    }

    private void complete(String taskId, boolean observedTechnicalFailure) {
        cancellationCheckpoint(taskId);
        repository.transitionTask(taskId, GenerationTaskStatus.GENERATING);
        repository.transitionTask(taskId, GenerationTaskStatus.VALIDATING);
        StructuredGenerationAcceptanceStore.V2AggregateState aggregate = store.v2AggregateState(taskId);
        if (!aggregate.allWorkTerminal()) {
            throw new IllegalStateException("V2 task cannot finalize while work remains nonterminal");
        }
        StructuredProcessingStatus processing = observedTechnicalFailure || aggregate.failedWork() > 0
                ? StructuredProcessingStatus.FAILED : StructuredProcessingStatus.COMPLETED;
        StructuredCoverageStatus coverage = coverage(aggregate);
        StructuredWorkbookRowSource rows = repository.structuredWorkbookRows(taskId);
        WorkbookArtifact artifact = exporter.exportStructuredRows(rows);
        // Task axes and artifact identity must become visible together; the repository owns that single transaction.
        repository.completeV2StructuredTask(taskId, artifact, processing, coverage);
    }

    private static StructuredCoverageStatus coverage(
            StructuredGenerationAcceptanceStore.V2AggregateState aggregate) {
        if (aggregate.formalCaseCount() == 0) return StructuredCoverageStatus.UNABLE_TO_GENERATE;
        boolean complete = aggregate.failedWork() == 0 && aggregate.pendingWork() == 0
                && aggregate.testPointTotal() > 0
                && aggregate.formalCoveredPointCount() == aggregate.testPointTotal()
                && aggregate.pendingCaseCount() == 0 && aggregate.unableOutcomeCount() == 0;
        return complete ? StructuredCoverageStatus.COMPLETE : StructuredCoverageStatus.PARTIAL;
    }

    private void fail(StructuredGenerationAcceptanceStore.WorkClaim claim, RuntimeException exception,
            StructuredCoordinatorFailure.Stage stage) {
        if (exception instanceof StructuredValidationException validation) {
            store.fail(claim, "business_validation_failed", validation.failure());
            WorkflowDiagnostics.structuredValidationFailure(claim.taskId(), claim.workItemId(), claim.attemptId(),
                    claim.attemptNumber(), validation.failure());
            return;
        }
        if (exception instanceof StructuredSkillExecutionException) {
            store.fail(claim, failureType(exception));
            return;
        }
        StructuredValidationFailure failure = StructuredCoordinatorFailure.from(stage, exception);
        store.fail(claim, "coordinator_execution_failed", failure);
        WorkflowDiagnostics.structuredValidationFailure(claim.taskId(), claim.workItemId(), claim.attemptId(),
                claim.attemptNumber(), failure);
    }

    private static boolean isTransient(RuntimeException exception) {
        String type = failureType(exception);
        return "model_unavailable".equals(type) || "model_execution_failed".equals(type);
    }

    private static String failureType(RuntimeException exception) {
        return exception instanceof StructuredSkillExecutionException structured
                ? structured.type().wireValue() : "business_validation_failed";
    }

    private static RequirementScope formalScope(RequirementScope scope) {
        List<RequirementDocumentCoordinate> documents = scope.documents().stream()
                .filter(document -> "requirements_spec".equals(document.materialTypeKey())
                        || "work_order_plan".equals(document.materialTypeKey()))
                .toList();
        return new RequirementScope(scope.knowledgeBaseId(), scope.systemId(), scope.versionId(),
                scope.materialCategory(), scope.projectId(), documents);
    }

    private void cancellationCheckpoint(String taskId) {
        if (repository.isCancellationRequested(taskId)) throw new CancellationException("V2 task was cancelled");
    }
}
