package com.testcaseagent.structuredgeneration;

/** Computes truthful terminal processing and formal coverage on independent axes. [Req-ID]: REQ-STG-007 */
public final class StructuredCompletionGate {

    /** Evaluates already-persisted aggregate state without mutating or repairing it. */
    public Outcome evaluate(Snapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("snapshot must not be null");
        snapshot.validate();

        StructuredCoverageStatus coverage = !snapshot.allWorkTerminal()
                ? StructuredCoverageStatus.PENDING
                : snapshot.formalTestPointTotal() > 0
                        && snapshot.coveredFormalTestPointCount() == snapshot.formalTestPointTotal()
                                ? StructuredCoverageStatus.COMPLETE
                                : snapshot.coveredFormalTestPointCount() > 0
                                        ? StructuredCoverageStatus.PARTIAL
                                        : StructuredCoverageStatus.UNABLE_TO_GENERATE;
        if (snapshot.cancelled()) {
            return new Outcome(StructuredProcessingStatus.CANCELLED, coverage, false);
        }
        boolean completed = snapshot.allWorkTerminal()
                && snapshot.parsedUnitsComplete()
                && snapshot.completedReviewWork() == snapshot.totalReviewWork()
                && snapshot.reconciliationComplete()
                && snapshot.scopeFrozen()
                && snapshot.artifactValidated()
                && snapshot.failedWorkCount() == 0;
        if (completed) return new Outcome(StructuredProcessingStatus.COMPLETED, coverage, true);
        if (!snapshot.allWorkTerminal()) return new Outcome(StructuredProcessingStatus.RUNNING, coverage, false);
        return new Outcome(StructuredProcessingStatus.FAILED, coverage, false);
    }

    /** Durable aggregate inputs; counts are independent of any fixed testcase multiplier. */
    public record Snapshot(
            boolean parsedUnitsComplete,
            int totalReviewWork,
            int completedReviewWork,
            boolean reconciliationComplete,
            boolean scopeFrozen,
            int formalTestPointTotal,
            int coveredFormalTestPointCount,
            int pendingCandidateCaseCount,
            boolean artifactValidated,
            int acceptedWorkCount,
            int failedWorkCount,
            boolean allWorkTerminal,
            boolean cancelled) {
        private void validate() {
            if (totalReviewWork < 0 || completedReviewWork < 0 || completedReviewWork > totalReviewWork
                    || formalTestPointTotal < 0 || coveredFormalTestPointCount < 0
                    || coveredFormalTestPointCount > formalTestPointTotal || pendingCandidateCaseCount < 0
                    || acceptedWorkCount < 0 || failedWorkCount < 0) {
                throw new IllegalArgumentException("Structured completion counts are invalid");
            }
        }
    }

    /** Browser/export-safe outcome with a separate artifact publication decision. */
    public record Outcome(
            StructuredProcessingStatus processingStatus,
            StructuredCoverageStatus coverageStatus,
            boolean artifactPublishable) { }
}
