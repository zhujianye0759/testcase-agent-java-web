package com.testcaseagent.featureaudit;

import java.util.Objects;

/**
 * Immutable task-owned feature selected only after the entire source candidate ledger has a terminal disposition.
 *
 * [Req-ID]: REQ-BFA-005
 */
public record FrozenFeatureTarget(
        String stableFeatureId,
        int stableSequence,
        String featureName,
        boolean generationEligible,
        FrozenFeatureSource source) {

    public FrozenFeatureTarget {
        if (stableFeatureId == null || stableFeatureId.isBlank()) throw new IllegalArgumentException("stableFeatureId must not be blank");
        if (stableSequence <= 0) throw new IllegalArgumentException("stableSequence must be positive");
        if (featureName == null || featureName.isBlank()) throw new IllegalArgumentException("featureName must not be blank");
        source = Objects.requireNonNull(source, "source must not be null");
    }
}
