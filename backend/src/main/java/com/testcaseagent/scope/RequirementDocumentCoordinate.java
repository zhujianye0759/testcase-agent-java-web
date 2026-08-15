package com.testcaseagent.scope;

/**
 * Identifies one document selected into a frozen requirement snapshot.
 *
 * [Req-ID]: REQ-SCP-001
 */
public record RequirementDocumentCoordinate(String documentId) {

    public RequirementDocumentCoordinate {
        documentId = ScopeValues.requireText(documentId, "documentId");
    }
}
