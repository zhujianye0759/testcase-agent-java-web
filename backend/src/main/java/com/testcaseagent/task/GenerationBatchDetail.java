package com.testcaseagent.task;

/**
 * Shared browser-safe batch state under one task.
 *
 * [Req-ID]: REQ-TSK-002, REQ-TSK-007
 */
public record GenerationBatchDetail(
        String id,
        String featureId,
        GenerationBatchStatus status,
        String failureSummary) {
}
