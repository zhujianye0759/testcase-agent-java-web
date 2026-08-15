package com.testcaseagent.featureaudit;

import com.testcaseagent.knowledgeagent.FeatureReconciliationInvocation;
import com.testcaseagent.knowledgeagent.KnowledgeAgentPort;
import com.testcaseagent.task.CreateGenerationTaskRequest;
import com.testcaseagent.task.GenerationTaskRepository;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * Coordinates durable, material-bounded candidate scans and the one final formal-scope reconciliation.
 *
 * <p>All remote calls use the reconciliation port, whose adapter is the stage Skill/SSE acceptance boundary. This
 * service deliberately never passes examples: candidate and conclusion facts originate exclusively in the retained
 * requirement material inventory.</p>
 *
 * [Req-ID]: REQ-KSI-001, REQ-KSI-002, REQ-KSI-003, REQ-BFA-001, REQ-BFA-002, REQ-BFA-003, REQ-BFA-004
 */
public final class FeatureAuditService {
    private static final Duration AUDIT_LEASE = Duration.ofMinutes(5);

    private final GenerationTaskRepository repository;
    private final KnowledgeAgentPort knowledgeAgentPort;
    private final FeatureCandidateScanner featureScanner;
    private final RequirementCandidateScanner requirementScanner;
    private final FeatureConclusionMarkdownParser conclusionParser;

    public FeatureAuditService(GenerationTaskRepository repository, KnowledgeAgentPort knowledgeAgentPort) {
        this(repository, knowledgeAgentPort, new FeatureCandidateScanner(), new RequirementCandidateScanner(),
                new FeatureConclusionMarkdownParser());
    }

    FeatureAuditService(
            GenerationTaskRepository repository, KnowledgeAgentPort knowledgeAgentPort, FeatureCandidateScanner featureScanner,
            RequirementCandidateScanner requirementScanner, FeatureConclusionMarkdownParser conclusionParser) {
        this.repository = repository;
        this.knowledgeAgentPort = knowledgeAgentPort;
        this.featureScanner = featureScanner;
        this.requirementScanner = requirementScanner;
        this.conclusionParser = conclusionParser;
    }

    /**
     * Advances one task until no eligible work remains, then durably accepts its single final reconciliation set.
     * A failed model response is recorded on its same work attempt; it cannot make the returned result complete.
     */
    public FeatureAuditResult audit(String taskId, CreateGenerationTaskRequest request) {
        throwIfCancellationRequested(taskId);
        boolean inventoryComplete = repository.hasCompleteMaterialInventory(taskId, request.requirementScope());
        if (!inventoryComplete) return result(taskId, false);

        Map<String, MaterialInventoryUnit> units = unitsByCoordinate(repository.materialInventory(taskId));
        AuditWorkClaim claim;
        while (true) {
            throwIfCancellationRequested(taskId);
            claim = repository.claimNextAuditWork(taskId, "feature-audit-" + taskId, AUDIT_LEASE).orElse(null);
            if (claim == null) break;
            try {
                processClaim(claim, request, requiredUnit(units, claim));
            } catch (RuntimeException exception) {
                repository.failAuditWork(claim, exception.getMessage());
            }
        }

        throwIfCancellationRequested(taskId);
        GenerationTaskRepository.FeatureAuditCounts counts = repository.featureAuditCounts(taskId);
        if (counts.totalWork() == counts.completedWork() && counts.permanentlyFailedWork() == 0
                && counts.candidateCount() > 0 && counts.coveredCandidateCount() != counts.candidateCount()) {
            reconcile(taskId, request);
        }
        return result(taskId, true);
    }

    private void throwIfCancellationRequested(String taskId) {
        if (repository.isCancellationRequested(taskId)) {
            throw new CancellationException("Cancellation requested between audit work items");
        }
    }

    private void processClaim(AuditWorkClaim claim, CreateGenerationTaskRequest request, MaterialInventoryUnit unit) {
        if ("FUNCTION_LIST".equals(unit.documentRole())) {
            if (claim.passNumber() != 1 || !"FEATURE_LIST_SCAN".equals(claim.stage())) {
                throw new IllegalStateException("Function-list audit work has an invalid pass or stage");
            }
            FeatureCandidateScanResult accepted = featureScanner.accept(unit, claim.passNumber(),
                    reconcile(request, featureScanner.promptFor(unit)));
            repository.persistScanAndCompleteAuditWork(claim, accepted.candidates(), List.of(), accepted.converged());
            return;
        }
        if (!("WORK_ORDER_PLAN".equals(unit.documentRole()) || "REQUIREMENT".equals(unit.documentRole()))
                || !"REQUIREMENT_SCAN".equals(claim.stage())) {
            throw new IllegalStateException("Requirement audit work has an invalid document role or stage");
        }
        List<FeatureSourceCandidate> acceptedFirstPass = claim.passNumber() == 1 ? List.of()
                : repository.featureSourceCandidates(claim.taskId(), claim.documentId(), claim.unitId(), 1);
        RequirementCandidateScanResult accepted = requirementScanner.accept(unit, claim.passNumber(), acceptedFirstPass,
                reconcile(request, requirementScanner.promptFor(unit, claim.passNumber(), acceptedFirstPass)));
        repository.persistScanAndCompleteAuditWork(claim, accepted.candidates(), accepted.duplicateOccurrences(), accepted.converged());
    }

    private void reconcile(String taskId, CreateGenerationTaskRequest request) {
        List<FeatureSourceCandidate> candidates = repository.featureSourceCandidates(taskId);
        Set<String> ids = candidates.stream().map(FeatureSourceCandidate::occurrenceId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<FeatureReviewConclusion> conclusions = conclusionParser.parse(reconcile(request, reconciliationPrompt(candidates)), ids);
        requireFormalEvidenceReferences(conclusions, candidates);
        repository.persistFeatureReviewConclusions(taskId, conclusions);
    }

    private String reconcile(CreateGenerationTaskRequest request, String prompt) {
        return knowledgeAgentPort.reconcileFeatures(new FeatureReconciliationInvocation(request.agentId(),
                request.requirementScope(), request.requirementAdmissionTypeKeys(), prompt)).terminalMarkdown();
    }

    private static String reconciliationPrompt(List<FeatureSourceCandidate> candidates) {
        StringBuilder prompt = new StringBuilder("仅基于以下已持久化的正式材料候选项进行双向核对；不得使用示例、不得引入材料外事实。\n")
                .append("只返回精确两张 Markdown 表。第一张必须为 `## 需求与功能清单审查发现`，表头必须为")
                .append(" `| 序号 | 对象/功能点 | 问题分类 | 证据对照 |`；第二张必须为 `## 测试用例` 且零数据行。\n")
                .append("问题分类仅可为：未发现问题、匹配、功能清单遗漏、需求未覆盖该功能点、冲突、拆分、合并、重复、证据不足。\n")
                .append("每个候选项必须且只能出现在一条第一表结论的证据对照中，使用精确 `candidateIds=id1,id2` token；")
                .append("每条结论还必须保留所覆盖候选项的精确 `documentId=` 与 `unitId=` 证据引用。\n候选项：\n");
        for (FeatureSourceCandidate candidate : candidates) {
            prompt.append("candidateId=").append(candidate.occurrenceId())
                    .append("; kind=").append(candidate.kind())
                    .append("; documentId=").append(candidate.documentId())
                    .append("; unitId=").append(candidate.unitId())
                    .append("; sourceOrdinal=").append(candidate.ordinal())
                    .append("; modelSequence=").append(candidate.modelSequence())
                    .append("; passNumber=").append(candidate.passNumber())
                    .append("; sourceRowPosition=").append(candidate.sourceRowPosition())
                    .append("; featureText=").append(oneLine(candidate.featureText()))
                    .append("; category=").append(oneLine(candidate.category()))
                    .append("; evidence=").append(oneLine(candidate.evidenceText())).append('\n');
        }
        return prompt.toString();
    }

    static void requireFormalEvidenceReferences(
            List<FeatureReviewConclusion> conclusions, List<FeatureSourceCandidate> candidates) {
        Map<String, FeatureSourceCandidate> byId = candidates.stream().collect(java.util.stream.Collectors.toMap(
                FeatureSourceCandidate::occurrenceId, candidate -> candidate));
        for (FeatureReviewConclusion conclusion : conclusions) {
            for (String candidateId : conclusion.candidateIds()) {
                FeatureSourceCandidate candidate = byId.get(candidateId);
                if (candidate == null || !containsExactCoordinatePair(
                        conclusion.evidenceText(), candidate.documentId(), candidate.unitId())) {
                    throw new IllegalArgumentException("Conclusion evidence must retain each candidate documentId and unitId");
                }
            }
        }
    }

    private static boolean containsExactCoordinatePair(String evidence, String expectedDocumentId, String expectedUnitId) {
        String previousDocumentId = null;
        for (String rawToken : evidence.split(";", -1)) {
            String token = rawToken.trim();
            int separator = token.indexOf('=');
            if (separator <= 0 || separator != token.lastIndexOf('=')) {
                previousDocumentId = null;
                continue;
            }
            String name = token.substring(0, separator);
            String value = token.substring(separator + 1);
            if ("documentId".equals(name) && !value.isBlank()) {
                previousDocumentId = value;
            } else if ("unitId".equals(name) && !value.isBlank()) {
                if (expectedDocumentId.equals(previousDocumentId) && expectedUnitId.equals(value)) return true;
                previousDocumentId = null;
            } else {
                previousDocumentId = null;
            }
        }
        return false;
    }

    private FeatureAuditResult result(String taskId, boolean inventoryComplete) {
        GenerationTaskRepository.FeatureAuditCounts counts = repository.featureAuditCounts(taskId);
        boolean complete = inventoryComplete && counts.totalWork() == counts.completedWork()
                && counts.permanentlyFailedWork() == 0 && counts.candidateCount() == counts.coveredCandidateCount();
        complete = complete && counts.candidateCount() > 0;
        return new FeatureAuditResult(inventoryComplete, counts.totalWork(), counts.completedWork(),
                counts.permanentlyFailedWork(), counts.candidateCount(), counts.conclusionCount(), complete);
    }

    private static Map<String, MaterialInventoryUnit> unitsByCoordinate(List<MaterialInventoryUnit> units) {
        Map<String, MaterialInventoryUnit> result = new LinkedHashMap<>();
        for (MaterialInventoryUnit unit : units) {
            MaterialInventoryUnit replaced = result.put(unit.documentId() + "\u001f" + unit.unitId(), unit);
            if (replaced != null) throw new IllegalStateException("Duplicate retained material unit coordinate");
        }
        return result;
    }

    private static MaterialInventoryUnit requiredUnit(Map<String, MaterialInventoryUnit> units, AuditWorkClaim claim) {
        MaterialInventoryUnit unit = units.get(claim.documentId() + "\u001f" + claim.unitId());
        if (unit == null) throw new IllegalStateException("Claimed audit work has no retained material unit");
        return unit;
    }

    private static String oneLine(String value) {
        return value.replace('\r', ' ').replace('\n', ' ').replace('|', '¦');
    }
}
