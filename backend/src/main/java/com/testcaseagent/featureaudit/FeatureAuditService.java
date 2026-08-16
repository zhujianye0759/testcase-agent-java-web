package com.testcaseagent.featureaudit;

import com.testcaseagent.knowledgeagent.FeatureReconciliationInvocation;
import com.testcaseagent.knowledgeagent.KnowledgeAgentPort;
import com.testcaseagent.knowledgeagent.KnowledgeAgentSkillPreparationException;
import com.testcaseagent.task.CreateGenerationTaskRequest;
import com.testcaseagent.task.GenerationTaskRepository;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * Coordinates durable, material-bounded candidate scans and the one final formal-scope reconciliation.
 *
 * <p>All remote calls use the reconciliation port, whose adapter is the stage Skill/SSE acceptance boundary. This
 * service deliberately never passes examples: candidate and conclusion facts originate exclusively in the retained
 * requirement material inventory.</p>
 *
 * [Req-ID]: REQ-KSI-001, REQ-KSI-002, REQ-KSI-003, REQ-BFA-001, REQ-BFA-002, REQ-BFA-003, REQ-BFA-004, REQ-BFA-007
 */
public final class FeatureAuditService {
    private static final Duration AUDIT_LEASE = Duration.ofMinutes(5);
    private static final int RECONCILIATION_TARGET_PAGE_SIZE = 16;
    private static final int RECONCILIATION_PAGE_ATTEMPTS = 3;
    private static final String SAFE_RETRY_PREFIX = "上一轮未通过固定 Markdown 格式校验：";
    private static final String NORMALIZED_PATH_CONFLICT = "Each normalized business path must retain one groupAnchorId and conclusion type";
    private static final String NORMALIZED_PATH_RETRY_FEEDBACK = SAFE_RETRY_PREFIX
            + "按 NFKC、首尾 strip、连续空白折叠成一个空格并转为小写后相同的业务路径必须使用同一 groupAnchorId 和同一问题分类；"
            + "禁止将同一路径 self-anchor 成多个结论。";
    private static final String COMPREHENSIVE_RETRY_BASELINE = "固定格式基线：输出第一个字符必须是 #，第一行必须精确为 ## 需求与功能清单审查发现，"
            + "第一标题前不得有分析、说明、结论或引导语；必须返回精确两张 Markdown 表；标题、表头和分隔行必须与本次提示完全一致；"
            + "第一张表每个非空数据行必须恰好四列；不得返回 JSON 或代码围栏；表格单元格中仅允许 <br>；"
            + "整份输出只能包含两张表；第一表最后一行后的下一非空行必须直接是 ## 测试用例；不得输出思考过程、Wait/Let's、复核说明、自我纠错、重复标题或重复表；"
            + "若判断无新增项，直接返回零数据行第一表，不得先列暂定行再解释/撤销；第二张表必须为零数据行且不得有尾随内容；"
            + "每个非空第一表行的证据对照必须先使用本次提示已提供的精确 documentId 坐标标记，"
            + "再使用精确 unitId 坐标标记；两个坐标各出现一次且不得变形，第二个坐标后必须以分号接证据正文；不得输出任何占位符。";
    private static final Map<String, String> SAFE_MARKDOWN_RETRY_FEEDBACK = Map.ofEntries(
            Map.entry("Expected strict scan Markdown with two Markdown tables", SAFE_RETRY_PREFIX + "必须返回精确两张 Markdown 表。"),
            Map.entry("Expected strict scan Markdown with Markdown tables instead of JSON or code fences",
                    SAFE_RETRY_PREFIX + "不得返回 JSON 或代码围栏，必须返回 Markdown 表。"),
            Map.entry("Expected strict scan Markdown with only <br> HTML in table cells",
                    SAFE_RETRY_PREFIX + "表格单元格中只允许使用 <br>，不得输出其他 HTML 标签。"),
            Map.entry("Expected strict scan Markdown with heading ## 需求与功能清单审查发现",
                    SAFE_RETRY_PREFIX + "删除标题前的任何分析、说明、结论或引导语；输出第一个字符必须是 #，第一行必须精确为 ## 需求与功能清单审查发现。"),
            Map.entry("Expected strict scan Markdown with heading ## 测试用例",
                    SAFE_RETRY_PREFIX + "整份输出只能包含两张表；第一表最后一行后的下一非空行必须直接是 ## 测试用例；"
                            + "删除思考过程、Wait/Let's、复核说明、自我纠错、重复标题或重复表；若无新增项，直接返回零数据行第一表，不得先列暂定行再解释/撤销。"),
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
    private static final Map<String, String> SAFE_FINAL_RECONCILIATION_RETRY_FEEDBACK = Map.ofEntries(
            Map.entry("each retained candidateId exactly once", SAFE_RETRY_PREFIX + "本页每个目标候选必须且只能出现一次。"),
            Map.entry("only retained candidateIds", SAFE_RETRY_PREFIX + "candidateIds 只能填写本页目标候选，且每行只能填写一个。"),
            Map.entry("candidateIds= token", SAFE_RETRY_PREFIX + "每行证据对照必须有一个独立的 candidateIds= 机器 token。"),
            Map.entry("known terminal conclusion type", SAFE_RETRY_PREFIX + "问题分类只能使用本次提示列出的中文分类。"),
            Map.entry("groupAnchorId", SAFE_RETRY_PREFIX + "每行必须有一个独立且有效的 groupAnchorId= 机器 token。"),
            Map.entry("Every anchored group", SAFE_RETRY_PREFIX + "默认每个目标 candidateId 都必须令 groupAnchorId 等于自身 candidateId；"
                    + "仅当对象/功能点和问题分类与既有 anchor 行逐字完全相同时，才允许复用更早的 groupAnchorId；"
                    + "只要任一不同，必须 self-anchor；不得因为同一 unitId、documentId、大模块或问题分类而批量复用 groupAnchorId。"),
            Map.entry("Each normalized business path", NORMALIZED_PATH_RETRY_FEEDBACK));

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
        Set<String> ids = candidateIds(candidates);
        List<FeatureReviewConclusion> pageConclusions = new ArrayList<>();
        Map<String, FeatureReviewConclusion> acceptedByCandidate = new LinkedHashMap<>();
        for (int start = 0; start < candidates.size(); start += RECONCILIATION_TARGET_PAGE_SIZE) {
            List<FeatureSourceCandidate> targets = candidates.subList(start,
                    Math.min(start + RECONCILIATION_TARGET_PAGE_SIZE, candidates.size()));
            List<FeatureReviewConclusion> accepted = reconcilePage(request, candidates, ids, targets, acceptedByCandidate);
            for (FeatureReviewConclusion conclusion : accepted) {
                acceptedByCandidate.put(conclusion.candidateIds().get(0), conclusion);
            }
            pageConclusions.addAll(accepted);
        }
        List<FeatureReviewConclusion> conclusions = mergeAnchoredConclusions(candidates, ids, pageConclusions);
        requireFormalEvidenceReferences(conclusions, candidates);
        repository.persistFeatureReviewConclusions(taskId, conclusions);
    }

    private List<FeatureReviewConclusion> reconcilePage(
            CreateGenerationTaskRequest request, List<FeatureSourceCandidate> candidates, Set<String> allCandidateIds,
            List<FeatureSourceCandidate> targets, Map<String, FeatureReviewConclusion> acceptedByCandidate) {
        Set<String> targetIds = candidateIds(targets);
        String prompt = reconciliationPrompt(candidates, targets);
        for (int attempt = 1; attempt <= RECONCILIATION_PAGE_ATTEMPTS; attempt++) {
            try {
                List<FeatureReviewConclusion> parsed = conclusionParser.parse(reconcile(request, prompt), targetIds);
                validatePageConclusions(parsed, candidates, targets, allCandidateIds, acceptedByCandidate);
                return parsed;
            } catch (IllegalArgumentException exception) {
                if (attempt == RECONCILIATION_PAGE_ATTEMPTS) break;
                prompt = prompt + "\n重试纠正要求：" + finalReconciliationRetryFeedback(exception.getMessage()) + "\n";
            }
        }
        throw new IllegalStateException("Final reconciliation page did not meet the strict contract after three attempts");
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

    private static String reconciliationPrompt(List<FeatureSourceCandidate> candidates, List<FeatureSourceCandidate> targets) {
        StringBuilder prompt = new StringBuilder("仅基于以下已持久化的正式材料候选项进行双向核对；不得使用示例、不得引入材料外事实。\n")
                .append("全量候选项仅作比较上下文；本页只对下列目标候选输出结论。每个目标候选必须且只能占一条第一表结论，")
                .append("candidateIds 必须只包含该目标候选自身。跨页的同一业务结论必须使用同一个 groupAnchorId；")
                .append("默认每个目标 candidateId 都必须令 groupAnchorId 等于自身 candidateId；")
                .append("仅当对象/功能点和问题分类与既有 anchor 行逐字完全相同时，才允许复用更早的 groupAnchorId；")
                .append("只要任一不同，必须 self-anchor；不得因为同一 unitId、documentId、大模块或问题分类而批量复用 groupAnchorId。")
                .append("同组必须按全量候选项给出的顺序选择最早的 candidateId 作为 groupAnchorId，且该 anchor 自身行必须令 groupAnchorId 等于自身 candidateId；")
                .append("同一 groupAnchorId 的问题分类和对象/功能点业务路径必须逐字完全一致。")
                .append("按 NFKC、首尾 strip、连续空白折叠成一个空格并转为小写后相同的任一业务路径（拆分中的每条路径逐条计算）"
                        + "必须使用同一 groupAnchorId 和同一问题分类；禁止将同一路径 self-anchor 成多个结论。\n")
                .append("只返回精确两张 Markdown 表。第一张必须为 `## 需求与功能清单审查发现`，表头必须为")
                .append(" `| 序号 | 对象/功能点 | 问题分类 | 证据对照 |`；第二张必须为 `## 测试用例` 且零数据行。\n")
                .append("问题分类仅可为：未发现问题、匹配、功能清单遗漏、需求未覆盖该功能点、冲突、拆分、合并、重复、证据不足。\n")
                .append("每行证据对照中的机器 token 必须是独立分号段：至少 `documentId=<exact>; unitId=<exact>; candidateIds=<本页目标 candidateId>; groupAnchorId=<全量 candidateId>; <reader evidence>`。")
                .append("candidateIds 不得与 `<br>` 或说明文字粘连。分类为“拆分”时，对象/功能点必须以 literal `<br>` 分隔至少两个互异纯文本业务路径；其他分类必须为单一纯文本且不得含 `<br>`。")
                .append("同一行不同层级列的非空值按列顺序构成业务路径，不同层级值不是冲突；冲突仅限同一层级或同一语义字段互斥，或跨正式材料对同一路径不兼容。无表头或层级语义不足时归为证据不足。")
                .append("\n全量候选项：\n");
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
        prompt.append("本页目标候选：\n");
        for (FeatureSourceCandidate target : targets) {
            prompt.append("candidateId=").append(target.occurrenceId()).append('\n');
        }
        return prompt.toString();
    }

    private static Set<String> candidateIds(List<FeatureSourceCandidate> candidates) {
        Set<String> ids = new LinkedHashSet<>();
        for (FeatureSourceCandidate candidate : candidates) {
            if (!ids.add(candidate.occurrenceId())) throw new IllegalArgumentException("Candidate occurrence ids must be unique");
        }
        return Set.copyOf(ids);
    }

    private static void validatePageConclusions(
            List<FeatureReviewConclusion> conclusions, List<FeatureSourceCandidate> candidates,
            List<FeatureSourceCandidate> targets, Set<String> allCandidateIds,
            Map<String, FeatureReviewConclusion> acceptedByCandidate) {
        Map<String, FeatureReviewConclusion> pageByCandidate = new HashMap<>();
        Map<String, Integer> candidatePositions = new HashMap<>();
        for (int index = 0; index < candidates.size(); index++) {
            candidatePositions.put(candidates.get(index).occurrenceId(), index);
        }
        for (FeatureReviewConclusion conclusion : conclusions) {
            if (conclusion.candidateIds().size() != 1) {
                throw new IllegalArgumentException("Each page conclusion must contain exactly one target candidateId");
            }
            String candidateId = conclusion.candidateIds().get(0);
            if (pageByCandidate.put(candidateId, conclusion) != null) {
                throw new IllegalArgumentException("Each page target candidateId must have exactly one conclusion");
            }
            groupAnchorId(conclusion.evidenceText(), allCandidateIds);
        }
        for (FeatureSourceCandidate target : targets) {
            if (!pageByCandidate.containsKey(target.occurrenceId())) {
                throw new IllegalArgumentException("Each page target candidateId must have exactly one conclusion");
            }
        }
        Map<String, FeatureReviewConclusion> knownByCandidate = new HashMap<>(acceptedByCandidate);
        knownByCandidate.putAll(pageByCandidate);
        for (FeatureSourceCandidate target : targets) {
            FeatureReviewConclusion current = pageByCandidate.get(target.occurrenceId());
            String anchorId = groupAnchorId(current.evidenceText(), allCandidateIds);
            if (candidatePositions.get(anchorId) > candidatePositions.get(target.occurrenceId())) {
                throw new IllegalArgumentException("Each groupAnchorId must not point after its target candidate");
            }
            FeatureReviewConclusion anchor = knownByCandidate.get(anchorId);
            if (anchor == null || !anchorId.equals(groupAnchorId(anchor.evidenceText(), allCandidateIds))) {
                throw new IllegalArgumentException("Each groupAnchorId must reference a self-anchored global candidate");
            }
            if (current.type() != anchor.type() || !current.explanation().equals(anchor.explanation())) {
                throw new IllegalArgumentException("Every anchored group must have one exact type and business path");
            }
        }
        Map<String, PathDisposition> dispositionByNormalizedPath = new HashMap<>();
        for (FeatureSourceCandidate candidate : candidates) {
            FeatureReviewConclusion conclusion = knownByCandidate.get(candidate.occurrenceId());
            if (conclusion == null) continue;
            PathDisposition disposition = new PathDisposition(
                    groupAnchorId(conclusion.evidenceText(), allCandidateIds), conclusion.type());
            for (String path : businessPaths(conclusion)) {
                PathDisposition prior = dispositionByNormalizedPath.putIfAbsent(normalizeBusinessPath(path), disposition);
                if (prior != null && !prior.equals(disposition)) {
                    throw new IllegalArgumentException(NORMALIZED_PATH_CONFLICT);
                }
            }
        }
    }

    private static List<String> businessPaths(FeatureReviewConclusion conclusion) {
        if (conclusion.type() != FeatureReviewConclusionType.SPLIT) return List.of(conclusion.explanation());
        return List.of(conclusion.explanation().split("<br>", -1));
    }

    private static String normalizeBusinessPath(String path) {
        return Normalizer.normalize(path, Normalizer.Form.NFKC).strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private record PathDisposition(String anchorId, FeatureReviewConclusionType type) { }

    private static List<FeatureReviewConclusion> mergeAnchoredConclusions(
            List<FeatureSourceCandidate> candidates, Set<String> allCandidateIds, List<FeatureReviewConclusion> pageConclusions) {
        Map<String, FeatureReviewConclusion> byCandidate = new HashMap<>();
        Map<String, String> anchorByCandidate = new HashMap<>();
        for (FeatureReviewConclusion conclusion : pageConclusions) {
            if (conclusion.candidateIds().size() != 1) {
                throw new IllegalArgumentException("Each page conclusion must contain exactly one target candidateId");
            }
            String candidateId = conclusion.candidateIds().get(0);
            if (!allCandidateIds.contains(candidateId) || byCandidate.put(candidateId, conclusion) != null) {
                throw new IllegalArgumentException("Every retained candidate requires exactly one page conclusion");
            }
            anchorByCandidate.put(candidateId, groupAnchorId(conclusion.evidenceText(), allCandidateIds));
        }
        if (!byCandidate.keySet().equals(allCandidateIds)) {
            throw new IllegalArgumentException("Every retained candidate requires exactly one page conclusion");
        }

        Map<String, List<String>> membersByAnchor = new LinkedHashMap<>();
        for (FeatureSourceCandidate candidate : candidates) {
            String candidateId = candidate.occurrenceId();
            String anchorId = anchorByCandidate.get(candidateId);
            FeatureReviewConclusion anchor = byCandidate.get(anchorId);
            if (anchor == null || !anchorId.equals(anchorByCandidate.get(anchorId))) {
                throw new IllegalArgumentException("Each groupAnchorId must reference a self-anchored global candidate");
            }
            FeatureReviewConclusion current = byCandidate.get(candidateId);
            if (current.type() != anchor.type() || !current.explanation().equals(anchor.explanation())) {
                throw new IllegalArgumentException("Every anchored group must have one exact type and business path");
            }
            membersByAnchor.computeIfAbsent(anchorId, ignored -> new ArrayList<>()).add(candidateId);
        }

        List<FeatureReviewConclusion> merged = new ArrayList<>();
        int sequence = 1;
        for (FeatureSourceCandidate candidate : candidates) {
            List<String> memberIds = membersByAnchor.remove(candidate.occurrenceId());
            if (memberIds == null) continue;
            FeatureReviewConclusion anchor = byCandidate.get(candidate.occurrenceId());
            String evidence = memberIds.stream().map(id -> byCandidate.get(id).evidenceText())
                    .collect(java.util.stream.Collectors.joining("; "));
            merged.add(new FeatureReviewConclusion(conclusionId(sequence, anchor.type(), anchor.explanation(), evidence, memberIds),
                    sequence, anchor.type(), anchor.explanation(), evidence, List.copyOf(memberIds)));
            sequence++;
        }
        if (!membersByAnchor.isEmpty()) throw new IllegalArgumentException("Every anchored group must close on a global candidate");
        return List.copyOf(merged);
    }

    private static String groupAnchorId(String evidence, Set<String> allCandidateIds) {
        String anchor = null;
        for (String rawToken : evidence.split(";", -1)) {
            String token = rawToken.trim();
            int separator = token.indexOf('=');
            if (separator <= 0 || separator != token.lastIndexOf('=')) continue;
            if (!"groupAnchorId".equals(token.substring(0, separator))) continue;
            String value = token.substring(separator + 1);
            if (value.isBlank() || anchor != null) throw new IllegalArgumentException("Each conclusion must contain one valid groupAnchorId");
            anchor = value;
        }
        if (anchor == null || !allCandidateIds.contains(anchor)) {
            throw new IllegalArgumentException("Each groupAnchorId must reference a retained global candidate");
        }
        return anchor;
    }

    private static String conclusionId(int sequence, FeatureReviewConclusionType type, String explanation,
            String evidence, List<String> candidateIds) {
        try {
            String identity = sequence + "\u001f" + type + "\u001f" + explanation + "\u001f" + evidence + "\u001f"
                    + String.join(",", candidateIds);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static String finalReconciliationRetryFeedback(String failure) {
        if (NORMALIZED_PATH_CONFLICT.equals(failure)) {
            return COMPREHENSIVE_RETRY_BASELINE + "\n" + NORMALIZED_PATH_RETRY_FEEDBACK;
        }
        String focus = SAFE_FINAL_RECONCILIATION_RETRY_FEEDBACK.entrySet().stream()
                .filter(entry -> failure != null && failure.contains(entry.getKey())).map(Map.Entry::getValue).findFirst()
                .orElse(SAFE_RETRY_PREFIX + "请逐条遵守本页目标、锚点和两张 Markdown 表的固定合同。不得回显错误或说明。 ");
        return COMPREHENSIVE_RETRY_BASELINE + "\n" + focus;
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
