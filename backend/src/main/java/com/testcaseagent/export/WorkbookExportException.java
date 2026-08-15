package com.testcaseagent.export;

/** [Req-ID]: REQ-EXP-001, REQ-EXP-005 */
public final class WorkbookExportException extends RuntimeException {
    public WorkbookExportException(String message, Throwable cause) { super(message, cause); }
    public WorkbookExportException(String message) { super(message); }
}
