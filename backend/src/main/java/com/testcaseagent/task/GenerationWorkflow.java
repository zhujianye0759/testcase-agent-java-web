package com.testcaseagent.task;

import com.testcaseagent.diagnostics.WorkflowDiagnostics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testcaseagent.export.MarkdownWorkbookExportRequest;
import com.testcaseagent.export.WorkbookArtifact;
import com.testcaseagent.export.WorkbookExporter;
import com.testcaseagent.featureaudit.FeatureAuditResult;
import com.testcaseagent.featureaudit.FeatureAuditService;
import com.testcaseagent.featureaudit.FinalReconciliationPageException;
import com.testcaseagent.featureaudit.FrozenFeatureResult;
import com.testcaseagent.featureaudit.FrozenFeatureService;
import com.testcaseagent.featureaudit.FrozenFeatureTarget;
import com.testcaseagent.featureaudit.RequirementMaterialTraversalService;
import com.testcaseagent.knowledgeagent.KnowledgeAgentInvocation;
import com.testcaseagent.knowledgeagent.KnowledgeAgentPort;
import com.testcaseagent.knowledgeagent.KnowledgeAgentSkillPreparationException;
import com.testcaseagent.markdown.MarkdownGenerationResult;
import com.testcaseagent.markdown.MarkdownGenerationResultParser;
import com.testcaseagent.testcase.FrozenFeatureBatchAcceptanceValidator;
import com.testcaseagent.testcase.GenerationTaskMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs durable material-bounded audit and Markdown generation work.
 *
 * <p>ALL creation only persists an unplanned task. The five-slot worker first traverses every authorised
 * material unit, completes the bidirectional audit, freezes the durable feature set, and only then plans its
 * server-owned batches. Specified-feature work bypasses that audit chain.</p>
 *
 * [Req-ID]: REQ-TSK-004, REQ-TSK-005, REQ-TSK-007, REQ-TSK-009, REQ-KAG-002, REQ-KAG-003,
 * REQ-KAG-004, REQ-ANA-007
 */
public final class GenerationWorkflow {
    private static final Logger LOGGER = LoggerFactory.getLogger(GenerationWorkflow.class);
    private final GenerationTaskRepository repository;
    private final KnowledgeAgentPort knowledgeAgentPort;
    private final WorkbookExporter workbookExporter;
    private final ObjectMapper objectMapper;
    private final TaskExecutionQueue executionQueue;
    private final TaskExecutor taskExecutor;
    private final RequirementMaterialTraversalService materialTraversalService;
    private final FeatureAuditService featureAuditService;
    private final FrozenFeatureService frozenFeatureService;
    private final StructuredAllGenerationCoordinator structuredAllCoordinator;
    private final FrozenFeatureBatchAcceptanceValidator frozenFeatureBatchAcceptanceValidator = new FrozenFeatureBatchAcceptanceValidator();
    private final MarkdownGenerationResultParser markdownParser = new MarkdownGenerationResultParser();

    public GenerationWorkflow(GenerationTaskRepository repository, KnowledgeAgentPort knowledgeAgentPort,
            WorkbookExporter workbookExporter, ObjectMapper objectMapper, TaskExecutionQueue executionQueue,
            TaskExecutor taskExecutor, RequirementMaterialTraversalService materialTraversalService,
            FeatureAuditService featureAuditService, FrozenFeatureService frozenFeatureService) {
        this(repository, knowledgeAgentPort, workbookExporter, objectMapper, executionQueue, taskExecutor,
                materialTraversalService, featureAuditService, frozenFeatureService, null, false);
    }

    /** Production constructor with the isolated structured ALL route. */
    public GenerationWorkflow(GenerationTaskRepository repository, KnowledgeAgentPort knowledgeAgentPort,
            WorkbookExporter workbookExporter, ObjectMapper objectMapper, TaskExecutionQueue executionQueue,
            TaskExecutor taskExecutor, RequirementMaterialTraversalService materialTraversalService,
            FeatureAuditService featureAuditService, FrozenFeatureService frozenFeatureService,
            StructuredAllGenerationCoordinator structuredAllCoordinator) {
        this(repository, knowledgeAgentPort, workbookExporter, objectMapper, executionQueue, taskExecutor,
                materialTraversalService, featureAuditService, frozenFeatureService, structuredAllCoordinator, true);
    }

    private GenerationWorkflow(GenerationTaskRepository repository, KnowledgeAgentPort knowledgeAgentPort,
            WorkbookExporter workbookExporter, ObjectMapper objectMapper, TaskExecutionQueue executionQueue,
            TaskExecutor taskExecutor, RequirementMaterialTraversalService materialTraversalService,
            FeatureAuditService featureAuditService, FrozenFeatureService frozenFeatureService,
            StructuredAllGenerationCoordinator structuredAllCoordinator, boolean requireStructuredCoordinator) {
        this.repository = repository;
        this.knowledgeAgentPort = knowledgeAgentPort;
        this.workbookExporter = workbookExporter;
        this.objectMapper = objectMapper;
        this.executionQueue = executionQueue;
        this.taskExecutor = taskExecutor;
        this.materialTraversalService = materialTraversalService;
        this.featureAuditService = featureAuditService;
        this.frozenFeatureService = frozenFeatureService;
        this.structuredAllCoordinator = requireStructuredCoordinator
                ? Objects.requireNonNull(structuredAllCoordinator, "structuredAllCoordinator must not be null")
                : structuredAllCoordinator;
    }

    public String create(CreateGenerationTaskRequest request) {
        // V2 owns its durable work inventory; creating legacy generation_batch rows would make restart recovery
        // mistake a valid V2 task for an old Markdown workflow. [Req-ID]: REQ-TGV2-002
        List<GenerationTaskRepository.PlannedBatch> batches = request.isV2() ? List.of() : planBatches(request);
        GenerationTaskRepository.TaskCreation creation = repository.createTaskIfAbsent(UUID.randomUUID().toString(), batches,
                request, idempotencyKey(request));
        if (creation.created()) scheduleNext();
        return creation.taskId();
    }

    private List<GenerationTaskRepository.PlannedBatch> planBatches(CreateGenerationTaskRequest request) {
        return request.featureIds().stream().map(featureId -> new GenerationTaskRepository.PlannedBatch(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), featureId)).toList();
    }

    private void scheduleNext() {
        try { taskExecutor.execute(this::executeNext); }
        catch (TaskRejectedException ignored) { /* the durable queue remains the source of truth */ }
    }

    private void executeNext() {
        executionQueue.claimNext().ifPresent(claim -> {
            try { executeClaimed(claim); }
            finally { executionQueue.release(claim); scheduleNext(); }
        });
    }

    /** Executes one claimed task while preserving the ALL audit gate before generation. [Req-ID]: REQ-CAG-001 */
    void executeClaimed(TaskExecutionClaim claim) {
        CreateGenerationTaskRequest request = repository.request(claim.taskId());
        try {
            // V2 owns an explicit audited function scope, so a nonempty feature list is expected and must not
            // be mistaken for the historical Markdown batch route. [Req-ID]: REQ-TGV2-001, REQ-TGV2-002
            if (request.isV2()) {
                if (structuredAllCoordinator == null) {
                    throw new IllegalStateException("Generation V2 coordinator is unavailable");
                }
                structuredAllCoordinator.execute(claim.taskId(), request);
                return;
            }
            if (request.taskMode() == GenerationTaskMode.ALL && request.featureIds().isEmpty()
                    && structuredAllCoordinator != null) {
                structuredAllCoordinator.execute(claim.taskId(), request);
                return;
            }
            if (request.taskMode() == GenerationTaskMode.ALL && request.featureIds().isEmpty()) {
                request = freezeAllFeatures(claim.taskId(), request);
                // An all-ineligible frozen set is a durable audit result, not a failed discovery. No batch exists
                // to retry; persist the truthful partial terminal seam rather than leaving AUDITING stranded.
                if (request.featureIds().isEmpty()) {
                    repository.finishAllFrozenFeaturesIneligible(claim.taskId(), request.requirementScope());
                    return;
                }
            }
            GenerationTaskRepository.TaskExecutionWork first = repository.requireQueuedWork(claim.taskId());
            if (repository.cancelAtCheckpoint(first.taskId(), first.batchId(), first.attemptId())) return;
            repository.transitionTask(claim.taskId(), GenerationTaskStatus.GENERATING);
            executeBatches(claim.taskId());
        } catch (CancellationException exception) {
            if (repository.taskStatus(claim.taskId()) == GenerationTaskStatus.AUDITING
                    && repository.cancelAuditingAtCheckpoint(claim.taskId())) {
                return;
            }
            if (repository.taskStatus(claim.taskId()) == GenerationTaskStatus.CANCELLED) {
                return;
            }
            throw exception;
        } catch (RuntimeException exception) {
            if (repository.taskStatus(claim.taskId()) == GenerationTaskStatus.AUDITING) {
                String failureSummary = auditingFailureSummary(exception);
                LOGGER.warn("Audit-stage task failed: {}", failureSummary);
                repository.failAuditingTask(claim.taskId(), failureSummary);
            }
            else throw exception;
        }
    }

    /**
     * Advances the only permitted ALL planning order: complete material traversal, complete audit, durable freeze,
     * then batch planning for eligible targets in their frozen sequence.
     *
     * [Req-ID]: REQ-CAG-001, REQ-BFA-005
     */
    CreateGenerationTaskRequest freezeAllFeatures(String taskId, CreateGenerationTaskRequest request) {
        materialTraversalService.traverse(taskId, request, false);
        FeatureAuditResult audit = featureAuditService.audit(taskId, request);
        if (!audit.complete()) {
            throw new IllegalStateException("Feature audit is not complete");
        }
        FrozenFeatureResult frozen = frozenFeatureService.freeze(taskId, request.requirementScope());
        if (!frozen.frozen()) {
            throw new IllegalStateException("Feature freeze is not complete");
        }
        List<com.testcaseagent.featureaudit.FrozenFeatureTarget> orderedTargets = frozen.targets().stream()
                .sorted(java.util.Comparator.comparingInt(com.testcaseagent.featureaudit.FrozenFeatureTarget::stableSequence))
                .toList();
        CreateGenerationTaskRequest planned = request.withFrozenFeatures(orderedTargets);
        if (planned.featureIds().isEmpty()) return planned;
        repository.planFrozenBatches(taskId, planned, orderedTargets, planBatches(planned));
        return planned;
    }

    private void executeBatches(String taskId) {
        GenerationTaskRepository.TaskExecutionWork work;
        while ((work = repository.nextQueuedWork(taskId).orElse(null)) != null) {
            try {
                repository.startBatch(work.batchId(), work.attemptId());
                if (repository.cancelAtCheckpoint(work.taskId(), work.batchId(), work.attemptId())) return;
                String feature = requireFeaturePath(work);
                FrozenFeatureTarget frozenFeature = frozenFeatureFor(work);
                String prompt = generationPrompt(work.request(), feature, frozenFeature,
                        frozenFeature == null ? ""
                                : retryCorrectionFeedback(repository.previousFailureReason(work.batchId(), work.attemptId())));
                KnowledgeAgentInvocation invocation = new KnowledgeAgentInvocation(work.request().agentId(),
                        work.request().requirementScope(), work.request().exampleScope(), work.request().requirementAdmissionTypeKeys(),
                        prompt, work.request().fewShotPolicy());
                WorkflowDiagnostics.generation(work.taskId(), work.batchId(), work.attemptId(), "request", prompt);
                knowledgeAgentPort.prepareGenerationSession(invocation);
                try {
                    String markdown = knowledgeAgentPort.invoke(invocation).terminalMarkdown();
                    WorkflowDiagnostics.generation(work.taskId(), work.batchId(), work.attemptId(), "response", markdown);
                    MarkdownGenerationResult accepted = markdownParser.parse(markdown);
                    if (frozenFeature != null) {
                        frozenFeatureBatchAcceptanceValidator.validate(frozenFeature, accepted);
                    }
                    repository.acceptMarkdownBatch(work.batchId(), work.attemptId(), accepted);
                    WorkflowDiagnostics.generation(work.taskId(), work.batchId(), work.attemptId(), "accepted", "two-case batch accepted");
                } finally {
                    knowledgeAgentPort.closePreparedSession();
                }
            } catch (KnowledgeAgentSkillPreparationException exception) {
                WorkflowDiagnostics.generation(work.taskId(), work.batchId(), work.attemptId(), "preparation-failed", exception.getMessage());
                repository.failTask(work.taskId(), work.batchId(), work.attemptId(), safeFailureMessage(exception));
                return;
            } catch (RuntimeException exception) {
                WorkflowDiagnostics.generation(work.taskId(), work.batchId(), work.attemptId(), "failed", exception.getMessage());
                repository.failBatch(work.batchId(), work.attemptId(), safeFailureMessage(exception), true);
            }
        }
        finishAndExport(taskId);
    }

    /**
     * Resolves the task-owned immutable ALL target before model invocation. Specified-feature tasks predate the
     * reconciliation ledger and deliberately retain their existing strict Markdown-only acceptance path.
     *
     * [Req-ID]: REQ-CAG-001, REQ-CAG-002, REQ-CAG-003
     */
    private FrozenFeatureTarget frozenFeatureFor(GenerationTaskRepository.TaskExecutionWork work) {
        if (work.request().taskMode() != GenerationTaskMode.ALL) {
            return null;
        }
        return repository.frozenFeatureTargets(work.taskId()).stream()
                .filter(target -> target.stableFeatureId().equals(work.featureId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Queued ALL batch does not have its frozen feature target"));
    }

    private static String requireFeaturePath(GenerationTaskRepository.TaskExecutionWork work) {
        String feature = work.request().featurePaths().get(work.featureId());
        if (feature == null || feature.isBlank()) {
            throw new IllegalStateException("Queued batch does not have a frozen feature path");
        }
        return feature.strip();
    }

    private static String generationPrompt(
            CreateGenerationTaskRequest request, String featurePath, FrozenFeatureTarget frozenFeature, String retryCorrection) {
        if (frozenFeature == null) {
            String prompt = request.prompt() + "\n本批次功能点：" + featurePath;
            return retryCorrection.isEmpty() ? prompt : prompt + "\n\n" + retryCorrection;
        }
        if (!featurePath.equals(frozenFeature.featureName())) {
            throw new IllegalStateException("Queued ALL batch feature path conflicts with its frozen feature target");
        }
        String candidateIds = String.join(",", frozenFeature.source().candidateIds());
        String leaf = featurePath.substring(featurePath.lastIndexOf('/') + 1).strip();
        String prompt = request.prompt() + "\n\n"
                + "仅生成当前功能路径：" + featurePath + "。不得生成其他功能。\n"
                + "只输出两个 H2 和两张 Markdown 表：## 需求与功能清单审查发现、## 测试用例；表头必须分别为"
                + "序号|对象/功能点|问题分类|证据对照" + "和"
                + "用例名称|功能模块|前提约束|执行步骤|预期结果|对应需求内容。\n"
                + "测试用例表恰好两行，名称必须是：" + leaf + "_正向、" + leaf + "_反向。\n"
                + "功能模块精确填写为：" + featurePath + "。执行步骤和预期结果均用 <br> 分隔，并从 1 开始连续编号且一一对应。\n"
                + "正式需求内容必须是可读摘要加 <br>candidateIds=" + candidateIds
                + "；candidateIds 只能使用该允许集合。仅基于通用经验时，对应需求内容必须精确为：依据通用经验，待确认。\n"
                + "审查表如有行，对象只能是当前功能路径或其叶子名，证据对照同样只能回显 candidateIds=" + candidateIds + "。";
        return retryCorrection.isEmpty() ? prompt : prompt + "\n\n" + retryCorrection;
    }

    private static String retryCorrectionFeedback(Optional<String> previousFailureReason) {
        return previousFailureReason.map(GenerationWorkflow::fixedRetryCorrection)
                .orElse("");
    }

    private static String fixedRetryCorrection(String failureReason) {
        return switch (failureReason) {
            case "General-experience content must exactly equal '依据通用经验，待确认'" ->
                    "上一轮校验未通过：对应需求内容只能二选一：\n"
                            + "正式材料：可读摘要+<br>candidateIds\n"
                            + "通用经验：依据通用经验，待确认\n"
                            + "若选择通用经验，冒号后到行尾的全部内容必须到此结束；严禁追加 <br>、candidateIds、引号、句号或任何其他文字，不得混合两种写法。";
            case "Execution steps and expected results must have the same numbered items" ->
                    "上一轮校验未通过：执行步骤和预期结果必须都从 1 连续编号，并保持编号逐项一一对应。";
            case "Audit evidence must retain candidateIds for the frozen target" ->
                    "上一轮校验未通过：审查证据对照必须保留当前冻结目标允许的 candidateIds。";
            case "Markdown contract invalid: expected no content after the final test-case table" ->
                    "上一轮校验未通过：测试用例表结束后不得再输出任何内容。";
            case "Markdown contract invalid: expected text-only table cells with only <br> line separators" ->
                    "上一轮校验未通过：表格单元格只能包含文本，换行只能使用 <br>。";
            case "Requirement content must retain candidateIds for the frozen target" ->
                    "上一轮校验未通过：正式需求内容必须保留当前冻结目标允许的 candidateIds。";
            case "Requirement content must reference only candidates of the frozen target" ->
                    "上一轮校验未通过：正式需求内容只能引用当前冻结目标允许的 candidateIds。";
            default -> "上一轮未通过固定输出合同校验。请严格遵守本提示中的两张表、表后无内容、文本单元格、编号、通用经验和冻结 candidateIds 约束。";
        };
    }

    private void finishAndExport(String taskId) {
        repository.transitionTask(taskId, GenerationTaskStatus.VALIDATING);
        try {
            GenerationTaskRepository.FinalizationReadiness readiness = repository.finalizationReadiness(taskId);
            if (!readiness.artifactRequired()) {
                repository.finishWithoutArtifact(taskId, readiness.terminalStatus());
                return;
            }
            MarkdownTaskRows rows = repository.exportMarkdownRows(taskId);
            if (rows.testCaseRows().isEmpty()) {
                throw new IllegalStateException("Validated finalization requires accepted test-case rows");
            }
            WorkbookArtifact artifact = workbookExporter.exportMarkdown(new MarkdownWorkbookExportRequest(taskId, rows.auditRows(),
                    rows.testCaseRows(), true, readiness.terminalStatus() == GenerationTaskStatus.PARTIAL));
            repository.completeMarkdownTask(taskId, readiness.terminalStatus(), artifact);
        } catch (RuntimeException exception) {
            if (repository.taskStatus(taskId) == GenerationTaskStatus.VALIDATING) {
                repository.finishWithoutArtifact(taskId, GenerationTaskStatus.FAILED);
                return;
            }
            throw exception;
        }
    }

    public GenerationTaskDetail detail(String taskId) {
        return detail(taskId, StructuredDetailQuery.defaults());
    }

    /** Reads a bounded V2 projection while preserving the historical V1 response shape. [Req-ID]: REQ-TGV2-009 */
    public GenerationTaskDetail detail(String taskId, StructuredDetailQuery query) {
        return repository.findDetail(taskId, query).orElseThrow(() -> new GenerationTaskNotFoundException(taskId));
    }
    public GenerationTaskPage list(int page, int size, String query) { return repository.findPage(page, size, query); }
    /** Cancels only a mutable V2 task; historical V1 records are retained as read-only evidence. [Req-ID]: REQ-TGV2-010 */
    public void cancel(String taskId) {
        if (!repository.isV2Task(taskId)) throw new IllegalStateException("Historical task is read-only");
        repository.requestCancellation(taskId);
    }
    /** Requeues one exact eligible retry target or fails closed on a stale/forbidden user action. [Req-ID]: REQ-ESR-001 */
    public void retryFailedBatches(String taskId) {
        if (!repository.isV2Task(taskId)) throw new GenerationTaskRetryConflictException();
        if (repository.retryFailedBatches(taskId) <= 0) throw new GenerationTaskRetryConflictException();
        scheduleNext();
    }
    /** Regenerates only the workbook projection for one completed structured ALL task. [Req-ID]: REQ-SGD-005 */
    public WorkbookArtifact regenerateStructuredArtifact(String taskId) {
        if (!repository.isV2Task(taskId)) throw new IllegalStateException("Historical task is read-only");
        String expectedArtifactId = repository.structuredArtifactRegenerationBaseline(taskId);
        WorkbookArtifact artifact = workbookExporter.exportStructuredRows(repository.structuredWorkbookRows(taskId));
        repository.replaceStructuredArtifact(taskId, expectedArtifactId, artifact);
        return artifact;
    }
    /** Invoked once by the application-start runner before normal queue claims begin. [Req-ID]: REQ-TSK-008 */
    public void recoverAtStartup() { executionQueue.recoverAtStartup(); scheduleNext(); }

    private static String safeFailureMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return SensitiveValueRedactor.redact(message == null || message.isBlank() ? exception.getClass().getSimpleName() : message);
    }

    private static String auditingFailureSummary(RuntimeException exception) {
        if (exception instanceof FinalReconciliationPageException pageFailure) return pageFailure.safeSummary();
        return "材料或审查处理未完成，未冻结功能范围";
    }

    private String idempotencyKey(CreateGenerationTaskRequest request) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(objectMapper.writeValueAsString(request).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException | JsonProcessingException exception) { throw new IllegalStateException("Unable to create task idempotency key", exception); }
    }
}
