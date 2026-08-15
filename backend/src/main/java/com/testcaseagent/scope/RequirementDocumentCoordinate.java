package com.testcaseagent.scope;

/**
 * Identifies one document selected into a frozen requirement snapshot.
 *
 * [Req-ID]: REQ-SCP-001
 */
public record RequirementDocumentCoordinate(String documentId, String materialTypeKey) {

    public RequirementDocumentCoordinate {
        documentId = ScopeValues.requireText(documentId, "documentId");
        materialTypeKey = ScopeValues.optionalText(materialTypeKey);
    }

    /**
     * Retains compatibility with task snapshots written before a document's admission type was frozen.
     *
     * [Req-ID]: REQ-SMR-002
     */
    public RequirementDocumentCoordinate(String documentId) {
        this(documentId, null);
    }
}
