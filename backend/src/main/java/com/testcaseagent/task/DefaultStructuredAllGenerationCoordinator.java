package com.testcaseagent.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testcaseagent.export.StructuredWorkbookExportRequest;
import com.testcaseagent.export.WorkbookArtifact;
import com.testcaseagent.export.WorkbookExporter;
import com.testcaseagent.featureaudit.MaterialInventoryDocument;
import com.testcaseagent.featureaudit.MaterialInventoryUnit;
import com.testcaseagent.featureaudit.RequirementMaterialTraversalService;
import com.testcaseagent.identity.LengthPrefixedSha256;
import com.testcaseagent.knowledgeagent.FeatureScopeReconciliationInput;
import com.testcaseagent.knowledgeagent.FeatureScopeReconciliationInvocation;
import com.testcaseagent.knowledgeagent.FunctionListExtractionInput;
import com.testcaseagent.knowledgeagent.FunctionListExtractionInvocation;
import com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignInput;
import com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignInvocation;
import com.testcaseagent.knowledgeagent.FormalSupport;
import com.testcaseagent.knowledgeagent.MaterialContentTypeKey;
import com.testcaseagent.knowledgeagent.RequirementMaterialQualityReviewInput;
import com.testcaseagent.knowledgeagent.RequirementMaterialQualityReviewInvocation;
import com.testcaseagent.knowledgeagent.StructuredSkillExecutionException;
import com.testcaseagent.knowledgeagent.StructuredSkillExecutionPort;
import com.testcaseagent.knowledgeagent.StructuredSkillSessionPort;
import com.testcaseagent.scope.RequirementScope;
import com.testcaseagent.structuredgeneration.StructuredCompletionGate;
import com.testcaseagent.structuredgeneration.StructuredCoverageStatus;
import com.testcaseagent.structuredgeneration.StructuredGenerationAcceptanceStore;
import com.testcaseagent.structuredgeneration.StructuredMaterialSlicePlanner;
import com.testcaseagent.structuredgeneration.StructuredProcessingStatus;
import com.testcaseagent.structuredgeneration.StructuredSkillResultMapper;
import com.testcaseagent.structuredgeneration.StructuredTestPointPlanner;
import com.testcaseagent.validation.FeatureReconciliationValidator;
import com.testcaseagent.validation.FunctionListExtractionValidator;
import com.testcaseagent.validation.FunctionalTestcaseResultValidator;
import com.testcaseagent.validation.RequirementMaterialReviewValidator;
import com.testcaseagent.validation.StructuredEvidence;
import com.testcaseagent.validation.StructuredKeyType;
import com.testcaseagent.validation.StructuredValidationRegistry;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
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
    private final StructuredMaterialSlicePlanner materialPlanner = new StructuredMaterialSlicePlanner();
    private final FunctionListExtractionValidator extractionValidator = new FunctionListExtractionValidator();
    private final RequirementMaterialReviewValidator reviewValidator = new RequirementMaterialReviewValidator();
    private final FeatureReconciliationValidator reconciliationValidator = new FeatureReconciliationValidator();
    private final FunctionalTestcaseResultValidator testcaseValidator = new FunctionalTestcaseResultValidator();
    private final StructuredTestPointPlanner testPointPlanner = new StructuredTestPointPlanner();
    private final StructuredCompletionGate completionGate = new StructuredCompletionGate();

    /** Creates the only production coordinator used by ALL tasks. */
    public DefaultStructuredAllGenerationCoordinator(GenerationTaskRepository repository,
            RequirementMaterialTraversalService traversal, StructuredSkillExecutionPort skills,
            StructuredSkillSessionPort sessions, StructuredGenerationAcceptanceStore store,
            WorkbookExporter exporter, ObjectMapper objectMapper) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.traversal = Objects.requireNonNull(traversal, "traversal must not be null");
        this.skills = Objects.requireNonNull(skills, "skills must not be null");
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.exporter = Objects.requireNonNull(exporter, "exporter must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public void execute(String taskId, CreateGenerationTaskRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        store.updateTaskState(taskId, new StructuredGenerationAcceptanceStore.StructuredTaskState(
                StructuredProcessingStatus.RUNNING, StructuredCoverageStatus.PENDING));
        try {
            cancellationCheckpoint(taskId);
            List<MaterialInventoryDocument> materials = traversal.traverse(taskId, request, false).documents();
            cancellationCheckpoint(taskId);
            StructuredValidationRegistry registry = inventoryRegistry(taskId, materials);
            String sessionId = sessions.openStructuredSession();
            executeReviews(taskId, request, sessionId, registry, materials);
            executeExtractions(taskId, request, sessionId, registry, materials);

            cancellationCheckpoint(taskId);
            StructuredGenerationAcceptanceStore.AcceptedInputs accepted = store.acceptedInputs(taskId);
            accepted.facts().forEach(fact -> registry.requireOrRegister(StructuredKeyType.REQUIREMENT_FACT, fact.factKey()));
            accepted.functionItems().forEach(item -> registry.requireOrRegister(StructuredKeyType.FUNCTION_LIST_ITEM, item.itemKey()));
            if (accepted.functionItems().isEmpty()) {
                complete(taskId, false, true);
                return;
            }
            if (accepted.functionItems().size() > 200 || accepted.facts().size() > 200) {
                throw new IllegalArgumentException("Structured reconciliation input exceeds the frozen per-call boundary");
            }
            executeReconciliation(taskId, request, sessionId, registry, accepted);
            cancellationCheckpoint(taskId);
            executeTestcases(taskId, request, sessionId, registry, store.acceptedConfirmedFunctions(taskId));
            complete(taskId, true, false);
        } catch (CancellationException exception) {
            repository.cancelStructuredTask(taskId, StructuredCoverageStatus.PENDING);
        } catch (RuntimeException exception) {
            store.updateTaskState(taskId, new StructuredGenerationAcceptanceStore.StructuredTaskState(
                    StructuredProcessingStatus.FAILED, StructuredCoverageStatus.PENDING));
            repository.failStructuredTask(taskId, StructuredCoverageStatus.PENDING);
            throw exception;
        }
    }

    private void executeReviews(String taskId, CreateGenerationTaskRequest request, String sessionId,
            StructuredValidationRegistry registry, List<MaterialInventoryDocument> materials) {
        for (MaterialInventoryDocument material : materials) {
            if ("FUNCTION_LIST".equals(material.documentRole())) continue;
            MaterialContentTypeKey contentType = contentType(material.documentRole());
            String sourceLabel = sourceLabel(material);
            for (RequirementMaterialQualityReviewInput input : materialPlanner.plan(
                    material.documentId(), contentType, sourceLabel, material.units())) {
                cancellationCheckpoint(taskId);
                List<String> evidence = input.units().stream().map(RequirementMaterialQualityReviewInput.MaterialUnit::unitKey).toList();
                Map<String, String> evidenceTexts = new LinkedHashMap<>();
                input.units().forEach(unit -> {
                    if (evidenceTexts.putIfAbsent(unit.unitKey(), unit.content()) != null) {
                        throw new IllegalArgumentException("Review slice unit keys must be unique");
                    }
                });
                String identity = identity(taskId, "REQUIREMENT_MATERIAL_REVIEW", input);
                RequirementScope reviewScope = request.requirementScope().singleDocumentAuthorization(material.documentId());
                var registration = new StructuredGenerationAcceptanceStore.WorkRegistration(taskId, identity,
                        "requirement-material-quality-review", "REQUIREMENT_MATERIAL_REVIEW",
                        input.units().get(0).ordinal(), input.units().get(input.units().size() - 1).ordinal(),
                        input.materialKey(), input.sourceLabel(), evidence, null, null);
                executeWork(registration, claim -> {
                    var result = StructuredSkillResultMapper.review(skills.reviewRequirementMaterial(
                            new RequirementMaterialQualityReviewInvocation(sessionId, request.agentId(), reviewScope, input)).data().result());
                    RequirementMaterialReviewValidator.Result namespaced = namespaceReview(taskId, identity, result);
                    store.acceptReview(claim, reviewValidator, new RequirementMaterialReviewValidator.WorkItem(
                            registry, input.materialKey(), input.contentTypeKey().wireValue(), evidence, evidenceTexts), namespaced);
                    return null;
                });
            }
        }
    }

    private void executeExtractions(String taskId, CreateGenerationTaskRequest request, String sessionId,
            StructuredValidationRegistry registry, List<MaterialInventoryDocument> materials) {
        for (MaterialInventoryDocument material : materials) {
            if (!"FUNCTION_LIST".equals(material.documentRole())) continue;
            for (FunctionListExtractionInput input : extractionSlices(material)) {
                cancellationCheckpoint(taskId);
                List<String> evidence = input.units().stream().map(FunctionListExtractionInput.Unit::unitKey).toList();
                String identity = identity(taskId, "FEATURE_SCOPE_EXTRACT", input);
                var registration = new StructuredGenerationAcceptanceStore.WorkRegistration(taskId, identity,
                        "feature-scope-reconciliation", "FEATURE_SCOPE_EXTRACT",
                        input.units().get(0).ordinal(), input.units().get(input.units().size() - 1).ordinal(),
                        input.materialKey(), input.sourceLabel(), evidence, null, null);
                executeWork(registration, claim -> {
                    FunctionListExtractionValidator.Result mapped = StructuredSkillResultMapper.extraction(skills.extractFunctionList(
                            new FunctionListExtractionInvocation(sessionId, request.agentId(), request.requirementScope(), input)).data().result());
                    List<FunctionListExtractionValidator.ValidatedItem> validated = extractionValidator.mergeSlices(
                            extractionValidator.validate(new FunctionListExtractionValidator.WorkItem(
                                    registry, input.materialKey(), evidence), mapped));
                    store.acceptFunctionListItems(claim, registry, validated.stream().map(row ->
                            new StructuredGenerationAcceptanceStore.FunctionListItem(
                                    row.itemKey(), row.path(), row.description(), row.evidenceKeys())).toList());
                    return null;
                });
            }
        }
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
            FeatureReconciliationValidator.Result namespaced = namespaceReconciliation(taskId, identity, mapped);
            store.acceptReconciliation(claim, reconciliationValidator, new FeatureReconciliationValidator.WorkItem(
                    registry, input.functionListItems().stream().map(FeatureScopeReconciliationInput.FunctionListItem::itemKey).toList(),
                    input.requirementFacts().stream().map(FeatureScopeReconciliationInput.RequirementFact::factKey).toList(), evidence),
                    namespaced);
            return null;
        });
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
                    FunctionalTestcaseResultValidator.Result namespaced = namespaceTestcases(taskId, identity, mapped);
                    store.acceptTestcases(claim, testcaseValidator, new FunctionalTestcaseResultValidator.WorkItem(
                            registry, input.functionKey(), input.functionName(), input.testPoint().testPointKey(), input.testPoint().description(),
                            FunctionalTestcaseResultValidator.TestPointType.valueOf(input.testPoint().type().name()),
                            FunctionalTestcaseResultValidator.Basis.valueOf(input.testPoint().basis().name()),
                            input.testPoint().requirementFactKeys(), evidence, input.testPoint().missingInformation(),
                            formalSupports(confirmed, input.testPoint().requirementFactKeys())), namespaced);
                    return null;
                });
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

    private void complete(String taskId, boolean reconciled, boolean noFunctionItems) {
        cancellationCheckpoint(taskId);
        repository.transitionTask(taskId, GenerationTaskStatus.GENERATING);
        cancellationCheckpoint(taskId);
        repository.transitionTask(taskId, GenerationTaskStatus.VALIDATING);
        cancellationCheckpoint(taskId);
        StructuredGenerationAcceptanceStore.AggregateState aggregate = store.aggregateState(taskId);
        StructuredWorkbookExportRequest rows = repository.structuredWorkbookRequest(taskId);
        cancellationCheckpoint(taskId);
        WorkbookArtifact artifact = exporter.exportStructured(rows);
        cancellationCheckpoint(taskId);
        var outcome = completionGate.evaluate(new StructuredCompletionGate.Snapshot(true,
                aggregate.totalReviewWork(), aggregate.completedReviewWork(), reconciled || noFunctionItems, true,
                aggregate.formalPointTotal(), aggregate.coveredFormalPointCount(), aggregate.pendingCandidateCount(),
                true, aggregate.acceptedWorkCount(),
                aggregate.failedWorkCount(), aggregate.allWorkTerminal(), false));
        store.updateTaskState(taskId, new StructuredGenerationAcceptanceStore.StructuredTaskState(
                outcome.processingStatus(), outcome.coverageStatus()));
        repository.completeStructuredTask(taskId, artifact, outcome.processingStatus(), outcome.coverageStatus());
    }

    private void cancellationCheckpoint(String taskId) {
        if (repository.isCancellationRequested(taskId)) throw new CancellationException("Structured task was cancelled");
    }

    private <T> T executeWork(StructuredGenerationAcceptanceStore.WorkRegistration registration,
            Function<StructuredGenerationAcceptanceStore.WorkClaim, T> action) {
        String workId = store.register(registration);
        if (store.isCompleted(workId)) return null;
        while (true) {
            cancellationCheckpoint(registration.taskId());
            StructuredGenerationAcceptanceStore.WorkClaim claim = store.claimRegistered(
                    registration.taskId(), workId, OWNER)
                    .orElseThrow(() -> new IllegalStateException("Registered structured work could not be claimed"));
            try {
                return action.apply(claim);
            } catch (RuntimeException exception) {
                String failureType = failureType(exception);
                store.fail(claim, failureType);
                if (isTransient(failureType)
                        && claim.attemptNumber() < StructuredGenerationAcceptanceStore.MAX_ATTEMPTS) continue;
                throw exception;
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

    private List<FunctionListExtractionInput> extractionSlices(MaterialInventoryDocument material) {
        List<FunctionListExtractionInput> slices = new ArrayList<>();
        for (int offset = 0; offset < material.units().size(); offset += 32) {
            List<FunctionListExtractionInput.Unit> units = material.units().subList(offset,
                            Math.min(offset + 32, material.units().size())).stream()
                    .map(unit -> new FunctionListExtractionInput.Unit(unit.unitId(), unit.ordinal(), unit.content())).toList();
            slices.add(new FunctionListExtractionInput(material.documentId(), sourceLabel(material), units));
        }
        if (slices.isEmpty()) throw new IllegalArgumentException("Function-list material must contain parsed units");
        return slices;
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
