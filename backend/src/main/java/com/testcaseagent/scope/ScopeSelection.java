package com.testcaseagent.scope;

import java.util.List;

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
        String materialCategory,
        String admissionTypeKey,
        List<String> documentIds) {
    public ScopeSelection {
        documentIds = List.copyOf(documentIds);
    }
}
