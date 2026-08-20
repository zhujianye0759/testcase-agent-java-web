package com.testcaseagent.task;

import com.testcaseagent.testcase.GenerationTaskMode;
import java.util.List;

/**
 * Browser-safe shared state for one generation task.
 *
 * [Req-ID]: REQ-TSK-002, REQ-TSK-007, REQ-EXP-001, REQ-CWR-001
 */
public record GenerationTaskDetail(
        String id,
        GenerationTaskMode taskMode,
        GenerationTaskStatus status,
        int totalBatches,
        int completedBatches,
        boolean artifactReady,
        String artifactId,
        String artifactSha256,
        String validationFailure,
        String failureSummary,
        List<GenerationBatchDetail> batches,
        MarkdownTaskRows acceptedRows,
        CreateGenerationTaskRequest request,
        GenerationTaskBusinessProgress businessProgress,
        StructuredGenerationTaskDetail structuredResult) {
}
