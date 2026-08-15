package com.testcaseagent.markdown;

import java.util.List;

/**
 * [Req-ID]: REQ-KAG-004, REQ-EXP-007
 *
 * <p>The accepted completed Markdown diagnostic snapshot and its two immutable, ordered table views.</p>
 */
public record MarkdownGenerationResult(
        String rawMarkdown,
        List<MarkdownAuditRow> auditRows,
        List<MarkdownTestCaseRow> testCaseRows) {

    public MarkdownGenerationResult {
        auditRows = List.copyOf(auditRows);
        testCaseRows = List.copyOf(testCaseRows);
    }
}
