package com.testcaseagent.featureaudit;

import com.testcaseagent.diagnostics.WorkflowDiagnostics;
import com.testcaseagent.knowledgeagent.FeatureReconciliationInvocation;
import com.testcaseagent.knowledgeagent.KnowledgeAgentPort;
import com.testcaseagent.knowledgeagent.KnowledgeAgentSkillPreparationException;
import com.testcaseagent.task.CreateGenerationTaskRequest;
import com.testcaseagent.task.GenerationTaskRepository;
import java.nio.charset.StandardCharsets;
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
    private static final int RECONCILIATION_TARGET_PAGE_SIZE = 8;
    private static final int RECONCILIATION_PAGE_ATTEMPTS = 3;
    private static final String SAFE_RETRY_PREFIX = "上一轮未通过固定 Markdown 格式校验：";
    private static final String NORMALIZED_PATH_CONFLICT = "Each normalized business path must retain one groupAnchorId and conclusion type";
    private static final String NORMALIZED_PATH_RETRY_FEEDBACK = SAFE_RETRY_PREFIX
            + "按 NFKC、首尾 strip、连续空白折叠成一个空格并转为小写后相同的业务路径必须使用同一 groupAnchorId 和同一问题分类；"
            + "禁止将同一路径 self-anchor 成多个结论。";
    private static final Set<String> BUSINESS_PATH_CONTRACT_FAILURES = Set.of(
            "Only SPLIT conclusions may contain multiple business paths",
            "SPLIT conclusions require explicit <br> separated business paths",
            "Business path must not be blank",
            "Business paths must be plain text",
            "SPLIT conclusions require distinct business paths");
    private static final String BUSINESS_PATH_CONTRACT_RETRY_FEEDBACK = SAFE_RETRY_PREFIX
            + "业务路径结构必须符合拆分和非拆分结论的固定要求：非拆分不得含 <br>；拆分必须使用 literal <br> 分隔至少两项；"
            + "每项必须为非空纯文本，且归一化后必须互异。";
    private static final String NON_SPLIT_CHAPTER_NUMBER_RETRY_FEEDBACK = SAFE_RETRY_PREFIX
            + "对象/功能点列只能保留单一业务功能名。章节号、条款号和目录编号只能写在证据对照列，绝不能写入对象/功能点；"
            + "非拆分结论必须删除且不得复制证据中 <br> 后的章节号或条款号，不能在业务功能名后追加 <br> 加编号。";
    private static final String REPRESENTATIVE_BINDING_RETRY_FEEDBACK = SAFE_RETRY_PREFIX
            + "本页每个代表目标的 candidateId、documentId 和 unitId 必须逐字复制该代表目标绑定行；"
            + "禁止从全量候选上下文中同名或同路径的邻居复制 documentId 或 unitId。";
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
        int totalPages = (candidates.size() + RECONCILIATION_TARGET_PAGE_SIZE - 1) / RECONCILIATION_TARGET_PAGE_SIZE;
        for (int start = 0; start < candidates.size(); start += RECONCILIATION_TARGET_PAGE_SIZE) {
            List<FeatureSourceCandidate> targets = candidates.subList(start,
                    Math.min(start + RECONCILIATION_TARGET_PAGE_SIZE, candidates.size()));
            List<FeatureReviewConclusion> accepted = reconcilePage(taskId, request, candidates, ids, targets, acceptedByCandidate,
                    start / RECONCILIATION_TARGET_PAGE_SIZE + 1, totalPages);
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
            String taskId,
            CreateGenerationTaskRequest request, List<FeatureSourceCandidate> candidates, Set<String> allCandidateIds,
            List<FeatureSourceCandidate> targets, Map<String, FeatureReviewConclusion> acceptedByCandidate,
            int pageNumber, int totalPages) {
        List<TargetGroup> groups = targetGroups(targets, acceptedByCandidate);
        List<FeatureSourceCandidate> representatives = groups.stream().filter(group -> group.acceptedConclusion() == null)
                .map(TargetGroup::representative).toList();
        if (representatives.isEmpty()) {
            List<FeatureReviewConclusion> projected = projectGroupedConclusions(groups, Map.of(), candidates, targets, allCandidateIds);
            validatePageConclusions(projected, candidates, targets, allCandidateIds, acceptedByCandidate);
            return projected;
        }
        try {
            return reconcileRepresentatives(taskId, request, candidates, allCandidateIds, targets, acceptedByCandidate,
                    groups, representatives, pageNumber, totalPages, "");
        } catch (FinalReconciliationPageException failure) {
            if (!failure.approvedIsolatedRecheckCategoriesOnly()) {
                throw failure;
            }
            return reconcileRepresentativesIndividually(taskId, request, candidates, allCandidateIds, targets,
                    acceptedByCandidate, groups, pageNumber, totalPages);
        }
    }

    /**
     * Uses the normal bounded page request and keeps its result entirely unaccepted until all requested
     * representatives pass the existing parser, binding and cross-page invariants. [Req-ID]: REQ-BFA-007
     */
    private List<FeatureReviewConclusion> reconcileRepresentatives(
            String taskId, CreateGenerationTaskRequest request, List<FeatureSourceCandidate> candidates,
            Set<String> allCandidateIds, List<FeatureSourceCandidate> targets,
            Map<String, FeatureReviewConclusion> acceptedByCandidate, List<TargetGroup> groups,
            List<FeatureSourceCandidate> representatives, int pageNumber, int totalPages, String eventPrefix) {
        String prompt = reconciliationPrompt(candidates, representatives, acceptedByCandidate, allCandidateIds);
        String lastContractFailure = null;
        boolean approvedIsolatedRecheckCategoriesOnly = true;
        for (int attempt = 1; attempt <= RECONCILIATION_PAGE_ATTEMPTS; attempt++) {
            List<ExplicitBinding> bindings = List.of();
            List<FeatureReviewConclusion> parsed = List.of();
            List<FeatureReviewConclusion> projected = List.of();
            String markdown = null;
            try {
                WorkflowDiagnostics.reconciliation(taskId, pageNumber, totalPages, attempt, eventPrefix + "request", prompt);
                markdown = reconcile(request, prompt);
                WorkflowDiagnostics.reconciliation(taskId, pageNumber, totalPages, attempt, eventPrefix + "response", markdown);
                parsed = conclusionParser.parse(markdown, candidateIds(representatives));
                bindings = conflictingAcceptedBindings(parsed, acceptedByCandidate, allCandidateIds);
                Map<String, FeatureReviewConclusion> byRepresentative = conclusionsByCandidate(parsed);
                projected = projectGroupedConclusions(
                        groups, byRepresentative, candidates, targets, allCandidateIds);
                validatePageConclusions(projected, candidates, targets, allCandidateIds, acceptedByCandidate);
                return projected;
            } catch (IllegalArgumentException exception) {
                lastContractFailure = exception.getMessage();
                if (!FinalReconciliationPageException.Category.isApprovedIsolatedRecheckCategory(
                        FinalReconciliationPageException.Category.fromFixedContractFailure(lastContractFailure))) {
                    approvedIsolatedRecheckCategoriesOnly = false;
                }
                WorkflowDiagnostics.reconciliation(taskId, pageNumber, totalPages, attempt, eventPrefix + "rejected",
                        "failure=" + exception.getMessage() + "\nmodelMarkdown:\n" + markdown);
                if (attempt == RECONCILIATION_PAGE_ATTEMPTS) break;
                prompt = prompt + "\n重试纠正要求：" + finalReconciliationRetryFeedback(exception.getMessage())
                        + explicitBindingInstructions(bindings) + currentBatchConflictInstructions(parsed, exception.getMessage(), candidates)
                        + misusedAcceptedAnchorInstructions(projected, exception.getMessage(), acceptedByCandidate, allCandidateIds)
                        + "\n";
            } catch (RuntimeException exception) {
                WorkflowDiagnostics.reconciliation(taskId, pageNumber, totalPages, attempt, eventPrefix + "failed",
                        "failure=" + exception.getMessage() + "\nmodelMarkdown:\n" + markdown);
                throw exception;
            }
        }
        throw FinalReconciliationPageException.exhausted(
                pageNumber, totalPages, RECONCILIATION_PAGE_ATTEMPTS, lastContractFailure,
                approvedIsolatedRecheckCategoriesOnly);
    }

    /**
     * Rechecks only a batch exhausted exclusively by approved categories. Each representative gets a fresh isolated
     * request; no partial batch result is persisted or reused, and any singleton failure closes the entire batch.
     * [Req-ID]: REQ-BFA-007
     */
    private List<FeatureReviewConclusion> reconcileRepresentativesIndividually(
            String taskId, CreateGenerationTaskRequest request, List<FeatureSourceCandidate> candidates,
            Set<String> allCandidateIds, List<FeatureSourceCandidate> targets,
            Map<String, FeatureReviewConclusion> acceptedByCandidate, List<TargetGroup> groups,
            int pageNumber, int totalPages) {
        Map<String, FeatureReviewConclusion> byRepresentative = new LinkedHashMap<>();
        int representativeNumber = 0;
        int representativeCount = (int) groups.stream().filter(group -> group.acceptedConclusion() == null).count();
        for (TargetGroup group : groups) {
            if (group.acceptedConclusion() != null) continue;
            representativeNumber++;
            FeatureSourceCandidate representative = group.representative();
            TargetGroup singletonGroup = new TargetGroup(group.normalizedPath(), List.of(representative), null);
            try {
                List<FeatureReviewConclusion> singleton = reconcileRepresentatives(taskId, request, candidates,
                        allCandidateIds, List.of(representative), acceptedByCandidate, List.of(singletonGroup),
                        List.of(representative), pageNumber, totalPages, "compensation-");
                byRepresentative.put(representative.occurrenceId(), singleton.get(0));
            } catch (FinalReconciliationPageException failure) {
                throw FinalReconciliationPageException.singletonExhausted(pageNumber, totalPages, representativeNumber,
                        representativeCount, failure.attempts(), failure.category());
            }
        }
        List<FeatureReviewConclusion> projected = projectGroupedConclusions(groups, byRepresentative, candidates, targets,
                allCandidateIds);
        validatePageConclusions(projected, candidates, targets, allCandidateIds, acceptedByCandidate);
        return projected;
    }

    /**
     * Uses a model conclusion once per stable, normalized source feature path and deterministically rebinds it to every
     * same-path material occurrence. This prevents a model omitting a duplicate occurrence from weakening the exact
     * candidate coverage gate. [Req-ID]: REQ-BFA-003, REQ-BFA-007
     */
    private static List<TargetGroup> targetGroups(
            List<FeatureSourceCandidate> targets, Map<String, FeatureReviewConclusion> acceptedByCandidate) {
        Map<String, List<FeatureSourceCandidate>> membersByPath = new LinkedHashMap<>();
        for (FeatureSourceCandidate target : targets) {
            String normalizedPath = target.featureText() == null || target.featureText().isBlank()
                    ? "\u0000" + target.occurrenceId() : BusinessPathNormalizer.normalize(target.featureText());
            membersByPath.computeIfAbsent(normalizedPath, ignored -> new ArrayList<>()).add(target);
        }
        List<TargetGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<FeatureSourceCandidate>> entry : membersByPath.entrySet()) {
            FeatureReviewConclusion accepted = acceptedConclusionForPath(entry.getKey(), acceptedByCandidate);
            groups.add(new TargetGroup(entry.getKey(), List.copyOf(entry.getValue()), accepted));
        }
        return List.copyOf(groups);
    }

    private static FeatureReviewConclusion acceptedConclusionForPath(
            String normalizedPath, Map<String, FeatureReviewConclusion> acceptedByCandidate) {
        for (FeatureReviewConclusion conclusion : acceptedByCandidate.values()) {
            if (retainsSingleGroupedPath(conclusion, normalizedPath)) return conclusion;
        }
        return null;
    }

    private static Map<String, FeatureReviewConclusion> conclusionsByCandidate(List<FeatureReviewConclusion> conclusions) {
        Map<String, FeatureReviewConclusion> byCandidate = new LinkedHashMap<>();
        for (FeatureReviewConclusion conclusion : conclusions) {
            if (conclusion.candidateIds().size() != 1
                    || byCandidate.put(conclusion.candidateIds().get(0), conclusion) != null) {
                throw new IllegalArgumentException("Each page representative candidateId must have exactly one conclusion");
            }
        }
        return Map.copyOf(byCandidate);
    }

    private static List<FeatureReviewConclusion> projectGroupedConclusions(
            List<TargetGroup> groups, Map<String, FeatureReviewConclusion> byRepresentative,
            List<FeatureSourceCandidate> allCandidates, List<FeatureSourceCandidate> targets, Set<String> allCandidateIds) {
        Map<String, FeatureSourceCandidate> candidateById = new HashMap<>();
        for (FeatureSourceCandidate candidate : allCandidates) candidateById.put(candidate.occurrenceId(), candidate);
        Map<String, FeatureReviewConclusion> projectedByCandidate = new LinkedHashMap<>();
        for (TargetGroup group : groups) {
            FeatureReviewConclusion source = group.acceptedConclusion() == null
                    ? byRepresentative.get(group.representative().occurrenceId()) : group.acceptedConclusion();
            if (source == null) throw new IllegalArgumentException("Each page representative candidateId must have exactly one conclusion");
            if ((group.members().size() > 1 || group.acceptedConclusion() != null)
                    && !retainsSingleGroupedPath(source, group.normalizedPath())) {
                throw new IllegalArgumentException("Each grouped representative must retain its normalized business path");
            }
            for (FeatureSourceCandidate member : group.members()) {
                if (projectedByCandidate.put(member.occurrenceId(), source) != null) {
                    throw new IllegalArgumentException("Each page target candidateId must have exactly one conclusion");
                }
            }
        }
        List<FeatureReviewConclusion> projected = new ArrayList<>();
        int sequence = 1;
        for (FeatureSourceCandidate target : targets) {
            FeatureReviewConclusion source = projectedByCandidate.get(target.occurrenceId());
            if (source == null) throw new IllegalArgumentException("Each page target candidateId must have exactly one conclusion");
            FeatureSourceCandidate sourceCandidate = candidateById.get(source.candidateIds().get(0));
            String evidence = rebindEvidence(source, sourceCandidate, target, allCandidateIds);
            projected.add(new FeatureReviewConclusion(conclusionId(sequence, source.type(), source.explanation(), evidence,
                    List.of(target.occurrenceId())), sequence, source.type(), source.explanation(), evidence,
                    List.of(target.occurrenceId())));
            sequence++;
        }
        return List.copyOf(projected);
    }

    private static boolean retainsSingleGroupedPath(FeatureReviewConclusion conclusion, String normalizedPath) {
        return conclusion.type() != FeatureReviewConclusionType.SPLIT
                && normalizedPath.equals(BusinessPathNormalizer.normalize(conclusion.explanation()));
    }

    private static String rebindEvidence(
            FeatureReviewConclusion source, FeatureSourceCandidate sourceCandidate, FeatureSourceCandidate target,
            Set<String> allCandidateIds) {
        if (sourceCandidate == null) {
            throw new IllegalArgumentException("Each page representative candidateId must reference a retained candidate");
        }
        Map<String, String> machineTokens = new HashMap<>();
        for (String rawToken : source.evidenceText().split(";", -1)) {
            String token = rawToken.trim();
            int separator = token.indexOf('=');
            String name = separator > 0 && separator == token.lastIndexOf('=') ? token.substring(0, separator) : "";
            if (isMachineBindingToken(name)) {
                String value = token.substring(separator + 1);
                if (value.isBlank() || machineTokens.put(name, value) != null) {
                    throw new IllegalArgumentException("Grouped representative evidence must contain one exact binding token");
                }
            }
        }
        if (!sourceCandidate.documentId().equals(machineTokens.get("documentId"))
                || !sourceCandidate.unitId().equals(machineTokens.get("unitId"))) {
            throw new IllegalArgumentException("Grouped representative evidence must bind its exact documentId and unitId");
        }
        String boundCandidateId = machineTokens.get("candidateIds");
        if (boundCandidateId == null || !source.candidateIds().equals(List.of(boundCandidateId))) {
            throw new IllegalArgumentException("Grouped representative evidence must bind its exact candidateId");
        }
        String anchor = machineTokens.get("groupAnchorId");
        if (anchor == null || !allCandidateIds.contains(anchor)) {
            throw new IllegalArgumentException("Each groupAnchorId must reference a retained global candidate");
        }
        String evidence = "documentId=" + target.documentId() + "; unitId=" + target.unitId() + "; candidateIds="
                + target.occurrenceId() + "; groupAnchorId=" + anchor;
        List<String> readerEvidence = readerEvidence(target);
        return readerEvidence.isEmpty() ? evidence : evidence + "; " + String.join("; ", readerEvidence);
    }

    /**
     * Retains reader-facing evidence from the member's persisted candidate only. A representative model response may
     * establish its classification, but it must never relabel that representative's wording as another document/unit.
     */
    private static List<String> readerEvidence(FeatureSourceCandidate candidate) {
        List<String> readerEvidence = new ArrayList<>();
        for (String rawToken : candidate.evidenceText().split(";", -1)) {
            String token = rawToken.trim();
            int separator = token.indexOf('=');
            String name = separator > 0 && separator == token.lastIndexOf('=') ? token.substring(0, separator) : "";
            if (!token.isBlank() && !isMachineBindingToken(name)) {
                readerEvidence.add(token);
            }
        }
        return List.copyOf(readerEvidence);
    }

    private static boolean isMachineBindingToken(String name) {
        return "documentId".equals(name) || "unitId".equals(name) || "candidateIds".equals(name)
                || "groupAnchorId".equals(name);
    }

    private record TargetGroup(
            String normalizedPath, List<FeatureSourceCandidate> members, FeatureReviewConclusion acceptedConclusion) {
        FeatureSourceCandidate representative() { return members.get(0); }
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

    private static String reconciliationPrompt(
            List<FeatureSourceCandidate> candidates, List<FeatureSourceCandidate> targets,
            Map<String, FeatureReviewConclusion> acceptedByCandidate, Set<String> allCandidateIds) {
        StringBuilder prompt = new StringBuilder("仅基于以下已持久化的正式材料候选项进行双向核对；不得使用示例、不得引入材料外事实。\n")
                .append("全量候选项仅作比较上下文；本页目标已按归一化后的相同功能名称预分组，只列出每组最早的代表候选。")
                .append("本页只对下列代表目标输出结论。每个代表目标必须且只能占一条第一表结论，")
                .append("candidateIds 必须只包含该目标候选自身。跨页的同一业务结论必须使用同一个 groupAnchorId；")
                .append("每个本页代表目标绑定行中的 candidateId、documentId 和 unitId 必须逐字复制到其自身结论；")
                .append("禁止从全量候选上下文中同名或同路径的邻居复制 documentId 或 unitId。")
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
                .append("candidateIds 不得与 `<br>` 或说明文字粘连。对象/功能点列只能写业务功能名或业务路径；章节号、条款号和目录编号只能留在证据对照列，绝不能进入对象/功能点。")
                .append("分类为“拆分”时，对象/功能点必须以 literal `<br>` 分隔至少两个互异纯文本业务路径；其他分类必须为单一纯文本且不得含 `<br>`，不得复制证据中 `<br>` 后的章节号或条款号，也不得在功能名后追加 `<br>` 加编号。")
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
        if (!acceptedByCandidate.isEmpty()) {
            prompt.append("已接受的跨页结论：以下 self-anchor 行已通过严格校验。若本页目标与其中任一 businessPath 归一化后相同，"
                    + "必须逐字复制其 issueCategory 与 businessPath，并沿用其 groupAnchorId；不得自行改判、改写路径或 self-anchor。\n");
            for (Map.Entry<String, FeatureReviewConclusion> entry : acceptedByCandidate.entrySet()) {
                FeatureReviewConclusion conclusion = entry.getValue();
                String anchorId = groupAnchorId(conclusion.evidenceText(), allCandidateIds);
                if (!entry.getKey().equals(anchorId)) continue;
                prompt.append("candidateId=").append(anchorId)
                        .append("; groupAnchorId=").append(anchorId)
                        .append("; issueCategory=").append(chineseCategory(conclusion.type()))
                        .append("; businessPath=").append(oneLine(conclusion.explanation())).append('\n');
            }
        }
        prompt.append("本页目标候选：\n");
        for (FeatureSourceCandidate target : targets) {
            prompt.append("candidateId=").append(target.occurrenceId())
                    .append("; documentId=").append(target.documentId())
                    .append("; unitId=").append(target.unitId())
                    .append("; featureText=").append(oneLine(target.featureText())).append('\n');
        }
        return prompt.toString();
    }

    private static String chineseCategory(FeatureReviewConclusionType type) {
        return switch (type) {
            case MATCHED -> "匹配";
            case FUNCTION_LIST_MISSING -> "功能清单遗漏";
            case REQUIREMENT_MISSING -> "需求未覆盖该功能点";
            case CONFLICT -> "冲突";
            case SPLIT -> "拆分";
            case MERGE -> "合并";
            case DUPLICATE -> "重复";
            case INSUFFICIENT_EVIDENCE -> "证据不足";
        };
    }

    private static List<ExplicitBinding> conflictingAcceptedBindings(
            List<FeatureReviewConclusion> parsed, Map<String, FeatureReviewConclusion> accepted, Set<String> ids) {
        Map<String, ExplicitBinding> known = new HashMap<>();
        for (Map.Entry<String, FeatureReviewConclusion> entry : accepted.entrySet()) {
            FeatureReviewConclusion conclusion = entry.getValue();
            for (String path : businessPaths(conclusion)) {
                known.putIfAbsent(BusinessPathNormalizer.normalize(path), new ExplicitBinding(entry.getKey(),
                        groupAnchorId(conclusion.evidenceText(), ids), chineseCategory(conclusion.type()), conclusion.explanation()));
            }
        }
        List<ExplicitBinding> bindings = new ArrayList<>();
        for (FeatureReviewConclusion conclusion : parsed) {
            for (String path : businessPaths(conclusion)) {
                ExplicitBinding prior = known.get(BusinessPathNormalizer.normalize(path));
                if (prior != null && (!prior.category().equals(chineseCategory(conclusion.type()))
                        || !prior.path().equals(conclusion.explanation())
                        || !prior.anchorId().equals(groupAnchorId(conclusion.evidenceText(), ids)))) bindings.add(prior.forTarget(conclusion.candidateIds().get(0)));
            }
        }
        return List.copyOf(bindings);
    }

    private static String explicitBindingInstructions(List<ExplicitBinding> bindings) {
        if (bindings.isEmpty()) return "";
        StringBuilder text = new StringBuilder("\n本页强制先例：下列目标必须逐字按指定结论重写，不得改分类、路径或 anchor。\n");
        for (ExplicitBinding binding : bindings) text.append("targetCandidateId=").append(binding.targetId())
                .append("; issueCategory=").append(binding.category()).append("; businessPath=").append(binding.path())
                .append("; groupAnchorId=").append(binding.anchorId()).append('\n');
        return text.toString();
    }

    /** Provides only current-batch target IDs and correction constraints; it never chooses a semantic outcome. */
    private static String currentBatchConflictInstructions(
            List<FeatureReviewConclusion> conclusions, String failure, List<FeatureSourceCandidate> candidates) {
        if (!NORMALIZED_PATH_CONFLICT.equals(failure)) return "";
        Set<String> ids = candidateIds(candidates);
        Map<String, List<FeatureReviewConclusion>> byPath = new LinkedHashMap<>();
        for (FeatureReviewConclusion conclusion : conclusions) {
            for (String path : businessPaths(conclusion)) {
                byPath.computeIfAbsent(BusinessPathNormalizer.normalize(path), ignored -> new ArrayList<>()).add(conclusion);
            }
        }
        StringBuilder text = new StringBuilder("\n本批次归一化路径冲突目标：\n");
        boolean found = false;
        int groupNumber = 0;
        for (List<FeatureReviewConclusion> samePath : byPath.values()) {
            if (samePath.size() < 2) continue;
            Set<String> categoryAndAnchor = new LinkedHashSet<>();
            for (FeatureReviewConclusion conclusion : samePath) {
                categoryAndAnchor.add(chineseCategory(conclusion.type()) + "\u0000" + groupAnchorId(conclusion.evidenceText(), ids));
            }
            if (categoryAndAnchor.size() < 2) continue;
            Set<String> conflictingTargetIds = new LinkedHashSet<>();
            for (FeatureReviewConclusion conclusion : samePath) conflictingTargetIds.addAll(conclusion.candidateIds());
            List<String> targetIds = candidates.stream().map(FeatureSourceCandidate::occurrenceId)
                    .filter(conflictingTargetIds::contains).toList();
            if (targetIds.size() < 2) continue;
            groupNumber++;
            text.append("冲突组 ").append(groupNumber).append("：");
            for (String targetId : targetIds) text.append("targetCandidateId=").append(targetId).append("; ");
            String earliestTargetId = targetIds.get(0);
            text.append("earliestTargetCandidateId=").append(earliestTargetId)
                    .append("; requiredGroupAnchorId=").append(earliestTargetId).append('\n')
                    .append("固定规则：若仍判断为同一路径，每行 groupAnchorId 必须逐字复制该组 requiredGroupAnchorId 的实际值，")
                    .append("且分类和完整路径一致；")
                    .append("若不同，必须基于正式证据给出真实可区分的业务路径和各自合法 anchor。")
                    .append("不得由 Java 自动裁决、改写分类、业务路径或 anchor。\n");
            found = true;
        }
        return found ? text.toString() : "";
    }

    /** Adds only machine bindings after a projected current row misuses an accepted preceding-batch anchor. */
    private static String misusedAcceptedAnchorInstructions(
            List<FeatureReviewConclusion> projected, String failure,
            Map<String, FeatureReviewConclusion> acceptedByCandidate, Set<String> allCandidateIds) {
        if (!"Every anchored group must have one exact type and business path".equals(failure)) return "";
        StringBuilder text = new StringBuilder("\n本批次跨批 anchor 误用目标：\n");
        boolean found = false;
        for (FeatureReviewConclusion current : projected) {
            String currentId = current.candidateIds().get(0);
            String anchorId = groupAnchorId(current.evidenceText(), allCandidateIds);
            FeatureReviewConclusion acceptedAnchor = acceptedByCandidate.get(anchorId);
            if (acceptedAnchor == null || (current.type() == acceptedAnchor.type()
                    && current.explanation().equals(acceptedAnchor.explanation()))) continue;
            text.append("targetCandidateId=").append(currentId)
                    .append("; rejectedGroupAnchorId=").append(anchorId)
                    .append("; requiredSelfAnchorId=").append(currentId).append('\n');
            found = true;
        }
        if (!found) return "";
        return text.append("固定规则：若不逐字复用已接受先例区的 issueCategory 与完整 businessPath，groupAnchorId 必须逐字复制 ")
                .append("requiredSelfAnchorId 的实际值；若确属旧语义组，必须逐字复用分类、完整路径和旧 anchor。")
                .append("不得由 Java 自动裁决、改写分类、业务路径或 anchor。\n").toString();
    }

    private record ExplicitBinding(String targetId, String anchorId, String category, String path) {
        ExplicitBinding forTarget(String target) { return new ExplicitBinding(target, anchorId, category, path); }
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
                PathDisposition prior = dispositionByNormalizedPath.putIfAbsent(BusinessPathNormalizer.normalize(path), disposition);
                if (prior != null && !prior.equals(disposition)) {
                    throw new IllegalArgumentException(NORMALIZED_PATH_CONFLICT);
                }
            }
        }
    }

    private static List<String> businessPaths(FeatureReviewConclusion conclusion) {
        return BusinessPathNormalizer.parseAndValidate(conclusion.type(), conclusion.explanation());
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
        if ("Grouped representative evidence must bind its exact documentId and unitId".equals(failure)) {
            return COMPREHENSIVE_RETRY_BASELINE + "\n" + REPRESENTATIVE_BINDING_RETRY_FEEDBACK;
        }
        if (NORMALIZED_PATH_CONFLICT.equals(failure)) {
            return COMPREHENSIVE_RETRY_BASELINE + "\n" + NORMALIZED_PATH_RETRY_FEEDBACK;
        }
        if ("Only SPLIT conclusions may contain multiple business paths".equals(failure)) {
            return COMPREHENSIVE_RETRY_BASELINE + "\n" + NON_SPLIT_CHAPTER_NUMBER_RETRY_FEEDBACK;
        }
        if (BUSINESS_PATH_CONTRACT_FAILURES.contains(failure)) {
            return COMPREHENSIVE_RETRY_BASELINE + "\n" + BUSINESS_PATH_CONTRACT_RETRY_FEEDBACK;
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
