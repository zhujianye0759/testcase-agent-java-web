package com.testcaseagent.featureaudit;

import java.util.List;

/** Accepted requirement candidates for one bounded pass, including second-pass convergence duplicates. [Req-ID]: REQ-BFA-002 */
public record RequirementCandidateScanResult(
        List<FeatureSourceCandidate> candidates,
        List<FeatureSourceCandidate> duplicateOccurrences,
        boolean converged) {
    public RequirementCandidateScanResult {
        candidates = List.copyOf(candidates);
        duplicateOccurrences = List.copyOf(duplicateOccurrences);
    }
}
