package com.testcaseagent.export;

/** Exports only validated, persisted Markdown rows. [Req-ID]: REQ-EXP-001, REQ-EXP-007 */
public interface WorkbookExporter {
    WorkbookArtifact exportMarkdown(MarkdownWorkbookExportRequest request);
}
