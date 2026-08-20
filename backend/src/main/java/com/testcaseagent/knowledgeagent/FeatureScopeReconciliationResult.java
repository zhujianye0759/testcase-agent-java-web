package com.testcaseagent.knowledgeagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;

/** Typed result of one feature-scope reconciliation. [Req-ID]: REQ-SKI-004 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record FeatureScopeReconciliationResult(List<Reconciliation> reconciliations) {
    public FeatureScopeReconciliationResult { reconciliations=StructuredSkillContract.list(reconciliations,"reconciliations",1,200); StructuredSkillContract.uniqueKeys(reconciliations.stream().map(Reconciliation::reconciliationKey).toList(),"reconciliation"); }
    public enum Classification { EXACT_MATCH, FUNCTION_LIST_ONLY, REQUIREMENTS_ONLY, CONFLICT, DUPLICATE, SPLIT, MERGE, INSUFFICIENT_EVIDENCE; @JsonValue public String wireValue(){return name().toLowerCase(java.util.Locale.ROOT);} @JsonCreator public static Classification fromWire(String value){return valueOf(value.toUpperCase(java.util.Locale.ROOT));} }
    public enum ConfirmationStatus { CONFIRMED, PENDING_CONFIRMATION; @JsonValue public String wireValue(){return name().toLowerCase(java.util.Locale.ROOT);} @JsonCreator public static ConfirmationStatus fromWire(String value){return valueOf(value.toUpperCase(java.util.Locale.ROOT));} }
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Reconciliation(@JsonProperty("reconciliation_key") String reconciliationKey,
            @JsonProperty("function_list_item_keys") List<String> functionListItemKeys,
            @JsonProperty("requirement_fact_keys") List<String> requirementFactKeys, Classification classification,
            @JsonProperty("evidence_keys") List<String> evidenceKeys, @JsonProperty("scope_recommendation") String scopeRecommendation,
            @JsonProperty("confirmation_status") ConfirmationStatus confirmationStatus) {
        public Reconciliation { reconciliationKey=StructuredSkillContract.key(reconciliationKey,"reconciliationKey"); functionListItemKeys=StructuredSkillContract.keyReferences(functionListItemKeys,"functionListItemKeys"); requirementFactKeys=StructuredSkillContract.keyReferences(requirementFactKeys,"requirementFactKeys"); if(functionListItemKeys.isEmpty()&&requirementFactKeys.isEmpty())throw new IllegalArgumentException("reconciliation requires a source key"); if(classification==null||confirmationStatus==null)throw new IllegalArgumentException("classification and confirmationStatus must not be null"); evidenceKeys=StructuredSkillContract.keyReferences(evidenceKeys,"evidenceKeys"); scopeRecommendation=StructuredSkillContract.text(scopeRecommendation,"scopeRecommendation"); }
    }
}
