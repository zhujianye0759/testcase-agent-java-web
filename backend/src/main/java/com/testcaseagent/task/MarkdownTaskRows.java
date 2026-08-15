package com.testcaseagent.task;

import com.testcaseagent.markdown.MarkdownAuditRow;
import com.testcaseagent.markdown.MarkdownTestCaseRow;
import java.util.List;

/**
 * [Req-ID]: REQ-TSK-005, REQ-TSK-009, REQ-ANA-006
 *
 * <p>The persisted, ordered Markdown rows accepted across one task's batches. Only accepted batches
 * contribute rows, so a partial task remains safely exportable without inferring data from failures.</p>
 */
public record MarkdownTaskRows(List<MarkdownAuditRow> auditRows, List<MarkdownTestCaseRow> testCaseRows) {

    public MarkdownTaskRows {
        auditRows = List.copyOf(auditRows);
        testCaseRows = List.copyOf(testCaseRows);
    }
}
