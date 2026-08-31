package com.testcaseagent.structuredgeneration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Verifies that processing completion never fabricates formal coverage. [Req-ID]: REQ-STG-007 */
class StructuredCompletionGateTest {

    @Test
    void completesOnlyAfterEveryBusinessAndArtifactGatePasses() {
        var outcome = new StructuredCompletionGate().evaluate(snapshot(3, 3, true, true));

        assertThat(outcome.processingStatus()).isEqualTo(StructuredProcessingStatus.COMPLETED);
        assertThat(outcome.coverageStatus()).isEqualTo(StructuredCoverageStatus.COMPLETE);
    }

    @Test
    void terminalCallsWithMissingFormalCoverageCompleteProcessingAndRemainInsufficient() {
        var outcome = new StructuredCompletionGate().evaluate(snapshot(3, 2, true, true));

        assertThat(outcome.processingStatus()).isEqualTo(StructuredProcessingStatus.COMPLETED);
        assertThat(outcome.coverageStatus()).isEqualTo(StructuredCoverageStatus.PARTIAL);
        assertThat(outcome.artifactPublishable()).isTrue();
    }

    @Test
    void terminalSuccessWithoutAnyFormalResultIsCompletedButUnableToGenerateCoverage() {
        var outcome = new StructuredCompletionGate().evaluate(snapshot(3, 0, true, true));

        assertThat(outcome.processingStatus()).isEqualTo(StructuredProcessingStatus.COMPLETED);
        assertThat(outcome.coverageStatus()).isEqualTo(StructuredCoverageStatus.UNABLE_TO_GENERATE);
    }

    @Test
    void cancellationAndFailureCannotPublishACompletedArtifact() {
        var gate = new StructuredCompletionGate();
        var cancelled = gate.evaluate(new StructuredCompletionGate.Snapshot(
                true, 2, 2, true, true, 2, 2, 1, true, 3, 0, true, true));
        var failed = gate.evaluate(new StructuredCompletionGate.Snapshot(
                false, 2, 1, false, false, 2, 0, 0, false, 0, 2, true, false));

        assertThat(cancelled.processingStatus()).isEqualTo(StructuredProcessingStatus.CANCELLED);
        assertThat(failed.processingStatus()).isEqualTo(StructuredProcessingStatus.FAILED);
        assertThat(cancelled.artifactPublishable()).isFalse();
        assertThat(failed.artifactPublishable()).isFalse();
    }

    /** [Req-ID]: REQ-AFCE-006 */
    @Test
    void candidateDeliveryWithTrustedFormalResultsAndAnIncompleteWindowIsPartialButProcessingCompleted() {
        var outcome = new StructuredCompletionGate().evaluate(new StructuredCompletionGate.Snapshot(
                true, 4, 4, true, true, 3, 3, 1, true,
                8, 1, true, true, 2, 1, 1, false));

        assertThat(outcome.processingStatus()).isEqualTo(StructuredProcessingStatus.COMPLETED);
        assertThat(outcome.coverageStatus()).isEqualTo(StructuredCoverageStatus.PARTIAL);
        assertThat(outcome.artifactPublishable()).isTrue();
    }

    /** [Req-ID]: REQ-AFCE-006 */
    @Test
    void completeCandidateDeliveryKeepsCompletedAndCompleteAxes() {
        var outcome = new StructuredCompletionGate().evaluate(new StructuredCompletionGate.Snapshot(
                true, 4, 4, true, true, 3, 3, 0, true,
                8, 0, true, true, 2, 0, 0, false));

        assertThat(outcome.processingStatus()).isEqualTo(StructuredProcessingStatus.COMPLETED);
        assertThat(outcome.coverageStatus()).isEqualTo(StructuredCoverageStatus.COMPLETE);
        assertThat(outcome.artifactPublishable()).isTrue();
    }

    /** [Req-ID]: REQ-AFCE-006 */
    @Test
    void candidateDeliveryWithoutAnyTrustedFormalResultFailsInsteadOfClaimingCompletion() {
        var outcome = new StructuredCompletionGate().evaluate(new StructuredCompletionGate.Snapshot(
                true, 4, 4, false, true, 0, 0, 2, true,
                5, 0, true, true, 0, 2, 0, false));

        assertThat(outcome.processingStatus()).isEqualTo(StructuredProcessingStatus.FAILED);
        assertThat(outcome.coverageStatus()).isEqualTo(StructuredCoverageStatus.UNABLE_TO_GENERATE);
        assertThat(outcome.artifactPublishable()).isFalse();
    }

    private static StructuredCompletionGate.Snapshot snapshot(
            int formalPointTotal, int formalPointCovered, boolean allTerminal, boolean artifactValidated) {
        return new StructuredCompletionGate.Snapshot(
                true, 4, 4, true, true, formalPointTotal, formalPointCovered, 2,
                artifactValidated, 8, 0, allTerminal, false);
    }
}
