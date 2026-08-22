package com.testcaseagent.scope;

import java.util.Comparator;
import java.util.List;

/**
 * Immutable, task-owned coordinates from which formal requirement evidence may be accepted.
 *
 * [Req-ID]: REQ-SCP-001
 */
public record RequirementScope(
        String knowledgeBaseId,
        String systemId,
        String versionId,
        String materialCategory,
        String projectId,
        List<RequirementDocumentCoordinate> documents) {

    public RequirementScope {
        knowledgeBaseId = ScopeValues.requireText(knowledgeBaseId, "knowledgeBaseId");
        systemId = ScopeValues.requireText(systemId, "systemId");
        versionId = ScopeValues.requireText(versionId, "versionId");
        materialCategory = ScopeValues.requireText(materialCategory, "materialCategory");
        projectId = ScopeValues.optionalText(projectId);
        documents = ScopeValues.sortedDistinctDocuments(documents);
    }

    public static RequirementScope freeze(
            String knowledgeBaseId,
            String systemId,
            String versionId,
            String materialCategory,
            String projectId,
            List<RequirementDocumentCoordinate> documents) {
        return new RequirementScope(knowledgeBaseId, systemId, versionId, materialCategory, projectId, documents);
    }

    /**
     * Derives a non-mutating one-document authorization from this frozen task snapshot.
     *
     * <p>This is only for an operation whose server-side contract is limited to one stored
     * document. The caller-provided material key remains outside this scope and is not used as
     * a document identity.</p>
     *
     * [Req-ID]: REQ-SMS-003
     *
     * @param documentId document already present in this frozen snapshot
     * @return a scope retaining every coordinate except unselected documents
     */
    public RequirementScope singleDocumentAuthorization(String documentId) {
        String selectedDocumentId = ScopeValues.requireText(documentId, "documentId");
        RequirementDocumentCoordinate selected = documents.stream()
                .filter(document -> document.documentId().equals(selectedDocumentId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("documentId is outside the frozen RequirementScope"));
        return new RequirementScope(knowledgeBaseId, systemId, versionId, materialCategory, projectId, List.of(selected));
    }

    /**
     * Produces a stable SHA-256 identity for this exact frozen coordinate set.
     *
     * @return lowercase SHA-256 snapshot hash
     */
    public String scopeHash() {
        String documentPart = documents.stream()
                .sorted(Comparator.comparing(RequirementDocumentCoordinate::documentId))
                .map(RequirementDocumentCoordinate::documentId)
                .map(ScopeValues::lengthPrefixed)
                .reduce("", (left, right) -> left + right);
        return ScopeValues.sha256(String.join("|",
                ScopeValues.lengthPrefixed(knowledgeBaseId),
                ScopeValues.lengthPrefixed(systemId),
                ScopeValues.lengthPrefixed(versionId),
                ScopeValues.lengthPrefixed(materialCategory),
                ScopeValues.nullableLengthPrefixed(projectId),
                documentPart));
    }

    boolean allows(EvidenceCoordinate evidence) {
        return knowledgeBaseId.equals(evidence.knowledgeBaseId())
                && systemId.equals(evidence.systemId())
                && versionId.equals(evidence.versionId())
                && materialCategory.equals(evidence.materialCategory())
                && (projectId == null || projectId.equals(evidence.projectId()))
                && documents.stream().map(RequirementDocumentCoordinate::documentId)
                        .anyMatch(evidence.documentId()::equals);
    }
}
