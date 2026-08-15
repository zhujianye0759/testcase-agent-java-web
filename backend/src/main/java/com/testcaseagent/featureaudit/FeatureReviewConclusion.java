package com.testcaseagent.featureaudit;

import java.util.List;
import java.util.Objects;

/**
 * One final, durable disposition that may cover one or more source candidate occurrences.
 *
 * [Req-ID]: REQ-BFA-003, REQ-BFA-004
 */
public record FeatureReviewConclusion(
        String conclusionId,
        int sequence,
        FeatureReviewConclusionType type,
        String explanation,
        String evidenceText,
        List<String> candidateIds) {

    public FeatureReviewConclusion {
        conclusionId = required(conclusionId, "conclusionId");
        if (sequence <= 0) throw new IllegalArgumentException("Conclusion sequence must be positive");
        type = Objects.requireNonNull(type, "type must not be null");
        explanation = required(explanation, "explanation");
        evidenceText = required(evidenceText, "evidenceText");
        candidateIds = List.copyOf(candidateIds == null ? List.of() : candidateIds);
        if (candidateIds.isEmpty() || candidateIds.stream().anyMatch(id -> id == null || id.isBlank())
                || candidateIds.stream().distinct().count() != candidateIds.size()) {
            throw new IllegalArgumentException("Conclusion candidateIds must be non-empty and distinct");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
