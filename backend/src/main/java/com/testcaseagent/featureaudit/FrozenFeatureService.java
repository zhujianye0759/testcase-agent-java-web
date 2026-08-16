package com.testcaseagent.featureaudit;

import com.testcaseagent.scope.RequirementScope;
import com.testcaseagent.task.GenerationTaskRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Freezes the complete, reconciled union before any generation batch is planned.
 *
 * <p>The service has no model dependency: it accepts only the durable inventory, candidates, and conclusions that
 * preceding stages have already closed. A conflict has no structured resolution field in the present contract, so it
 * is deliberately rejected instead of being guessed into a business path.</p>
 *
 * [Req-ID]: REQ-BFA-005
 */
public final class FrozenFeatureService {
    private final GenerationTaskRepository repository;

    public FrozenFeatureService(GenerationTaskRepository repository) {
        this.repository = repository;
    }

    /**
     * Returns an existing immutable freeze or atomically persists a newly validated deterministic one.
     *
     * @throws IllegalStateException when source traversal, audit work, candidate coverage, or conflict resolution is incomplete
     */
    public FrozenFeatureResult freeze(String taskId, RequirementScope requirementScope) {
        GenerationTaskRepository.FeatureAuditCounts counts = requireReadyForFreeze(taskId, requirementScope);
        List<FrozenFeatureTarget> retained = repository.frozenFeatureTargets(taskId);
        if (!retained.isEmpty()) return new FrozenFeatureResult(true, retained);

        List<FeatureSourceCandidate> candidates = repository.featureSourceCandidates(taskId);
        List<FeatureReviewConclusion> conclusions = repository.featureReviewConclusions(taskId);
        if (candidates.size() != counts.candidateCount() || conclusions.size() != counts.conclusionCount()) {
            throw new IllegalStateException("Retained audit ledger counts changed before feature freeze");
        }
        List<FrozenFeatureTarget> targets = buildTargets(candidates, conclusions);
        repository.persistFrozenFeatureTargets(taskId, targets);
        return new FrozenFeatureResult(true, targets);
    }

    private GenerationTaskRepository.FeatureAuditCounts requireReadyForFreeze(String taskId, RequirementScope requirementScope) {
        if (!repository.hasCompleteMaterialInventory(taskId, requirementScope)) {
            throw new IllegalStateException("Material inventory is not complete");
        }
        GenerationTaskRepository.FeatureAuditCounts counts = repository.featureAuditCounts(taskId);
        if (counts.totalWork() == 0 || counts.totalWork() != counts.completedWork() || counts.permanentlyFailedWork() != 0) {
            throw new IllegalStateException("Audit work is not terminally complete");
        }
        if (counts.candidateCount() == 0 || counts.candidateCount() != counts.coveredCandidateCount()
                || counts.conclusionCount() == 0) {
            throw new IllegalStateException("Candidate conclusions are not terminally complete");
        }
        return counts;
    }

    private static List<FrozenFeatureTarget> buildTargets(
            List<FeatureSourceCandidate> candidates, List<FeatureReviewConclusion> conclusions) {
        Map<String, FeatureSourceCandidate> candidatesById = candidatesById(candidates);
        Map<String, FeatureReviewConclusion> dispositions = exactlyOneDispositionPerCandidate(candidatesById, conclusions);
        List<FrozenFeatureTarget> pending = new ArrayList<>();
        Set<String> normalizedPaths = new LinkedHashSet<>();
        for (FeatureReviewConclusion conclusion : conclusions) {
            if (conclusion.type() == FeatureReviewConclusionType.CONFLICT) {
                throw new IllegalStateException("Cannot freeze an unresolved conflict: " + conclusion.conclusionId());
            }
            List<String> paths = featurePaths(conclusion);
            List<String> sourceIds = conclusion.candidateIds().stream().sorted().toList();
            FrozenFeatureSource source = new FrozenFeatureSource(conclusion.conclusionId(), conclusion.type(), sourceIds,
                    conclusion.explanation());
            boolean eligible = conclusion.type() != FeatureReviewConclusionType.INSUFFICIENT_EVIDENCE;
            for (String path : paths) {
                String normalizedPath = BusinessPathNormalizer.normalize(path);
                if (!normalizedPaths.add(normalizedPath)) {
                    throw new IllegalArgumentException("Frozen business paths must be distinct");
                }
                // The final sequence is assigned only after all stable identities have been sorted.
                pending.add(new FrozenFeatureTarget(stableId(sourceIds, normalizedPath), 1, path, eligible, source));
            }
        }
        if (pending.isEmpty() || dispositions.size() != candidatesById.size()) {
            throw new IllegalStateException("Every candidate requires a frozen or explicitly ineligible disposition");
        }
        pending.sort(Comparator.comparing(FrozenFeatureTarget::stableFeatureId));
        List<FrozenFeatureTarget> frozen = new ArrayList<>(pending.size());
        Set<String> ids = new LinkedHashSet<>();
        for (int index = 0; index < pending.size(); index++) {
            FrozenFeatureTarget target = pending.get(index);
            if (!ids.add(target.stableFeatureId())) {
                throw new IllegalStateException("Frozen feature identity collides for distinct conclusions");
            }
            frozen.add(new FrozenFeatureTarget(target.stableFeatureId(), index + 1, target.featureName(),
                    target.generationEligible(), target.source()));
        }
        return List.copyOf(frozen);
    }

    private static Map<String, FeatureSourceCandidate> candidatesById(List<FeatureSourceCandidate> candidates) {
        Map<String, FeatureSourceCandidate> result = new LinkedHashMap<>();
        for (FeatureSourceCandidate candidate : List.copyOf(candidates == null ? List.of() : candidates)) {
            if (result.put(candidate.occurrenceId(), candidate) != null) {
                throw new IllegalStateException("Candidate ledger contains a duplicate occurrence id");
            }
        }
        if (result.isEmpty()) throw new IllegalStateException("Candidate ledger is empty");
        return result;
    }

    private static Map<String, FeatureReviewConclusion> exactlyOneDispositionPerCandidate(
            Map<String, FeatureSourceCandidate> candidates, List<FeatureReviewConclusion> conclusions) {
        Map<String, FeatureReviewConclusion> result = new LinkedHashMap<>();
        Set<String> conclusionIds = new LinkedHashSet<>();
        for (FeatureReviewConclusion conclusion : List.copyOf(conclusions == null ? List.of() : conclusions)) {
            if (!conclusionIds.add(conclusion.conclusionId())) {
                throw new IllegalStateException("Conclusion ledger contains a duplicate conclusion id");
            }
            for (String candidateId : conclusion.candidateIds()) {
                if (!candidates.containsKey(candidateId) || result.put(candidateId, conclusion) != null) {
                    throw new IllegalStateException("Every candidate requires exactly one conclusion");
                }
            }
        }
        if (result.size() != candidates.size()) throw new IllegalStateException("Every candidate requires exactly one conclusion");
        return result;
    }

    private static List<String> featurePaths(FeatureReviewConclusion conclusion) {
        return BusinessPathNormalizer.parseAndValidate(conclusion.type(), conclusion.explanation());
    }

    private static String stableId(List<String> sortedSourceCandidateIds, String normalizedPath) {
        try {
            String material = String.join("\u001f", sortedSourceCandidateIds) + "\u001f" + normalizedPath;
            return "ff-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }
}
