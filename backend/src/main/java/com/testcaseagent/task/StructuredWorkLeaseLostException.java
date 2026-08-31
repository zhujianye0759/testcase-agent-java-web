package com.testcaseagent.task;

/** Fixed, content-free failure used when the current worker no longer owns its structured attempt. */
public final class StructuredWorkLeaseLostException extends IllegalStateException {
    /** [Req-ID]: REQ-SEW-003 */
    public StructuredWorkLeaseLostException() {
        super("Structured work lease was lost during execution");
    }
}
