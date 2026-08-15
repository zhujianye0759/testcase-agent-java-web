package com.testcaseagent.scope;

import java.util.List;

/**
 * Browser-safe hierarchy containing business labels and opaque application-owned IDs only.
 *
 * [Req-ID]: REQ-CAT-002
 */
public record ScopeCatalogView(List<KnowledgeBaseOption> knowledgeBases) {
    public ScopeCatalogView {
        knowledgeBases = List.copyOf(knowledgeBases);
    }

    public record KnowledgeBaseOption(String id, String label, List<SystemOption> systems) {
        public KnowledgeBaseOption { systems = List.copyOf(systems); }
    }

    public record SystemOption(String id, String label, List<VersionOption> versions) {
        public SystemOption { versions = List.copyOf(versions); }
    }

    public record VersionOption(String id, String label, List<MaterialTypeOption> materialTypes) {
        public VersionOption { materialTypes = List.copyOf(materialTypes); }
    }

    public record MaterialTypeOption(String id, String label, int documentCount) { }
}
