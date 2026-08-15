package com.testcaseagent.testcase;

import com.testcaseagent.featureaudit.FrozenFeatureTarget;
import com.testcaseagent.markdown.MarkdownAuditRow;
import com.testcaseagent.markdown.MarkdownGenerationResult;
import com.testcaseagent.markdown.MarkdownTestCaseRow;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministically accepts only the two test-case rows owned by one frozen feature.
 *
 * <p>The parser has already proved the exact Markdown structure. This validator deliberately proves only facts
 * available in its two inputs: frozen feature ownership, the positive/negative pair, deterministic module identity,
 * numbered step/result pairing, and the general-experience label. Formal evidence is linked indirectly through the
 * durable frozen-source candidate IDs: every non-general requirement cell and every audit row must reference only
 * candidate IDs owned by this frozen target. It deliberately does not judge the semantic truth of model prose;
 * export later converts or removes the technical candidate token for business-facing output.</p>
 *
 * [Req-ID]: REQ-CAG-001, REQ-CAG-002, REQ-CAG-003
 */
public final class FrozenFeatureBatchAcceptanceValidator {
    static final String GENERAL_EXPERIENCE = "依据通用经验，待确认";
    private static final Pattern NUMBERED_ITEM = Pattern.compile("^(\\d+)\\.\\s+\\S.*$");

    /**
     * Validates one already-parsed batch against its immutable frozen feature target.
     *
     * @throws IllegalArgumentException when a row is missing, added, reassigned, or internally inconsistent
     */
    public void validate(FrozenFeatureTarget frozenFeature, MarkdownGenerationResult result) {
        Objects.requireNonNull(frozenFeature, "frozenFeature must not be null");
        Objects.requireNonNull(result, "result must not be null");
        requireGenerationEligible(frozenFeature);
        String featurePath = frozenFeature.featureName().strip();
        String featureLeaf = featureLeaf(featurePath);
        validateAuditRows(frozenFeature, featurePath, featureLeaf, result.auditRows());
        validateTestCaseRows(featurePath, featureLeaf, frozenFeature.source().candidateIds(), result.testCaseRows());
    }

    private static void requireGenerationEligible(FrozenFeatureTarget frozenFeature) {
        if (!frozenFeature.generationEligible()) {
            throw new IllegalArgumentException("Cannot accept a batch for a non-generatable frozen feature");
        }
    }

    private static void validateTestCaseRows(
            String featurePath, String featureLeaf, List<String> frozenCandidateIds, List<MarkdownTestCaseRow> rows) {
        if (rows == null || rows.size() != 2) {
            throw new IllegalArgumentException("A frozen feature batch must contain exactly two test-case rows");
        }
        Set<String> expectedNames = Set.of(featureLeaf + "_正向", featureLeaf + "_反向");
        Set<String> actualNames = new LinkedHashSet<>();
        for (MarkdownTestCaseRow row : rows) {
            if (row == null) throw new IllegalArgumentException("A test-case row must not be null");
            if (!featurePath.equals(required(row.featureModule(), "feature module"))) {
                throw new IllegalArgumentException("Test-case module must exactly equal the frozen feature path");
            }
            String caseName = required(row.caseName(), "case name");
            if (!expectedNames.contains(caseName) || !actualNames.add(caseName)) {
                throw new IllegalArgumentException("Test-case names must be the unique frozen positive and negative pair");
            }
            requireNumberedPairs(row.executionSteps(), row.expectedResult());
            requireRequirementContent(row.requirementContent(), Set.copyOf(frozenCandidateIds));
        }
        if (!actualNames.equals(expectedNames)) {
            throw new IllegalArgumentException("Test-case names must cover the frozen positive and negative pair");
        }
    }

    private static void requireNumberedPairs(String executionSteps, String expectedResult) {
        List<Integer> stepNumbers = numberedLines(required(executionSteps, "execution steps"), "execution steps");
        List<Integer> expectedNumbers = numberedLines(required(expectedResult, "expected result"), "expected result");
        if (!stepNumbers.equals(expectedNumbers)) {
            throw new IllegalArgumentException("Execution steps and expected results must have the same numbered items");
        }
    }

    private static List<Integer> numberedLines(String value, String column) {
        List<String> lines = value.lines().map(String::strip).toList();
        if (lines.isEmpty() || lines.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("The " + column + " must contain nonblank numbered items");
        }
        java.util.ArrayList<Integer> numbers = new java.util.ArrayList<>(lines.size());
        for (int index = 0; index < lines.size(); index++) {
            Matcher matcher = NUMBERED_ITEM.matcher(lines.get(index));
            if (!matcher.matches()) {
                throw new IllegalArgumentException("The " + column + " must use consecutive '1. ' numbering");
            }
            int number = Integer.parseInt(matcher.group(1));
            if (number != index + 1) {
                throw new IllegalArgumentException("The " + column + " numbering must start at 1 and be consecutive");
            }
            numbers.add(number);
        }
        return List.copyOf(numbers);
    }

    private static void requireRequirementContent(String content, Set<String> allowedCandidateIds) {
        String value = required(content, "requirement content");
        if (GENERAL_EXPERIENCE.equals(value)) {
            return;
        }
        if (value.contains("依据通用经验")) {
            throw new IllegalArgumentException("General-experience content must exactly equal '" + GENERAL_EXPERIENCE + "'");
        }
        if (!allowedCandidateIds.containsAll(requireCandidateIds(value, "Requirement content"))) {
            throw new IllegalArgumentException("Requirement content must reference only candidates of the frozen target");
        }
    }

    private static void validateAuditRows(
            FrozenFeatureTarget frozenFeature, String featurePath, String featureLeaf, List<MarkdownAuditRow> rows) {
        if (rows == null) throw new IllegalArgumentException("Audit rows must not be null");
        Set<String> allowedCandidateIds = Set.copyOf(frozenFeature.source().candidateIds());
        for (MarkdownAuditRow row : rows) {
            if (row == null) throw new IllegalArgumentException("An audit row must not be null");
            String subject = required(row.subjectOrFeature(), "audit subject");
            if (!featurePath.equals(subject) && !featureLeaf.equals(subject)) {
                throw new IllegalArgumentException("An audit row must not introduce a feature outside the frozen target");
            }
            Set<String> referencedIds = requireCandidateIds(required(row.evidenceComparison(), "audit evidence"), "Audit evidence");
            if (!allowedCandidateIds.containsAll(referencedIds)) {
                throw new IllegalArgumentException("An audit row must reference only candidates of the frozen target");
            }
        }
    }

    private static Set<String> requireCandidateIds(String evidence, String field) {
        String value = null;
        for (String rawToken : evidence.split("(?:;|\\R)", -1)) {
            String token = rawToken.strip();
            if (!token.startsWith("candidateIds=")) continue;
            if (value != null) throw new IllegalArgumentException(field + " must contain exactly one candidateIds token");
            value = token.substring("candidateIds=".length());
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must retain candidateIds for the frozen target");
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String id : value.split(",", -1)) {
            String normalized = id.strip();
            if (normalized.isBlank() || !ids.add(normalized)) {
                throw new IllegalArgumentException(field + " candidateIds must be nonblank and distinct");
            }
        }
        return Set.copyOf(ids);
    }

    private static String featureLeaf(String featurePath) {
        int separator = featurePath.lastIndexOf('/');
        String leaf = separator < 0 ? featurePath : featurePath.substring(separator + 1).strip();
        if (leaf.isBlank()) throw new IllegalArgumentException("Frozen feature path must have a nonblank final feature name");
        return leaf;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("The " + field + " must not be blank");
        return value.strip();
    }
}
