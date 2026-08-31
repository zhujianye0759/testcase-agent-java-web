package com.testcaseagent.task;

/** Signals that a user-triggered retry no longer has one safe eligible target. [Req-ID]: REQ-ESR-004 */
public final class GenerationTaskRetryConflictException extends RuntimeException {

    public GenerationTaskRetryConflictException() {
        super("Task retry is not currently eligible");
    }
}
