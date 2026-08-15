package com.testcaseagent.featureaudit;

import java.util.List;
import java.util.Objects;

/**
 * Traceability retained with one frozen business feature; it never creates a second evidence system.
 *
 * [Req-ID]: REQ-BFA-005
 */
public record FrozenFeatureSource(
        String conclusionId,
        FeatureReviewConclusionType conclusionType,
        List<String> candidateIds,
        String decisionReason) {

    public FrozenFeatureSource {
        if (conclusionId == null || conclusionId.isBlank()) throw new IllegalArgumentException("conclusionId must not be blank");
        conclusionType = Objects.requireNonNull(conclusionType, "conclusionType must not be null");
        candidateIds = List.copyOf(candidateIds == null ? List.of() : candidateIds);
        if (candidateIds.isEmpty() || candidateIds.stream().anyMatch(id -> id == null || id.isBlank())
                || candidateIds.stream().distinct().count() != candidateIds.size()) {
            throw new IllegalArgumentException("candidateIds must be non-empty and distinct");
        }
        if (decisionReason == null || decisionReason.isBlank()) throw new IllegalArgumentException("decisionReason must not be blank");
    }
}
