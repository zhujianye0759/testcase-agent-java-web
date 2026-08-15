package com.testcaseagent.task;

import java.util.EnumSet;

/**
 * Persisted lifecycle for one bounded generation batch.
 *
 * [Req-ID]: REQ-TSK-002, REQ-TSK-007
 */
public enum GenerationBatchStatus {
    QUEUED,
    RUNNING,
    ACCEPTED,
    FAILED,
    CANCELLED;

    public boolean canTransitionTo(GenerationBatchStatus target) {
        return switch (this) {
            case QUEUED -> EnumSet.of(RUNNING, CANCELLED).contains(target);
            case RUNNING -> EnumSet.of(ACCEPTED, FAILED, CANCELLED).contains(target);
            case ACCEPTED, FAILED, CANCELLED -> false;
        };
    }

    public boolean isTerminal() {
        return switch (this) {
            case ACCEPTED, FAILED, CANCELLED -> true;
            default -> false;
        };
    }
}
