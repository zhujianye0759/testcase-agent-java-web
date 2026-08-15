package com.testcaseagent.scope;

/**
 * Carries the authoritative coordinates resolved for one formal evidence item.
 *
 * [Req-ID]: REQ-SCP-002
 */
public record EvidenceCoordinate(
        String knowledgeBaseId,
        String systemId,
        String versionId,
        String materialCategory,
        String projectId,
        String documentId,
        String location) {

    public EvidenceCoordinate {
        knowledgeBaseId = ScopeValues.requireText(knowledgeBaseId, "knowledgeBaseId");
        systemId = ScopeValues.requireText(systemId, "systemId");
        versionId = ScopeValues.requireText(versionId, "versionId");
        materialCategory = ScopeValues.requireText(materialCategory, "materialCategory");
        projectId = ScopeValues.optionalText(projectId);
        documentId = ScopeValues.requireText(documentId, "documentId");
        location = ScopeValues.requireText(location, "location");
    }
}
