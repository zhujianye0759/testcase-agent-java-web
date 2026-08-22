package com.testcaseagent.scope;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Read-only application port for discovering task-eligible KEE scope coordinates.
 *
 * [Req-ID]: REQ-KAG-009, REQ-CAT-001, REQ-CAT-003, REQ-FSC-006
 */
public interface KnowledgeScopeCatalogPort {
    Pattern UUID_FILE_NAME = Pattern.compile("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

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
            DocumentScope scope,
            String fileName) {
        public KnowledgeDocument {
            fileName = readerFacingFileName(fileName);
        }
        public KnowledgeDocument(String id, String knowledgeBaseId, String fileSha256, String parseStatus,
                String enableStatus, DocumentScope scope) {
            this(id, knowledgeBaseId, fileSha256, parseStatus, enableStatus, scope, "材料文档");
        }

        private static String readerFacingFileName(String value) {
            if (value == null) return "材料文档";
            String normalized = value.replace('\\', '/');
            int finalSeparator = normalized.lastIndexOf('/');
            String baseName = (finalSeparator < 0 ? normalized : normalized.substring(finalSeparator + 1))
                    .replaceAll("\\p{Cntrl}", "").trim();
            if (baseName.isBlank() || UUID_FILE_NAME.matcher(baseName).matches() || baseName.startsWith("scope-")) {
                return "材料文档";
            }
            return baseName;
        }
    }

    record DocumentScope(
            String systemId,
            String versionId,
            String projectId,
            String contentCategory,
            String contentTypeKey,
            String contentTypeLabel) { }
}
