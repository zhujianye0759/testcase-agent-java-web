package com.testcaseagent.structuredgeneration;

import com.testcaseagent.task.GenerationTaskStatus;

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
                                ? StructuredCoverageStatus.SATISFIED
                                : StructuredCoverageStatus.INSUFFICIENT;
        if (snapshot.cancelled()) {
            return new Outcome(GenerationTaskStatus.CANCELLED, coverage, false);
        }
        boolean completed = snapshot.allWorkTerminal()
                && snapshot.parsedUnitsComplete()
                && snapshot.completedReviewWork() == snapshot.totalReviewWork()
                && snapshot.reconciliationComplete()
                && snapshot.scopeFrozen()
                && snapshot.artifactValidated()
                && snapshot.failedWorkCount() == 0;
        if (completed) return new Outcome(GenerationTaskStatus.COMPLETED, coverage, true);
        if (!snapshot.allWorkTerminal()) return new Outcome(GenerationTaskStatus.GENERATING, coverage, false);
        if (snapshot.acceptedWorkCount() > 0) {
            boolean partialArtifactPublishable = snapshot.parsedUnitsComplete()
                    && snapshot.completedReviewWork() == snapshot.totalReviewWork()
                    && snapshot.reconciliationComplete()
                    && snapshot.scopeFrozen()
                    && snapshot.artifactValidated();
            return new Outcome(GenerationTaskStatus.PARTIAL, coverage, partialArtifactPublishable);
        }
        return new Outcome(GenerationTaskStatus.FAILED, coverage, false);
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
            GenerationTaskStatus processingStatus,
            StructuredCoverageStatus coverageStatus,
            boolean artifactPublishable) { }
}
