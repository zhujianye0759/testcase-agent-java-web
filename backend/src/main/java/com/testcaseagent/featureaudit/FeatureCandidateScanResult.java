package com.testcaseagent.featureaudit;

import java.util.List;

/** Accepted one-pass function-list candidates. [Req-ID]: REQ-BFA-001, REQ-BFA-004 */
public record FeatureCandidateScanResult(List<FeatureSourceCandidate> candidates, boolean converged) {
    public FeatureCandidateScanResult {
        candidates = List.copyOf(candidates);
        if (!converged) throw new IllegalArgumentException("A valid function-list scan is immediately converged");
    }
}
