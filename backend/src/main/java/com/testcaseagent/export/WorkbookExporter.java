package com.testcaseagent.export;

/** Exports validated persisted rows without accepting raw model output. [Req-ID]: REQ-EXP-001, REQ-EXP-007, REQ-SGD-003 */
public interface WorkbookExporter {
    WorkbookArtifact exportMarkdown(MarkdownWorkbookExportRequest request);

    /** Exports Java-validated structured business records without a Markdown conversion step. */
    WorkbookArtifact exportStructured(StructuredWorkbookExportRequest request);

    /** Exports a bounded row source without loading all task-owned rows into memory. [Req-ID]: REQ-TGV2-009 */
    WorkbookArtifact exportStructuredRows(StructuredWorkbookRowSource source);
}
