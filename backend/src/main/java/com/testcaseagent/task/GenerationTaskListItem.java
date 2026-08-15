package com.testcaseagent.task;

import com.testcaseagent.testcase.GenerationTaskMode;
import java.time.Instant;

/**
 * One compact shared task-list row.
 *
 * [Req-ID]: REQ-TSK-002, REQ-TSK-007
 */
public record GenerationTaskListItem(
        String id,
        GenerationTaskMode taskMode,
        GenerationTaskStatus status,
        Instant createdAt,
        int totalBatches,
        int completedBatches,
        String failureSummary,
        boolean artifactReady) {
}
