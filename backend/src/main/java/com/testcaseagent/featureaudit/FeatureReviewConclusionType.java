package com.testcaseagent.featureaudit;

/** Terminal categories accepted from the bounded feature-scope reconciliation contract. [Req-ID]: REQ-BFA-003 */
public enum FeatureReviewConclusionType {
    MATCHED,
    FUNCTION_LIST_MISSING,
    REQUIREMENT_MISSING,
    CONFLICT,
    SPLIT,
    MERGE,
    DUPLICATE,
    INSUFFICIENT_EVIDENCE
}
