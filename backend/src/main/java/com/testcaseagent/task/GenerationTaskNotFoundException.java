package com.testcaseagent.task;

/** [Req-ID]: REQ-TSK-002 */
public final class GenerationTaskNotFoundException extends RuntimeException {
    public GenerationTaskNotFoundException(String taskId) {
        super("Generation task was not found: " + taskId);
    }
}
