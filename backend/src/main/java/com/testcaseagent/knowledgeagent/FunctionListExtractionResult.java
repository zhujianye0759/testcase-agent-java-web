package com.testcaseagent.knowledgeagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Typed output of the extract-function-list operation; KEE does not assign item keys. [Req-ID]: REQ-SKI-004, REQ-STG-003 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record FunctionListExtractionResult(
        String operation,
        @JsonProperty("function_list_items") List<FunctionListItem> functionListItems) {
    /** Creates the only permitted feature-list extraction result. */
    public FunctionListExtractionResult(List<FunctionListItem> functionListItems) {
        this(FunctionListExtractionInput.OPERATION, functionListItems);
    }

    public FunctionListExtractionResult {
        if (!FunctionListExtractionInput.OPERATION.equals(operation)) {
            throw new IllegalArgumentException("operation must be extract_function_list");
        }
        functionListItems = StructuredSkillContract.list(functionListItems, "functionListItems", 0, 200);
    }

    /** One extracted function item, deliberately without a model-owned item key. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record FunctionListItem(String path, String description, @JsonProperty("evidence_keys") List<String> evidenceKeys) {
        public FunctionListItem {
            path = StructuredSkillContract.text(path, "path");
            description = StructuredSkillContract.text(description, "description");
            evidenceKeys = StructuredSkillContract.list(evidenceKeys, "evidenceKeys", 1, 100);
            evidenceKeys.forEach(key -> StructuredSkillContract.key(key, "evidenceKey"));
            StructuredSkillContract.uniqueKeys(evidenceKeys, "evidenceKey");
        }
    }
}
