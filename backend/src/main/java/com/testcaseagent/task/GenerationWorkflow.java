package com.testcaseagent.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testcaseagent.export.MarkdownWorkbookExportRequest;
import com.testcaseagent.export.WorkbookArtifact;
import com.testcaseagent.export.WorkbookExporter;
import com.testcaseagent.knowledgeagent.FeatureDiscoveryInvocation;
import com.testcaseagent.knowledgeagent.KnowledgeAgentInvocation;
import com.testcaseagent.knowledgeagent.KnowledgeAgentPort;
import com.testcaseagent.markdown.MarkdownFeatureRow;
import com.testcaseagent.markdown.MarkdownGenerationResult;
import com.testcaseagent.markdown.MarkdownGenerationResultParser;
import com.testcaseagent.testcase.GenerationTaskMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

/**
 * Runs durable Markdown discovery and generation work.
 *
 * <p>ALL creation only persists an empty, frozen task. The five-slot worker discovers names later,
 * freezes its server-owned batches, and then accepts parsed Markdown rows atomically per batch.</p>
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
    private final MarkdownGenerationResultParser markdownParser = new MarkdownGenerationResultParser();

    public GenerationWorkflow(GenerationTaskRepository repository, KnowledgeAgentPort knowledgeAgentPort,
            WorkbookExporter workbookExporter, ObjectMapper objectMapper, TaskExecutionQueue executionQueue,
            TaskExecutor taskExecutor) {
        this.repository = repository;
        this.knowledgeAgentPort = knowledgeAgentPort;
        this.workbookExporter = workbookExporter;
        this.objectMapper = objectMapper;
        this.executionQueue = executionQueue;
        this.taskExecutor = taskExecutor;
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

    private void executeClaimed(TaskExecutionClaim claim) {
        CreateGenerationTaskRequest request = repository.request(claim.taskId());
        try {
            if (request.taskMode() == GenerationTaskMode.ALL && request.featureIds().isEmpty()) {
                request = freezeAllFeatures(claim.taskId(), request);
            }
            GenerationTaskRepository.TaskExecutionWork first = repository.requireQueuedWork(claim.taskId());
            if (repository.cancelAtCheckpoint(first.taskId(), first.batchId(), first.attemptId())) return;
            repository.transitionTask(claim.taskId(), GenerationTaskStatus.GENERATING);
            executeBatches(claim.taskId());
        } catch (RuntimeException exception) {
            if (repository.taskStatus(claim.taskId()) == GenerationTaskStatus.AUDITING) repository.failAuditingTask(claim.taskId());
            else throw exception;
        }
    }

    private CreateGenerationTaskRequest freezeAllFeatures(String taskId, CreateGenerationTaskRequest request) {
        List<MarkdownFeatureRow> rows = knowledgeAgentPort.discoverFeatures(new FeatureDiscoveryInvocation(request.agentId(),
                request.requirementScope(), request.requirementAdmissionTypeKeys()));
        CreateGenerationTaskRequest discovered = request.withDiscoveredFeatures(rows);
        repository.planDiscoveredBatches(taskId, discovered, planBatches(discovered));
        return discovered;
    }

    private void executeBatches(String taskId) {
        GenerationTaskRepository.TaskExecutionWork work;
        while ((work = repository.nextQueuedWork(taskId).orElse(null)) != null) {
            try {
                repository.startBatch(work.batchId(), work.attemptId());
                if (repository.cancelAtCheckpoint(work.taskId(), work.batchId(), work.attemptId())) return;
                String feature = work.request().featurePaths().get(work.featureId());
                String markdown = knowledgeAgentPort.invoke(new KnowledgeAgentInvocation(work.request().agentId(),
                        work.request().requirementScope(), work.request().exampleScope(), work.request().requirementAdmissionTypeKeys(),
                        work.request().prompt() + "\n本批次功能点：" + feature, work.request().fewShotPolicy())).terminalMarkdown();
                MarkdownGenerationResult accepted = markdownParser.parse(markdown);
                repository.acceptMarkdownBatch(work.batchId(), work.attemptId(), accepted);
            } catch (RuntimeException exception) {
                repository.failBatch(work.batchId(), work.attemptId(), safeFailureMessage(exception), true);
            }
        }
        finishAndExport(taskId);
    }

    private void finishAndExport(String taskId) {
        repository.transitionTask(taskId, GenerationTaskStatus.VALIDATING);
        MarkdownTaskRows rows = repository.acceptedMarkdownRows(taskId);
        if (rows.testCaseRows().isEmpty()) {
            repository.finishTaskFromBatches(taskId);
            return;
        }
        GenerationTaskStatus terminal = repository.batchCounts(taskId).failed() > 0
                ? GenerationTaskStatus.PARTIAL : GenerationTaskStatus.COMPLETED;
        WorkbookArtifact artifact = workbookExporter.exportMarkdown(new MarkdownWorkbookExportRequest(taskId, rows.auditRows(),
                rows.testCaseRows(), true, terminal == GenerationTaskStatus.PARTIAL));
        repository.completeMarkdownTask(taskId, terminal, artifact);
    }

    public GenerationTaskDetail detail(String taskId) { return repository.findDetail(taskId).orElseThrow(() -> new GenerationTaskNotFoundException(taskId)); }
    public GenerationTaskPage list(int page, int size, String query) { return repository.findPage(page, size, query); }
    public void cancel(String taskId) { repository.requestCancellation(taskId); }
    public void retryFailedBatches(String taskId) { if (repository.retryFailedBatches(taskId) > 0) scheduleNext(); }
    public void recoverExpiredClaims() { executionQueue.recoverExpiredClaims(); scheduleNext(); }

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
