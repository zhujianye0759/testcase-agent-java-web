package com.testcaseagent.validation;

import com.testcaseagent.identity.LengthPrefixedSha256;
import com.testcaseagent.knowledgeagent.FunctionCandidateExtractionInput;
import com.testcaseagent.knowledgeagent.FunctionCandidateExtractionResult;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Independently validates one public candidate window and recomputes both protocol identities.
 *
 * <p>The validator has no HTTP, SQL, retry, or presentation responsibility. It closes every
 * source outcome and candidate against the frozen target units, then returns immutable audit rows.
 * Java may retain or downgrade a KEE recommendation, but cannot upgrade it.</p>
 *
 * [Req-ID]: REQ-AFCE-002, REQ-AFCE-003, REQ-AFCE-004
 */
public final class FunctionCandidateExtractionValidator {
    private static final String CANDIDATE_DOMAIN = "function-candidate-v1";
    private static final Set<String> PENDING_REASON_CODES = Set.of(
            "ambiguous_scope", "insufficient_detail", "conflicting_evidence");
    private static final Set<String> UNRESOLVED_REASON_CODES = Set.of(
            "ambiguous_content", "conflicting_content", "model_omitted_unit", "model_item_unusable");

    /** Validates the complete window or throws before any row can be accepted. */
    public ValidatedWindow validate(String taskId, FunctionCandidateExtractionInput input,
            FunctionCandidateExtractionResult result) {
        String checkedTaskId = required(taskId, "taskId");
        FunctionCandidateExtractionInput checkedInput = Objects.requireNonNull(input, "input must not be null");
        FunctionCandidateExtractionResult checkedResult = Objects.requireNonNull(result, "result must not be null");

        String expectedWindowKey = windowKey(checkedTaskId, checkedInput);
        if (!expectedWindowKey.equals(checkedInput.windowKey())
                || !expectedWindowKey.equals(checkedResult.windowKey())) {
            throw new IllegalArgumentException("candidate window identity does not match the frozen input");
        }

        Map<String, FunctionCandidateExtractionInput.Unit> targets = new LinkedHashMap<>();
        checkedInput.units().forEach(unit -> targets.put(unit.unitKey(), unit));
        Set<String> contextKeys = checkedInput.contextUnits().stream()
                .map(FunctionCandidateExtractionInput.Unit::unitKey).collect(java.util.stream.Collectors.toSet());

        Map<String, ValidatedCandidate> candidates = validateCandidates(expectedWindowKey, targets,
                contextKeys, checkedResult.candidates());
        List<ValidatedSourceOutcome> outcomes = validateOutcomes(checkedInput.units(), checkedResult.sourceOutcomes(),
                candidates);
        validateSummary(checkedResult.normalizationSummary(), checkedResult.candidates().size(), outcomes);
        return new ValidatedWindow(expectedWindowKey, outcomes, List.copyOf(candidates.values()),
                checkedResult.normalizationSummary());
    }

    private static Map<String, ValidatedCandidate> validateCandidates(String windowKey,
            Map<String, FunctionCandidateExtractionInput.Unit> targets, Set<String> contextKeys,
            List<FunctionCandidateExtractionResult.Candidate> values) {
        Map<String, ValidatedCandidate> candidates = new LinkedHashMap<>();
        Map<String, Integer> targetOrder = new LinkedHashMap<>();
        int ordinal = 0;
        for (String key : targets.keySet()) {
            targetOrder.put(key, ordinal++);
        }
        for (FunctionCandidateExtractionResult.Candidate candidate : values) {
            String path = canonicalText(candidate.path());
            String description = canonicalText(candidate.description());
            String targetQuote = canonicalText(candidate.targetQuote());
            if (!path.equals(candidate.path()) || !description.equals(candidate.description())
                    || !targetQuote.equals(candidate.targetQuote())) {
                throw new IllegalArgumentException("candidate text is not in canonical protocol form");
            }
            ReaderFacingTextPolicy.requireSafe(path, "candidate path");
            ReaderFacingTextPolicy.requireSafe(description, "candidate description");

            List<String> evidenceKeys = candidate.evidenceKeys();
            int previousIndex = -1;
            for (String evidenceKey : evidenceKeys) {
                if (contextKeys.contains(evidenceKey) || !targets.containsKey(evidenceKey)) {
                    throw new IllegalArgumentException("candidate evidence is outside the target window");
                }
                int currentIndex = targetOrder.get(evidenceKey);
                if (currentIndex <= previousIndex) {
                    throw new IllegalArgumentException("candidate evidence order is not canonical");
                }
                previousIndex = currentIndex;
            }
            boolean quoteBound = evidenceKeys.stream().map(targets::get)
                    .map(FunctionCandidateExtractionInput.Unit::content)
                    .map(FunctionCandidateExtractionValidator::canonicalText)
                    .anyMatch(content -> content.contains(targetQuote));
            if (!quoteBound) {
                throw new IllegalArgumentException("candidate quote is not bound to one target unit");
            }
            requireCandidateCombination(candidate);

            String expectedCandidateRef = candidateKey(windowKey, path, description, targetQuote, evidenceKeys);
            if (!expectedCandidateRef.equals(candidate.candidateRef())) {
                throw new IllegalArgumentException("candidate identity does not match canonical content");
            }
            FinalDecision initialDecision = switch (candidate.recommendedStatus()) {
                case ACCEPTED -> FinalDecision.ACCEPTED;
                case PENDING_CONFIRMATION -> FinalDecision.PENDING_CONFIRMATION;
            };
            ValidatedCandidate validated = new ValidatedCandidate(candidate.candidateRef(), path, description,
                    targetQuote, evidenceKeys, candidate.recommendedStatus(), initialDecision,
                    candidate.reasonCode(), candidate.missingInformation());
            if (candidates.putIfAbsent(validated.candidateRef(), validated) != null) {
                throw new IllegalArgumentException("candidateRef must be unique");
            }
        }
        return candidates;
    }

    private static List<ValidatedSourceOutcome> validateOutcomes(
            List<FunctionCandidateExtractionInput.Unit> targets,
            List<FunctionCandidateExtractionResult.SourceOutcome> values,
            Map<String, ValidatedCandidate> candidates) {
        if (values.size() != targets.size()) {
            throw new IllegalArgumentException("every target unit requires exactly one source outcome");
        }
        List<ValidatedSourceOutcome> validated = new ArrayList<>(values.size());
        Set<String> reachedCandidates = new HashSet<>();
        for (int index = 0; index < targets.size(); index++) {
            String unitKey = targets.get(index).unitKey();
            FunctionCandidateExtractionResult.SourceOutcome outcome = values.get(index);
            if (!unitKey.equals(outcome.unitKey())) {
                throw new IllegalArgumentException("source outcomes must follow target input order");
            }
            List<String> expectedRefs = candidates.values().stream()
                    .filter(candidate -> candidate.evidenceKeys().contains(unitKey))
                    .map(ValidatedCandidate::candidateRef).toList();
            if (!expectedRefs.equals(outcome.candidateRefs())) {
                throw new IllegalArgumentException("source candidate references do not match evidence ownership");
            }
            requireOutcomeCombination(outcome, expectedRefs.isEmpty());
            reachedCandidates.addAll(outcome.candidateRefs());
            validated.add(new ValidatedSourceOutcome(unitKey, outcome.disposition(), outcome.candidateRefs(),
                    outcome.reasonCode(), finalSourceDecision(expectedRefs, candidates)));
        }
        if (!reachedCandidates.equals(candidates.keySet())) {
            throw new IllegalArgumentException("every candidate must be reachable from a target outcome");
        }
        return List.copyOf(validated);
    }

    private static void requireCandidateCombination(FunctionCandidateExtractionResult.Candidate candidate) {
        switch (candidate.recommendedStatus()) {
            case ACCEPTED -> {
                if (!"grounded_function".equals(candidate.reasonCode()) || !candidate.missingInformation().isEmpty()) {
                    throw new IllegalArgumentException("accepted candidate has an invalid reason or missing information");
                }
            }
            case PENDING_CONFIRMATION -> {
                if (!PENDING_REASON_CODES.contains(candidate.reasonCode())
                        || candidate.missingInformation().isEmpty()
                        || candidate.missingInformation().stream().anyMatch(String::isBlank)) {
                    throw new IllegalArgumentException("pending candidate must explain missing information");
                }
                ReaderFacingTextPolicy.requireSafeItems(candidate.missingInformation(), "candidate missingInformation");
            }
        }
    }

    private static void requireOutcomeCombination(FunctionCandidateExtractionResult.SourceOutcome outcome,
            boolean noCandidate) {
        switch (outcome.disposition()) {
            case LINKED -> {
                if (noCandidate || !"candidate_linked".equals(outcome.reasonCode())) {
                    throw new IllegalArgumentException("linked source outcome has an invalid candidate closure");
                }
            }
            case NO_FUNCTION -> {
                if (!noCandidate || !"non_functional_content".equals(outcome.reasonCode())) {
                    throw new IllegalArgumentException("no-function source outcome has an invalid candidate closure");
                }
            }
            case UNRESOLVED -> {
                if (!noCandidate || !UNRESOLVED_REASON_CODES.contains(outcome.reasonCode())) {
                    throw new IllegalArgumentException("unresolved source outcome has an invalid candidate closure");
                }
            }
        }
    }

    private static FinalDecision finalSourceDecision(List<String> candidateRefs,
            Map<String, ValidatedCandidate> candidates) {
        if (candidateRefs.stream().map(candidates::get)
                .anyMatch(candidate -> candidate.finalDecision() == FinalDecision.ACCEPTED)) {
            return FinalDecision.ACCEPTED;
        }
        if (candidateRefs.stream().map(candidates::get)
                .anyMatch(candidate -> candidate.finalDecision() == FinalDecision.PENDING_CONFIRMATION)) {
            return FinalDecision.PENDING_CONFIRMATION;
        }
        return FinalDecision.REJECTED;
    }

    private static void validateSummary(FunctionCandidateExtractionResult.NormalizationSummary summary,
            int candidateCount, List<ValidatedSourceOutcome> outcomes) {
        long minimumModelCandidates = (long) candidateCount + summary.discardedCandidateCount();
        if (minimumModelCandidates > summary.modelCandidateCount()
                || summary.discardedCandidateCount() > summary.modelCandidateCount()) {
            throw new IllegalArgumentException("normalization candidate count is inconsistent");
        }
        // KEE counts downgrades before deduplicating equal public candidates. The remaining raw
        // model candidates, not the public candidate count, are therefore the authoritative bound.
        if (summary.downgradedCandidateCount()
                > summary.modelCandidateCount() - summary.discardedCandidateCount()) {
            throw new IllegalArgumentException("normalization downgraded count is inconsistent");
        }
        long unresolved = outcomes.stream()
                .filter(outcome -> outcome.disposition() == FunctionCandidateExtractionResult.Disposition.UNRESOLVED)
                .count();
        long autoEligible = outcomes.stream()
                .filter(outcome -> outcome.disposition() == FunctionCandidateExtractionResult.Disposition.UNRESOLVED)
                .filter(outcome -> "model_omitted_unit".equals(outcome.reasonCode()))
                .count();
        // Explicit ambiguity/conflict/unusable outcomes are unresolved, but only omitted units can
        // have been supplied automatically by KEE's normalizer.
        if (summary.autoUnresolvedUnitCount() > unresolved
                || summary.autoUnresolvedUnitCount() > autoEligible) {
            throw new IllegalArgumentException("normalization unresolved count is inconsistent");
        }
    }

    private static String windowKey(String taskId, FunctionCandidateExtractionInput input) {
        return FunctionCandidateExtractionInput.expectedWindowKey(
                taskId, input.materialKey(), input.units(), input.contextUnits());
    }

    private static String candidateKey(String windowKey, String path, String description, String targetQuote,
            List<String> evidenceKeys) {
        List<String> fields = new ArrayList<>();
        fields.add(CANDIDATE_DOMAIN);
        fields.add(windowKey);
        fields.add(path);
        fields.add(description);
        fields.add(targetQuote);
        fields.add(Integer.toString(evidenceKeys.size()));
        fields.addAll(evidenceKeys);
        return hash(fields);
    }

    private static String hash(List<String> fields) {
        return HexFormat.of().formatHex(LengthPrefixedSha256.digest(fields.toArray(String[]::new)));
    }

    private static String canonicalText(String value) {
        String normalized = Normalizer.normalize(required(value, "candidate text"), Normalizer.Form.NFKC);
        StringBuilder result = new StringBuilder(normalized.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                pendingSpace = result.length() > 0;
            } else {
                if (pendingSpace) {
                    result.append(' ');
                    pendingSpace = false;
                }
                result.appendCodePoint(codePoint);
            }
        }
        return result.toString();
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /** Entire validated window, ready for a later atomic acceptance transaction. */
    public record ValidatedWindow(String windowKey, List<ValidatedSourceOutcome> sourceOutcomes,
            List<ValidatedCandidate> candidates,
            FunctionCandidateExtractionResult.NormalizationSummary normalizationSummary) {
        public ValidatedWindow {
            sourceOutcomes = List.copyOf(sourceOutcomes);
            candidates = List.copyOf(candidates);
        }
    }

    /** Target outcome with KEE's disposition and Java's current final decision kept separate. */
    public record ValidatedSourceOutcome(String unitKey,
            FunctionCandidateExtractionResult.Disposition disposition, List<String> candidateRefs,
            String reasonCode, FinalDecision finalDecision) {
        public ValidatedSourceOutcome {
            candidateRefs = List.copyOf(candidateRefs);
        }
    }

    /** Candidate audit row whose decision can only move to an equal or lower trust level. */
    public record ValidatedCandidate(String candidateRef, String path, String description, String targetQuote,
            List<String> evidenceKeys, FunctionCandidateExtractionResult.RecommendedStatus recommendedStatus,
            FinalDecision finalDecision, String reasonCode, List<String> missingInformation) {
        public ValidatedCandidate {
            evidenceKeys = List.copyOf(evidenceKeys);
            missingInformation = List.copyOf(missingInformation);
        }

        /** Returns a retained or downgraded decision; an upgrade is rejected fail-closed. */
        public ValidatedCandidate decide(FinalDecision decision) {
            FinalDecision checked = Objects.requireNonNull(decision, "decision must not be null");
            if (checked.trustLevel > finalDecision.trustLevel) {
                throw new IllegalArgumentException("Java candidate decision must not upgrade KEE evidence");
            }
            return new ValidatedCandidate(candidateRef, path, description, targetQuote, evidenceKeys,
                    recommendedStatus, checked, reasonCode, missingInformation);
        }
    }

    /** Java-owned terminal candidate decisions ordered from least to most trusted. */
    public enum FinalDecision {
        REJECTED(0), PENDING_CONFIRMATION(1), ACCEPTED(2);

        private final int trustLevel;

        FinalDecision(int trustLevel) {
            this.trustLevel = trustLevel;
        }
    }
}
