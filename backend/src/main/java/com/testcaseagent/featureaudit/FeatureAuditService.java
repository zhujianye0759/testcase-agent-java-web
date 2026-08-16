package com.testcaseagent.featureaudit;

import com.testcaseagent.knowledgeagent.FeatureReconciliationInvocation;
import com.testcaseagent.knowledgeagent.KnowledgeAgentPort;
import com.testcaseagent.knowledgeagent.KnowledgeAgentSkillPreparationException;
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
    private static final String SAFE_RETRY_PREFIX = "上一轮未通过固定 Markdown 格式校验：";
    private static final String COMPREHENSIVE_RETRY_BASELINE = "固定格式基线：必须返回精确两张 Markdown 表；标题、表头和分隔行必须与本次提示完全一致；"
            + "第一张表每个非空数据行必须恰好四列；不得返回 JSON 或代码围栏；表格单元格中仅允许 <br>；"
            + "第二张表必须为零数据行且不得有尾随内容；每个非空第一表行的证据对照必须先使用本次提示已提供的精确 documentId 坐标标记，"
            + "再使用精确 unitId 坐标标记；两个坐标各出现一次且不得变形，第二个坐标后必须以分号接证据正文；不得输出任何占位符。";
    private static final Map<String, String> SAFE_MARKDOWN_RETRY_FEEDBACK = Map.ofEntries(
            Map.entry("Expected strict scan Markdown with two Markdown tables", SAFE_RETRY_PREFIX + "必须返回精确两张 Markdown 表。"),
            Map.entry("Expected strict scan Markdown with Markdown tables instead of JSON or code fences",
                    SAFE_RETRY_PREFIX + "不得返回 JSON 或代码围栏，必须返回 Markdown 表。"),
            Map.entry("Expected strict scan Markdown with only <br> HTML in table cells",
                    SAFE_RETRY_PREFIX + "表格单元格中只允许使用 <br>，不得输出其他 HTML 标签。"),
            Map.entry("Expected strict scan Markdown with heading ## 需求与功能清单审查发现",
                    SAFE_RETRY_PREFIX + "必须使用本次给出的两张精确表标题。"),
            Map.entry("Expected strict scan Markdown with exact table header",
                    SAFE_RETRY_PREFIX + "必须使用本次给出的精确表头。"),
            Map.entry("Expected strict scan Markdown with table separator",
                    SAFE_RETRY_PREFIX + "每张表必须使用完整的 Markdown 分隔行。"),
            Map.entry("Expected strict scan Markdown with Markdown table row",
                    SAFE_RETRY_PREFIX + "每一行必须是完整的 Markdown 表格行。"),
            Map.entry("Expected strict scan Markdown with exact table column count",
                    SAFE_RETRY_PREFIX + "第一张表的每个非空数据行必须恰好四列。"),
            Map.entry("Expected strict scan Markdown with zero test-case rows and no trailing content",
                    SAFE_RETRY_PREFIX + "第二张表必须为零数据行，表后不得附加内容。"),
            Map.entry("Expected strict scan Markdown with positive visible sequence",
                    SAFE_RETRY_PREFIX + "第一列必须是正整数的可见序号。"),
            Map.entry("Expected strict scan Markdown with numeric visible sequence",
                    SAFE_RETRY_PREFIX + "第一列必须只填写数字序号。"),
            Map.entry("Expected strict scan Markdown with non-blank 对象/功能点",
                    SAFE_RETRY_PREFIX + "对象/功能点列不得为空。"),
            Map.entry("Expected strict scan Markdown with non-blank 问题分类",
                    SAFE_RETRY_PREFIX + "问题分类列不得为空。"),
            Map.entry("Expected strict scan Markdown with non-blank 证据对照",
                    SAFE_RETRY_PREFIX + "证据对照列不得为空。"),
            Map.entry("Candidate evidence has duplicate or malformed coordinate tokens",
                    SAFE_RETRY_PREFIX + "证据列中的两个坐标标记必须各出现一次且格式正确。"),
            Map.entry("Candidate evidence must bind the exact documentId and unitId",
                    SAFE_RETRY_PREFIX + "证据列必须绑定本次提示给出的两个精确坐标标记。"));

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
            } catch (KnowledgeAgentSkillPreparationException exception) {
                repository.failAuditWork(claim, exception.getMessage(), false);
                throw exception;
            } catch (RuntimeException exception) {
                repository.failAuditWork(claim, exception.getMessage(), true);
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
                    reconcile(request, withRetryFeedback(featureScanner.promptFor(unit), claim)));
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
                reconcile(request, withRetryFeedback(
                        requirementScanner.promptFor(unit, claim.passNumber(), acceptedFirstPass), claim)));
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
        FeatureReconciliationInvocation invocation = new FeatureReconciliationInvocation(request.agentId(),
                request.requirementScope(), request.requirementAdmissionTypeKeys(), prompt);
        knowledgeAgentPort.prepareReconciliationSession(invocation);
        try {
            return knowledgeAgentPort.reconcileFeatures(invocation).terminalMarkdown();
        } finally {
            knowledgeAgentPort.closePreparedSession();
        }
    }

    private static String reconciliationPrompt(List<FeatureSourceCandidate> candidates) {
        StringBuilder prompt = new StringBuilder("仅基于以下已持久化的正式材料候选项进行双向核对；不得使用示例、不得引入材料外事实。\n")
                .append("只返回精确两张 Markdown 表。第一张必须为 `## 需求与功能清单审查发现`，表头必须为")
                .append(" `| 序号 | 对象/功能点 | 问题分类 | 证据对照 |`；第二张必须为 `## 测试用例` 且零数据行。\n")
                .append("问题分类仅可为：未发现问题、匹配、功能清单遗漏、需求未覆盖该功能点、冲突、拆分、合并、重复、证据不足。\n")
                .append("每行证据对照中的机器 token 必须是独立分号段：至少 `documentId=<exact>; unitId=<exact>; candidateIds=id1,id2; <reader evidence>`。")
                .append("candidateIds 不得与 `<br>` 或说明文字粘连。分类为“拆分”时，对象/功能点必须以 literal `<br>` 分隔至少两个互异纯文本业务路径；其他分类必须为单一纯文本且不得含 `<br>`。")
                .append("同一行不同层级列的非空值按列顺序构成业务路径，不同层级值不是冲突；冲突仅限同一层级或同一语义字段互斥，或跨正式材料对同一路径不兼容。无表头或层级语义不足时归为证据不足。")
                .append("每个候选项必须且只能出现在一条第一表结论中。\n候选项：\n");
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

    /** Adds only a fixed, non-sensitive format correction to a reclaimed scan work item. [Req-ID]: REQ-BFA-006 */
    private static String withRetryFeedback(String prompt, AuditWorkClaim claim) {
        if (claim.attemptNumber() <= 1) return prompt;
        String previousFailureSummary = claim.previousFailureSummary();
        String feedback = COMPREHENSIVE_RETRY_BASELINE;
        if (previousFailureSummary != null && !previousFailureSummary.isBlank()) {
            String focus = SAFE_MARKDOWN_RETRY_FEEDBACK.get(previousFailureSummary);
            if (focus != null) feedback += "\n本次重点：" + focus;
        }
        return prompt + "\n重领纠正要求：" + feedback + "\n";
    }
}
