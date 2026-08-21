package com.testcaseagent.knowledgeagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashSet;
import java.util.List;

/**
 * Immutable formal fact and evidence text supplied only to one testcase-design model call.
 *
 * <p>This is a generation input, not a persisted result or a substitute for Java's final business validator.</p>
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
        evidenceTexts = StructuredSkillContract.texts(evidenceTexts, "formalSupport.evidenceTexts");
        if (evidenceTexts.isEmpty()) {
            throw new IllegalArgumentException("formalSupport.evidenceTexts must not be empty");
        }
        if (new HashSet<>(evidenceTexts).size() != evidenceTexts.size()) {
            throw new IllegalArgumentException("formalSupport.evidenceTexts must be unique");
        }
    }
}
