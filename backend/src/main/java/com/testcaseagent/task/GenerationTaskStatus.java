package com.testcaseagent.task;

import java.util.EnumSet;

/**
 * Truthful lifecycle for one persisted generation task.
 *
 * [Req-ID]: REQ-TSK-002, REQ-TSK-007
 */
public enum GenerationTaskStatus {
    QUEUED,
    AUDITING,
    GENERATING,
    VALIDATING,
    COMPLETED,
    PARTIAL,
    FAILED,
    CANCELLED;

    public boolean canTransitionTo(GenerationTaskStatus target) {
        return switch (this) {
            case QUEUED -> EnumSet.of(AUDITING, CANCELLED).contains(target);
            // A fully traversed and reconciled ALL task can truthfully end as PARTIAL when every frozen target is
            // explicitly ineligible. It must not fabricate a generation batch just to reach a later lifecycle state.
            case AUDITING -> EnumSet.of(GENERATING, PARTIAL, FAILED, CANCELLED).contains(target);
            case GENERATING -> EnumSet.of(VALIDATING, FAILED, CANCELLED).contains(target);
            case VALIDATING -> EnumSet.of(COMPLETED, PARTIAL, FAILED, CANCELLED).contains(target);
            case PARTIAL, FAILED -> target == QUEUED;
            case COMPLETED, CANCELLED -> false;
        };
    }

    public void requireTransitionTo(GenerationTaskStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException("Illegal task status transition from " + this + " to " + target);
        }
    }

    public boolean isTerminal() {
        return switch (this) {
            case COMPLETED, PARTIAL, FAILED, CANCELLED -> true;
            default -> false;
        };
    }
}
