package com.testcaseagent.export;

import com.testcaseagent.markdown.MarkdownAuditRow;
import com.testcaseagent.markdown.MarkdownTestCaseRow;

import java.util.List;

/**
 * [Req-ID]: REQ-EXP-001, REQ-EXP-007
 *
 * <p>Immutable, already-ordered accepted Markdown rows for one task export. The exporter receives rows
 * rather than raw Markdown so parsing and persistence remain outside the Apache POI adapter.</p>
 */
public record MarkdownWorkbookExportRequest(
        String taskId,
        List<MarkdownAuditRow> auditRows,
        List<MarkdownTestCaseRow> testCaseRows,
        boolean validationPassed,
        boolean partial) {

    public MarkdownWorkbookExportRequest {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        auditRows = List.copyOf(auditRows);
        testCaseRows = List.copyOf(testCaseRows);
    }
}
