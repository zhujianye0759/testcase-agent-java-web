package com.testcaseagent.structuredgeneration;

import static org.assertj.core.api.Assertions.assertThat;

import com.testcaseagent.task.GenerationTaskStatus;
import org.junit.jupiter.api.Test;

/** Verifies that processing completion never fabricates formal coverage. [Req-ID]: REQ-STG-007 */
class StructuredCompletionGateTest {

    @Test
    void completesOnlyAfterEveryBusinessAndArtifactGatePasses() {
        var outcome = new StructuredCompletionGate().evaluate(snapshot(3, 3, true, true));

        assertThat(outcome.processingStatus()).isEqualTo(GenerationTaskStatus.COMPLETED);
        assertThat(outcome.coverageStatus()).isEqualTo(StructuredCoverageStatus.SATISFIED);
    }

    @Test
    void terminalCallsWithMissingFormalCoverageCompleteProcessingAndRemainInsufficient() {
        var outcome = new StructuredCompletionGate().evaluate(snapshot(3, 2, true, true));

        assertThat(outcome.processingStatus()).isEqualTo(GenerationTaskStatus.COMPLETED);
        assertThat(outcome.coverageStatus()).isEqualTo(StructuredCoverageStatus.INSUFFICIENT);
        assertThat(outcome.artifactPublishable()).isTrue();
    }

    @Test
    void cancellationAndFailureCannotPublishACompletedArtifact() {
        var gate = new StructuredCompletionGate();
        var cancelled = gate.evaluate(new StructuredCompletionGate.Snapshot(
                true, 2, 2, true, true, 2, 2, 1, true, 3, 0, true, true));
        var failed = gate.evaluate(new StructuredCompletionGate.Snapshot(
                false, 2, 1, false, false, 2, 0, 0, false, 0, 2, true, false));

        assertThat(cancelled.processingStatus()).isEqualTo(GenerationTaskStatus.CANCELLED);
        assertThat(failed.processingStatus()).isEqualTo(GenerationTaskStatus.FAILED);
        assertThat(cancelled.artifactPublishable()).isFalse();
        assertThat(failed.artifactPublishable()).isFalse();
    }

    private static StructuredCompletionGate.Snapshot snapshot(
            int formalPointTotal, int formalPointCovered, boolean allTerminal, boolean artifactValidated) {
        return new StructuredCompletionGate.Snapshot(
                true, 4, 4, true, true, formalPointTotal, formalPointCovered, 2,
                artifactValidated, 8, 0, allTerminal, false);
    }
}
