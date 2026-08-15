package com.testcaseagent.scope;

/**
 * Applies task-owned trust boundaries to evidence and retry snapshots.
 *
 * [Req-ID]: REQ-SCP-001, REQ-SCP-002
 */
public final class ScopePolicy {

    /**
     * Rejects formal evidence that does not resolve to the frozen task coordinates.
     *
     * @param requirementScope immutable task requirement snapshot
     * @param evidence resolved evidence coordinate
     */
    public void requireInRequirementScope(RequirementScope requirementScope, EvidenceCoordinate evidence) {
        if (!requirementScope.allows(evidence)) {
            throw new ScopeViolation("Evidence is outside frozen RequirementScope");
        }
    }

    /**
     * Rejects retry input that would change the original task scope.
     *
     * @param frozenScope original task snapshot
     * @param retryScope requested retry snapshot
     */
    public void requireSameRetryScope(RequirementScope frozenScope, RequirementScope retryScope) {
        if (!frozenScope.scopeHash().equals(retryScope.scopeHash())) {
            throw new ScopeViolation("Retry scope differs from frozen RequirementScope");
        }
    }
}
