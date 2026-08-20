package com.testcaseagent.structuredgeneration;

/** Task-level structured processing lifecycle exposed by persistence and the web projection. [Req-ID]: REQ-STG-007 */
public enum StructuredProcessingStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
