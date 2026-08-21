package com.testcaseagent.knowledgeagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Immutable formal fact and its current test-point evidence closure.
 *
 * <p>The evidence keys are carried with their texts so KEE cannot use opaque identities as evidence.
 * This input is never a persisted fact or a substitute for Java's final validation.</p>
 *
 * [Req-ID]: REQ-FTG-004
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record FormalSupport(
        @JsonProperty("fact_key") String factKey,
        String function,
        List<String> roles,
        @JsonProperty("trigger_conditions") List<String> triggerConditions,
        List<String> inputs,
        @JsonProperty("business_rules") List<String> businessRules,
        List<String> outputs,
        List<String> permissions,
        @JsonProperty("state_changes") List<String> stateChanges,
        @JsonProperty("exception_handling") List<String> exceptionHandling,
        @JsonProperty("external_dependencies") List<String> externalDependencies,
        @JsonProperty("evidence_keys") List<String> evidenceKeys,
        @JsonProperty("evidence_texts") List<String> evidenceTexts) {

    public FormalSupport {
        factKey = StructuredSkillContract.key(factKey, "formalSupport.factKey");
        function = StructuredSkillContract.text(function, "formalSupport.function");
        roles = StructuredSkillContract.texts(roles, "formalSupport.roles");
        triggerConditions = StructuredSkillContract.texts(triggerConditions, "formalSupport.triggerConditions");
        inputs = StructuredSkillContract.texts(inputs, "formalSupport.inputs");
        businessRules = StructuredSkillContract.texts(businessRules, "formalSupport.businessRules");
        outputs = StructuredSkillContract.texts(outputs, "formalSupport.outputs");
        permissions = StructuredSkillContract.texts(permissions, "formalSupport.permissions");
        stateChanges = StructuredSkillContract.texts(stateChanges, "formalSupport.stateChanges");
        exceptionHandling = StructuredSkillContract.texts(exceptionHandling, "formalSupport.exceptionHandling");
        externalDependencies = StructuredSkillContract.texts(externalDependencies, "formalSupport.externalDependencies");
        evidenceKeys = StructuredSkillContract.keyReferences(evidenceKeys, "formalSupport.evidenceKeys");
        evidenceTexts = StructuredSkillContract.texts(evidenceTexts, "formalSupport.evidenceTexts");
        if (evidenceKeys.isEmpty() || evidenceTexts.isEmpty()) {
            throw new IllegalArgumentException("formalSupport evidence keys and texts must not be empty");
        }
        if (evidenceKeys.size() != evidenceTexts.size()) {
            throw new IllegalArgumentException("formalSupport evidence keys and texts must be aligned");
        }
    }

    /** Compatibility constructor for pre-frozen callers; it cannot satisfy a formal executable call. */
    public FormalSupport(String factKey, String function, List<String> roles, List<String> triggerConditions,
            List<String> inputs, List<String> businessRules, List<String> outputs, List<String> permissions,
            List<String> stateChanges, List<String> exceptionHandling, List<String> externalDependencies,
            List<String> evidenceTexts) {
        this(factKey, function, roles, triggerConditions, inputs, businessRules, outputs, permissions,
                stateChanges, exceptionHandling, externalDependencies, List.of(), evidenceTexts);
    }
}
