package com.testcaseagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Defines the public state-machine contract for durable generation work.
 *
 * [Test-Ref]: GenerationTaskStateMachineTest
 * [Req-ID]: REQ-TSK-002, REQ-TSK-007
 */
class GenerationTaskStateMachineTest {

    @Test
    void allowsTheNormalTaskLifecycleAndOnlyTruthfulTerminalStates() {
        assertThat(GenerationTaskStatus.QUEUED.canTransitionTo(GenerationTaskStatus.AUDITING)).isTrue();
        assertThat(GenerationTaskStatus.AUDITING.canTransitionTo(GenerationTaskStatus.GENERATING)).isTrue();
        assertThat(GenerationTaskStatus.GENERATING.canTransitionTo(GenerationTaskStatus.VALIDATING)).isTrue();
        assertThat(GenerationTaskStatus.VALIDATING.canTransitionTo(GenerationTaskStatus.COMPLETED)).isTrue();
        assertThat(GenerationTaskStatus.VALIDATING.canTransitionTo(GenerationTaskStatus.PARTIAL)).isTrue();
        assertThat(GenerationTaskStatus.VALIDATING.canTransitionTo(GenerationTaskStatus.FAILED)).isTrue();
        assertThat(GenerationTaskStatus.COMPLETED.canTransitionTo(GenerationTaskStatus.FAILED)).isFalse();
    }

    @Test
    void rejectsSkippedAndTerminalTaskTransitions() {
        assertThatThrownBy(() -> GenerationTaskStatus.QUEUED.requireTransitionTo(GenerationTaskStatus.GENERATING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QUEUED")
                .hasMessageContaining("GENERATING");
        assertThatThrownBy(() -> GenerationTaskStatus.COMPLETED.requireTransitionTo(GenerationTaskStatus.QUEUED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void allowsBatchCompletionOnlyAfterAClaimedRun() {
        assertThat(GenerationBatchStatus.QUEUED.canTransitionTo(GenerationBatchStatus.RUNNING)).isTrue();
        assertThat(GenerationBatchStatus.RUNNING.canTransitionTo(GenerationBatchStatus.ACCEPTED)).isTrue();
        assertThat(GenerationBatchStatus.RUNNING.canTransitionTo(GenerationBatchStatus.FAILED)).isTrue();
        assertThat(GenerationBatchStatus.QUEUED.canTransitionTo(GenerationBatchStatus.ACCEPTED)).isFalse();
        assertThat(GenerationBatchStatus.ACCEPTED.isTerminal()).isTrue();
        assertThat(GenerationBatchStatus.RUNNING.isTerminal()).isFalse();
    }
}
