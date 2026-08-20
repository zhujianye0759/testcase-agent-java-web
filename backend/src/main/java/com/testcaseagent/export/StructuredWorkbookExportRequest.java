package com.testcaseagent.export;

import java.util.List;

/** Immutable, Java-validated structured records for one two-sheet export. [Req-ID]: REQ-SGD-003, REQ-SGD-004 */
public record StructuredWorkbookExportRequest(
        String taskId, List<StructuredReviewRow> reviewRows, List<StructuredTestCaseRow> testCaseRows) {

    /** Copies the caller collections so an export cannot observe a partially changed record set. */
    public StructuredWorkbookExportRequest {
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("taskId must not be blank");
        if (reviewRows == null || testCaseRows == null) throw new IllegalArgumentException("Structured rows must not be null");
        reviewRows = List.copyOf(reviewRows);
        testCaseRows = List.copyOf(testCaseRows);
    }
}
