package com.testcaseagent.featureaudit;

import java.util.List;

/**
 * Performs the two bounded requirement passes and exposes second-pass duplicates as convergence evidence.
 *
 * <p>The caller owns durable state and supplies accepted first-pass candidates. This scanner only validates the
 * exact agent response for one retained unit; it never accesses examples, Office content, or transport APIs.</p>
 *
 * [Req-ID]: REQ-BFA-002
 */
public final class RequirementCandidateScanner {
    private final CandidateMarkdownParser parser = new CandidateMarkdownParser();

    /** Builds a fixed request for one requirement unit and, on pass two, its accepted first-pass candidate texts. */
    public String promptFor(MaterialInventoryUnit unit, int passNumber, List<FeatureSourceCandidate> acceptedCandidates) {
        requireRequirementUnit(unit);
        requirePass(passNumber);
        List<FeatureSourceCandidate> accepted = checkedAccepted(unit, passNumber, acceptedCandidates);
        String instruction = passNumber == 1
                ? "从该需求单元提取动作、对象或条件构成的候选项。"
                : "仅检查第一遍未覆盖的功能性陈述；只返回新增项，或返回第一表零行。\n第一遍已接受对象/功能点：\n"
                        + accepted.stream().map(candidate -> "- " + candidate.featureText()).collect(java.util.stream.Collectors.joining("\n")) + "\n";
        return FeatureCandidateScanner.promptPrefix(unit, instruction)
                + "只返回下列精确两张 Markdown 表；第一表可为零行，第二表必须为零行。\n"
                + FeatureCandidateScanner.tableContract() + FeatureCandidateScanner.unitContent(unit);
    }

    /** Accepts a valid first or second pass; only a valid second pass reports convergence. */
    public RequirementCandidateScanResult accept(
            MaterialInventoryUnit unit, int passNumber, List<FeatureSourceCandidate> acceptedCandidates, String markdown) {
        requireRequirementUnit(unit);
        requirePass(passNumber);
        List<FeatureSourceCandidate> accepted = checkedAccepted(unit, passNumber, acceptedCandidates);
        List<FeatureSourceCandidate> parsed = parser.parse(markdown).stream()
                .map(row -> FeatureCandidateScanner.candidate(unit, FeatureCandidateKind.REQUIREMENT, passNumber, row))
                .toList();
        if (passNumber == 1) return new RequirementCandidateScanResult(parsed, List.of(), false);

        List<FeatureSourceCandidate> duplicates = parsed.stream()
                .filter(candidate -> accepted.stream().anyMatch(previous -> sameContent(previous, candidate))).toList();
        List<FeatureSourceCandidate> newCandidates = parsed.stream()
                .filter(candidate -> accepted.stream().noneMatch(previous -> sameContent(previous, candidate))).toList();
        return new RequirementCandidateScanResult(newCandidates, duplicates, true);
    }

    private static boolean sameContent(FeatureSourceCandidate left, FeatureSourceCandidate right) {
        return left.featureText().equals(right.featureText()) && left.category().equals(right.category())
                && left.evidenceText().equals(right.evidenceText());
    }

    private static List<FeatureSourceCandidate> checkedAccepted(
            MaterialInventoryUnit unit, int passNumber, List<FeatureSourceCandidate> acceptedCandidates) {
        List<FeatureSourceCandidate> accepted = List.copyOf(acceptedCandidates == null ? List.of() : acceptedCandidates);
        if (passNumber == 1 && !accepted.isEmpty()) throw new IllegalArgumentException("First requirement pass has no accepted candidates");
        if (passNumber == 2 && accepted.stream().anyMatch(candidate -> candidate.kind() != FeatureCandidateKind.REQUIREMENT
                || candidate.passNumber() != 1 || !unit.documentId().equals(candidate.documentId())
                || !unit.unitId().equals(candidate.unitId()))) {
            throw new IllegalArgumentException("Second requirement pass requires accepted first-pass candidates from the same unit");
        }
        return accepted;
    }

    private static void requireRequirementUnit(MaterialInventoryUnit unit) {
        if (unit == null || !("WORK_ORDER_PLAN".equals(unit.documentRole()) || "REQUIREMENT".equals(unit.documentRole()))) {
            throw new IllegalArgumentException("Requirement candidate scanning requires a WORK_ORDER_PLAN or REQUIREMENT material unit");
        }
    }

    private static void requirePass(int passNumber) {
        if (passNumber != 1 && passNumber != 2) throw new IllegalArgumentException("Requirement candidate scanning accepts exactly two passes");
    }
}
