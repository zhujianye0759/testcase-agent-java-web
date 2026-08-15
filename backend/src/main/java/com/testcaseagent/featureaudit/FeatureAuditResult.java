package com.testcaseagent.featureaudit;

/** Read-only progress returned by one durable feature-audit run. [Req-ID]: REQ-BFA-001, REQ-BFA-003 */
public record FeatureAuditResult(
        boolean materialInventoryComplete,
        int totalAuditWork,
        int completedAuditWork,
        int permanentlyFailedAuditWork,
        int candidateCount,
        int conclusionCount,
        boolean complete) {
}
