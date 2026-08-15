package com.testcaseagent.featureaudit;

import java.util.Objects;

/**
 * One model-labelled occurrence retained with its material-unit coordinate rather than deduplicated model text.
 *
 * [Req-ID]: REQ-BFA-001, REQ-BFA-002, REQ-BFA-004
 */
public record FeatureSourceCandidate(
        String occurrenceId,
        FeatureCandidateKind kind,
        String documentId,
        String unitId,
        int ordinal,
        int modelSequence,
        String featureText,
        String category,
        String evidenceText,
        int passNumber,
        int sourceRowPosition) {

    public FeatureSourceCandidate {
        occurrenceId = required(occurrenceId, "occurrenceId");
        kind = Objects.requireNonNull(kind, "kind must not be null");
        documentId = required(documentId, "documentId");
        unitId = required(unitId, "unitId");
        featureText = required(featureText, "featureText");
        category = required(category, "category");
        evidenceText = required(evidenceText, "evidenceText");
        if (ordinal <= 0 || modelSequence <= 0 || passNumber <= 0 || sourceRowPosition <= 0) {
            throw new IllegalArgumentException("Candidate source coordinates must be positive");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
