package com.testcaseagent.knowledgeagent;

import com.testcaseagent.scope.RequirementScope;
import java.util.List;
import java.util.Objects;

/**
 * Builds the only authorized transport scope from an immutable requirement snapshot or its
 * operation-specific, snapshot-derived subset.
 *
 * [Req-ID]: REQ-SKI-002, REQ-SMS-003
 */
public record StructuredSkillScope(List<String> knowledgeBaseIds, List<String> knowledgeIds, List<SystemScopePayload> systemScopes) {
    public StructuredSkillScope {
        knowledgeBaseIds = StructuredSkillContract.keyReferences(knowledgeBaseIds, "knowledgeBaseIds");
        knowledgeIds = StructuredSkillContract.keyReferences(knowledgeIds, "knowledgeIds");
        systemScopes = StructuredSkillContract.list(systemScopes, "systemScopes", 1, 1);
        if (knowledgeBaseIds.size() != 1 || knowledgeIds.isEmpty() || knowledgeIds.size() > 100) throw new IllegalArgumentException("structured scope has an invalid size");
        SystemScopePayload scope = systemScopes.get(0);
        if (!knowledgeBaseIds.get(0).equals(scope.knowledgeBaseId()) || !knowledgeIds.equals(scope.knowledgeIds())) throw new IllegalArgumentException("structured scope does not match the outer scope");
    }
    /** Creates the exact single scope accepted by the isolated structured endpoint. */
    public static StructuredSkillScope from(RequirementScope requirementScope) {
        Objects.requireNonNull(requirementScope, "requirementScope must not be null");
        if (requirementScope.projectId() == null || requirementScope.projectId().isBlank()) throw new IllegalArgumentException("RequirementScope projectId must be non-blank");
        List<String> documentIds = requirementScope.documents().stream().map(document -> document.documentId()).toList();
        return new StructuredSkillScope(List.of(requirementScope.knowledgeBaseId()), documentIds,
                List.of(new SystemScopePayload(requirementScope.knowledgeBaseId(), requirementScope.versionId(),
                        requirementScope.projectId(), documentIds)));
    }
}
