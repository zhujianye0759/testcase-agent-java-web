package com.testcaseagent.fewshot;

/**
 * Signals a request that would use a non-eligible or out-of-scope example.
 *
 * [Req-ID]: REQ-FEW-001, REQ-FEW-002, REQ-FEW-003
 */
public final class FewShotSelectionViolation extends RuntimeException {

    public FewShotSelectionViolation(String message) {
        super(message);
    }
}
