package com.testcaseagent.task;

/** Executes the isolated structured-Skill route for a newly claimed ALL task. [Req-ID]: REQ-STG-001 */
public interface StructuredAllGenerationCoordinator {
    /** Runs the complete structured workflow without invoking the legacy Markdown Agent path. */
    void execute(String taskId, CreateGenerationTaskRequest request);
}
