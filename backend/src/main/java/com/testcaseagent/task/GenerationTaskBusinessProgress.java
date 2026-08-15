package com.testcaseagent.task;

/**
 * Browser-safe, aggregate-only completeness progress for one task.
 *
 * <p>Frozen totals and the expected test-case count deliberately remain {@code null} until the durable audit and
 * freeze gates have completed. This prevents a running audit from presenting a provisional candidate count as its
 * final generation scope.</p>
 *
 * [Req-ID]: REQ-CWR-001, REQ-CWR-002
 */
public record GenerationTaskBusinessProgress(
        String currentBusinessStage,
        int materialDocumentTotal,
        int completeMaterialDocumentCount,
        int materialUnitTotal,
        int processedMaterialUnitCount,
        int totalAuditWork,
        int completedAuditWork,
        int failedAuditWork,
        int featureCandidateTotal,
        int functionListMissingCount,
        int requirementMissingCount,
        int conflictCount,
        int splitCount,
        int mergeCount,
        int insufficientEvidenceCount,
        boolean frozenComplete,
        Integer frozenFeatureTotal,
        Integer generationEligibleFrozenFeatureCount,
        Integer generationIneligibleFrozenFeatureCount,
        Integer expectedTestCaseTotal,
        int acceptedTestCaseCount,
        String coverageStatus,
        String businessReason) {
}
