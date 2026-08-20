package com.testcaseagent.scope;

import java.util.List;
import java.util.Optional;

/**
 * Read-only application port for discovering task-eligible KEE scope coordinates.
 *
 * [Req-ID]: REQ-KAG-009, REQ-CAT-001, REQ-CAT-003
 */
public interface KnowledgeScopeCatalogPort {

    List<KnowledgeBase> listKnowledgeBases();

    Optional<ScopeContainer> getScopeContainer(String knowledgeBaseId);

    List<SystemVersion> listSystemVersions(String knowledgeBaseId);

    List<KnowledgeDocument> listDocuments(String knowledgeBaseId);

    record KnowledgeBase(String id, String name, String type) { }

    record ScopeContainer(String knowledgeBaseId, String containerType, String systemId, String systemName) { }

    record SystemVersion(String id, String systemId, String displayName, String status, boolean current) {
        public boolean active() {
            return "active".equals(status);
        }
    }

    record KnowledgeDocument(
            String id,
            String knowledgeBaseId,
            String fileSha256,
            String parseStatus,
            String enableStatus,
            DocumentScope scope) { }

    record DocumentScope(
            String systemId,
            String versionId,
            String projectId,
            String contentCategory,
            String contentTypeKey,
            String contentTypeLabel) { }
}
