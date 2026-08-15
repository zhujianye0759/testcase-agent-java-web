package com.testcaseagent.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testcaseagent.export.MarkdownWorkbookExportRequest;
import com.testcaseagent.export.WorkbookArtifact;
import com.testcaseagent.export.WorkbookExporter;
import com.testcaseagent.featureaudit.FeatureAuditResult;
import com.testcaseagent.featureaudit.FeatureAuditService;
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
import java.util.UUID;
import java.util.concurrent.CancellationException;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

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
    private final GenerationTaskRepository repository;
    private final KnowledgeAgentPort knowledgeAgentPort;
    private final WorkbookExporter workbookExporter;
    private final ObjectMapper objectMapper;
    private final TaskExecutionQueue executionQueue;
    private final TaskExecutor taskExecutor;
    private final RequirementMaterialTraversalService materialTraversalService;
    private final FeatureAuditService featureAuditService;
    private final FrozenFeatureService frozenFeatureService;
    private final FrozenFeatureBatchAcceptanceValidator frozenFeatureBatchAcceptanceValidator = new FrozenFeatureBatchAcceptanceValidator();
    private final MarkdownGenerationResultParser markdownParser = new MarkdownGenerationResultParser();

    public GenerationWorkflow(GenerationTaskRepository repository, KnowledgeAgentPort knowledgeAgentPort,
            WorkbookExporter workbookExporter, ObjectMapper objectMapper, TaskExecutionQueue executionQueue,
            TaskExecutor taskExecutor, RequirementMaterialTraversalService materialTraversalService,
            FeatureAuditService featureAuditService, FrozenFeatureService frozenFeatureService) {
        this.repository = repository;
        this.knowledgeAgentPort = knowledgeAgentPort;
        this.workbookExporter = workbookExporter;
        this.objectMapper = objectMapper;
        this.executionQueue = executionQueue;
        this.taskExecutor = taskExecutor;
        this.materialTraversalService = materialTraversalService;
        this.featureAuditService = featureAuditService;
        this.frozenFeatureService = frozenFeatureService;
    }

    public String create(CreateGenerationTaskRequest request) {
        List<GenerationTaskRepository.PlannedBatch> batches = planBatches(request);
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
            if (repository.taskStatus(claim.taskId()) == GenerationTaskStatus.AUDITING) repository.failAuditingTask(claim.taskId());
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
                KnowledgeAgentInvocation invocation = new KnowledgeAgentInvocation(work.request().agentId(),
                        work.request().requirementScope(), work.request().exampleScope(), work.request().requirementAdmissionTypeKeys(),
                        generationPrompt(work.request(), feature, frozenFeature), work.request().fewShotPolicy());
                knowledgeAgentPort.prepareGenerationSession(invocation);
                try {
                    String markdown = knowledgeAgentPort.invoke(invocation).terminalMarkdown();
                    MarkdownGenerationResult accepted = markdownParser.parse(markdown);
                    if (frozenFeature != null) {
                        frozenFeatureBatchAcceptanceValidator.validate(frozenFeature, accepted);
                    }
                    repository.acceptMarkdownBatch(work.batchId(), work.attemptId(), accepted);
                } finally {
                    knowledgeAgentPort.closePreparedSession();
                }
            } catch (KnowledgeAgentSkillPreparationException exception) {
                repository.failTask(work.taskId(), work.batchId(), work.attemptId(), safeFailureMessage(exception));
                return;
            } catch (RuntimeException exception) {
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
            CreateGenerationTaskRequest request, String featurePath, FrozenFeatureTarget frozenFeature) {
        if (frozenFeature == null) {
            return request.prompt() + "\n本批次功能点：" + featurePath;
        }
        if (!featurePath.equals(frozenFeature.featureName())) {
            throw new IllegalStateException("Queued ALL batch feature path conflicts with its frozen feature target");
        }
        String candidateIds = String.join(",", frozenFeature.source().candidateIds());
        String leaf = featurePath.substring(featurePath.lastIndexOf('/') + 1).strip();
        return request.prompt() + "\n\n"
                + "仅生成当前功能路径：" + featurePath + "。不得生成其他功能。\n"
                + "只输出两个 H2 和两张 Markdown 表：## 需求与功能清单审查发现、## 测试用例；表头必须分别为"
                + "序号|对象/功能点|问题分类|证据对照" + "和"
                + "用例名称|功能模块|前提约束|执行步骤|预期结果|对应需求内容。\n"
                + "测试用例表恰好两行，名称必须是：" + leaf + "_正向、" + leaf + "_反向。\n"
                + "功能模块精确填写为：" + featurePath + "。执行步骤和预期结果均用 <br> 分隔，并从 1 开始连续编号且一一对应。\n"
                + "正式需求内容必须是可读摘要加 <br>candidateIds=" + candidateIds
                + "；candidateIds 只能使用该允许集合。仅基于通用经验时，对应需求内容必须精确为：依据通用经验，待确认。\n"
                + "审查表如有行，对象只能是当前功能路径或其叶子名，证据对照同样只能回显 candidateIds=" + candidateIds + "。";
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

    public GenerationTaskDetail detail(String taskId) { return repository.findDetail(taskId).orElseThrow(() -> new GenerationTaskNotFoundException(taskId)); }
    public GenerationTaskPage list(int page, int size, String query) { return repository.findPage(page, size, query); }
    public void cancel(String taskId) { repository.requestCancellation(taskId); }
    public void retryFailedBatches(String taskId) { if (repository.retryFailedBatches(taskId) > 0) scheduleNext(); }
    /** Invoked once by the application-start runner before normal queue claims begin. [Req-ID]: REQ-TSK-008 */
    public void recoverAtStartup() { executionQueue.recoverAtStartup(); scheduleNext(); }

    private static String safeFailureMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return SensitiveValueRedactor.redact(message == null || message.isBlank() ? exception.getClass().getSimpleName() : message);
    }

    private String idempotencyKey(CreateGenerationTaskRequest request) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(objectMapper.writeValueAsString(request).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException | JsonProcessingException exception) { throw new IllegalStateException("Unable to create task idempotency key", exception); }
    }
}
