package com.testcaseagent.fewshot;

import com.testcaseagent.scope.RequirementScope;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable department example-library coordinates, separate from RequirementScope.
 *
 * [Req-ID]: REQ-FEW-001
 */
public record ExampleScope(String knowledgeBaseId, List<String> documentIds,
        Map<String, ExampleQualityKind> expectedQualityKinds) {

    public ExampleScope {
        knowledgeBaseId = FewShotValues.requireText(knowledgeBaseId, "knowledgeBaseId");
        Objects.requireNonNull(documentIds, "documentIds must not be null");
        if (documentIds.isEmpty()) {
            throw new IllegalArgumentException("documentIds must not be empty");
        }
        documentIds = documentIds.stream()
                .map(id -> FewShotValues.requireText(id, "documentId"))
                .sorted()
                .toList();
        if (documentIds.stream().distinct().count() != documentIds.size()) {
            throw new IllegalArgumentException("documentIds must not contain duplicates");
        }
        expectedQualityKinds = expectedQualityKinds == null ? Map.of() : Map.copyOf(expectedQualityKinds);
        if (!expectedQualityKinds.isEmpty() && !expectedQualityKinds.keySet().equals(java.util.Set.copyOf(documentIds))) {
            throw new IllegalArgumentException("expectedQualityKinds must declare every example document exactly once");
        }
        if (expectedQualityKinds.values().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("expectedQualityKinds must not contain null values");
        }
    }

    public ExampleScope(String knowledgeBaseId, List<String> documentIds) {
        this(knowledgeBaseId, documentIds, Map.of());
    }

    public static ExampleScope freeze(String knowledgeBaseId, List<String> documentIds) {
        return new ExampleScope(knowledgeBaseId, documentIds);
    }

    public static ExampleScope freeze(String knowledgeBaseId, Map<String, ExampleQualityKind> expectedQualityKinds) {
        return new ExampleScope(knowledgeBaseId, List.copyOf(expectedQualityKinds.keySet()), expectedQualityKinds);
    }

    /**
     * Prevents the department example library from becoming the formal requirement source.
     *
     * @param requirementScope frozen formal evidence scope
     */
    public void requireIndependentFrom(RequirementScope requirementScope) {
        if (knowledgeBaseId.equals(requirementScope.knowledgeBaseId())) {
            throw new FewShotSelectionViolation("ExampleScope must be independent from RequirementScope");
        }
    }

    public String scopeHash() {
        String documents = documentIds.stream()
                .sorted(Comparator.naturalOrder())
                .map(id -> FewShotValues.lengthPrefixed(id) + FewShotValues.lengthPrefixed(
                        expectedQualityKinds.containsKey(id) ? expectedQualityKinds.get(id).name() : "UNSPECIFIED"))
                .reduce("", String::concat);
        return FewShotValues.sha256(FewShotValues.lengthPrefixed(knowledgeBaseId) + "|" + documents);
    }

    public ExampleQualityKind expectedQualityKind(String documentId) {
        ExampleQualityKind qualityKind = expectedQualityKinds.get(documentId);
        if (qualityKind == null) {
            throw new FewShotSelectionViolation("Example document has no configured quality kind: " + documentId);
        }
        return qualityKind;
    }
}
