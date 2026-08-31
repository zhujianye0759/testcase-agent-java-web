package com.testcaseagent.export;

/** Exports validated persisted rows without accepting raw model output. [Req-ID]: REQ-EXP-001, REQ-EXP-007, REQ-SGD-003, REQ-TGV2-009 */
public interface WorkbookExporter {
    WorkbookArtifact exportMarkdown(MarkdownWorkbookExportRequest request);

    /** Exports Java-validated structured business records without a Markdown conversion step. */
    WorkbookArtifact exportStructured(StructuredWorkbookExportRequest request);

    /** Exports a bounded row source without loading all task-owned rows into memory. [Req-ID]: REQ-TGV2-009 */
    WorkbookArtifact exportStructuredRows(StructuredWorkbookRowSource source);

    /**
     * Exports a V2 row source with the V2 reader-facing sheet contract.
     *
     * <p>The separate entry point prevents a new workflow from renaming historical V1 workbooks or regenerated V1
     * artifacts while both versions share the same validated row and streaming implementation.</p>
     *
     * @param source validated V2 rows in stable source order
     * @return the atomically published workbook artifact
     * [Req-ID]: REQ-TGV2-009, REQ-TGV2-010
     */
    WorkbookArtifact exportV2StructuredRows(StructuredWorkbookRowSource source);
}
