package com.testcaseagent.markdown;

/**
 * [Req-ID]: REQ-ANA-005
 *
 * <p>One candidate requirement or feature audit finding returned by a generation batch.</p>
 */
public record MarkdownAuditRow(int sequence, String subjectOrFeature, String issueCategory, String evidenceComparison) {
}
