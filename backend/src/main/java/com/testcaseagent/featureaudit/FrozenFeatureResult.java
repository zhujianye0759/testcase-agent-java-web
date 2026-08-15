package com.testcaseagent.featureaudit;

import java.util.List;

/** Result of a task-scoped final feature freeze. [Req-ID]: REQ-BFA-005 */
public record FrozenFeatureResult(boolean frozen, List<FrozenFeatureTarget> targets) {
    public FrozenFeatureResult {
        targets = List.copyOf(targets == null ? List.of() : targets);
    }
}
