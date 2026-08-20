package com.testcaseagent.scope;

import java.util.List;
import java.util.Map;

/**
 * Server-only material leaf that can be frozen into a task-owned RequirementScope.
 *
 * [Req-ID]: REQ-CAT-004, REQ-SCP-001
 */
public record ScopeSelection(
        String id,
        String knowledgeBaseId,
        String systemId,
        String versionId,
        String projectId,
        String materialCategory,
        String admissionTypeKey,
        List<String> documentIds,
        Map<String, String> documentSha256ById) {
    public ScopeSelection {
        documentIds = List.copyOf(documentIds);
        documentSha256ById = Map.copyOf(documentSha256ById);
    }
}
