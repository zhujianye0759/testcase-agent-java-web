package com.testcaseagent.knowledgeagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Exact input for one feature-list and requirement-fact reconciliation. [Req-ID]: REQ-SKI-003 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record FeatureScopeReconciliationInput(
        String operation,
        @JsonProperty("function_list_items") List<FunctionListItem> functionListItems,
        @JsonProperty("requirement_facts") List<RequirementFact> requirementFacts) {
    static final String OPERATION = "reconcile";
    /** Creates the only permitted reconciliation operation. */
    public FeatureScopeReconciliationInput(List<FunctionListItem> functionListItems, List<RequirementFact> requirementFacts) {
        this(OPERATION, functionListItems, requirementFacts);
    }
    public FeatureScopeReconciliationInput {
        if (!OPERATION.equals(operation)) throw new IllegalArgumentException("operation must be reconcile");
        functionListItems = StructuredSkillContract.list(functionListItems, "functionListItems", 1, 200);
        requirementFacts = StructuredSkillContract.list(requirementFacts, "requirementFacts", 0, 200);
        StructuredSkillContract.uniqueKeys(functionListItems.stream().map(FunctionListItem::itemKey).toList(), "functionListItem");
        StructuredSkillContract.uniqueKeys(requirementFacts.stream().map(RequirementFact::factKey).toList(), "requirementFact");
    }
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record FunctionListItem(@JsonProperty("item_key") String itemKey, String path, String description,
            @JsonProperty("evidence_keys") List<String> evidenceKeys) {
        public FunctionListItem { itemKey = StructuredSkillContract.key(itemKey, "itemKey"); path = StructuredSkillContract.text(path, "path"); description = StructuredSkillContract.text(description, "description"); evidenceKeys = StructuredSkillContract.keyReferences(evidenceKeys, "evidenceKeys"); }
    }
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RequirementFact(@JsonProperty("fact_key") String factKey, String function,
            @JsonProperty("evidence_keys") List<String> evidenceKeys) {
        public RequirementFact { factKey = StructuredSkillContract.key(factKey, "factKey"); function = StructuredSkillContract.text(function, "function"); evidenceKeys = StructuredSkillContract.keyReferences(evidenceKeys, "evidenceKeys"); }
    }
}
