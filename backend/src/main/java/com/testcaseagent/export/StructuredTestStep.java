package com.testcaseagent.export;

/** One already-validated action and expectation pair for a structured testcase row. [Req-ID]: REQ-SGD-003 */
public record StructuredTestStep(int stepNo, String action, String expected, String evaluationCriteria,
        String terminationOrError, String resultCollection) {
    /** Backward-compatible projection for persisted V12 rows. */
    public StructuredTestStep(int stepNo, String action, String expected) {
        this(stepNo, action, expected, "", "", "");
    }
}
