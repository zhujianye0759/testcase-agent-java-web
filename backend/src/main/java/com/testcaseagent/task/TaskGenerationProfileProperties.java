package com.testcaseagent.task;

import com.testcaseagent.fewshot.ExampleQualityKind;
import com.testcaseagent.fewshot.ExampleScope;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Server-only generation profile shared by every dynamically selected requirement scope.
 *
 * [Req-ID]: REQ-CAT-005, REQ-KAG-006
 */
@ConfigurationProperties(prefix = "app.task-generation-profile")
public class TaskGenerationProfileProperties {
    private String agentId;
    private String exampleKnowledgeBaseId;
    private List<String> exampleGoodDocumentIds = List.of();
    private List<String> exampleBadDocumentIds = List.of();
    private Duration catalogCacheTtl = Duration.ofMinutes(5);
    private int knowledgeBasePageSize = 100;
    private int documentPageSize = 20;

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getExampleKnowledgeBaseId() { return exampleKnowledgeBaseId; }
    public void setExampleKnowledgeBaseId(String exampleKnowledgeBaseId) { this.exampleKnowledgeBaseId = exampleKnowledgeBaseId; }
    public List<String> getExampleGoodDocumentIds() { return exampleGoodDocumentIds; }
    public void setExampleGoodDocumentIds(List<String> values) { this.exampleGoodDocumentIds = values == null ? List.of() : List.copyOf(values); }
    public List<String> getExampleBadDocumentIds() { return exampleBadDocumentIds; }
    public void setExampleBadDocumentIds(List<String> values) { this.exampleBadDocumentIds = values == null ? List.of() : List.copyOf(values); }
    public Duration getCatalogCacheTtl() { return catalogCacheTtl; }
    public void setCatalogCacheTtl(Duration catalogCacheTtl) { this.catalogCacheTtl = catalogCacheTtl; }
    public int getKnowledgeBasePageSize() { return knowledgeBasePageSize; }
    public void setKnowledgeBasePageSize(int knowledgeBasePageSize) { this.knowledgeBasePageSize = knowledgeBasePageSize; }
    public int getDocumentPageSize() { return documentPageSize; }
    public void setDocumentPageSize(int documentPageSize) { this.documentPageSize = documentPageSize; }

    public ExampleScope exampleScope() {
        Map<String, ExampleQualityKind> expectedKinds = new LinkedHashMap<>();
        exampleGoodDocumentIds.forEach(id -> put(expectedKinds, id, ExampleQualityKind.GOOD_CASE));
        exampleBadDocumentIds.forEach(id -> put(expectedKinds, id, ExampleQualityKind.BAD_CASE));
        return ExampleScope.freeze(requireText(exampleKnowledgeBaseId, "exampleKnowledgeBaseId"), expectedKinds);
    }

    public String requiredAgentId() {
        return requireText(agentId, "agentId");
    }

    private static void put(Map<String, ExampleQualityKind> values, String id, ExampleQualityKind kind) {
        String normalized = requireText(id, "exampleDocumentId");
        if (values.put(normalized, kind) != null) {
            throw new IllegalArgumentException("Example document must have exactly one configured quality kind: " + normalized);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
