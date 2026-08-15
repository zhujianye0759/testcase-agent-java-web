package com.testcaseagent.scope;

/**
 * Signals an evidence or retry request outside a frozen task trust boundary.
 *
 * [Req-ID]: REQ-SCP-001, REQ-SCP-002
 */
public final class ScopeViolation extends RuntimeException {

    public ScopeViolation(String message) {
        super(message);
    }
}
