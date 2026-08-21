package com.testcaseagent.knowledgeagent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** Typed result of one bounded requirement-material quality review. [Req-ID]: REQ-SKI-004, REQ-FTG-007 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record RequirementMaterialQualityReviewResult(@JsonProperty("requirement_facts") List<RequirementFact> requirementFacts,
        @JsonProperty("review_findings") List<ReviewFinding> reviewFindings) {
    public RequirementMaterialQualityReviewResult {
        requirementFacts = StructuredSkillContract.list(requirementFacts, "requirementFacts", 0, 200);
        reviewFindings = StructuredSkillContract.list(reviewFindings, "reviewFindings", 0, 200);
        if (requirementFacts.isEmpty() && reviewFindings.isEmpty()) throw new IllegalArgumentException("result must contain a fact or finding");
        StructuredSkillContract.uniqueKeys(requirementFacts.stream().map(RequirementFact::factKey).toList(), "requirementFact");
        StructuredSkillContract.uniqueKeys(reviewFindings.stream().map(ReviewFinding::findingKey).toList(), "reviewFinding");
        if (reviewFindings.stream().map(ReviewFinding::rootCauseKind).filter(kind -> kind != null).collect(java.util.stream.Collectors.toSet()).size()
                != reviewFindings.stream().filter(finding -> finding.rootCauseKind() != null).count()) {
            throw new IllegalArgumentException("rootCauseKind must be unique within one bounded result");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RequirementFact(@JsonProperty("fact_key") String factKey, String function, List<String> roles,
            @JsonProperty("trigger_conditions") List<String> triggerConditions, List<String> inputs,
            @JsonProperty("business_rules") List<String> businessRules, List<String> outputs, List<String> permissions,
            @JsonProperty("state_changes") List<String> stateChanges, @JsonProperty("exception_handling") List<String> exceptionHandling,
            @JsonProperty("external_dependencies") List<String> externalDependencies, @JsonProperty("evidence_keys") List<String> evidenceKeys) {
        public RequirementFact {
            factKey = StructuredSkillContract.key(factKey, "factKey"); function = StructuredSkillContract.text(function, "function");
            roles = StructuredSkillContract.texts(roles, "roles"); triggerConditions = StructuredSkillContract.texts(triggerConditions, "triggerConditions");
            inputs = StructuredSkillContract.texts(inputs, "inputs"); businessRules = StructuredSkillContract.texts(businessRules, "businessRules");
            outputs = StructuredSkillContract.texts(outputs, "outputs"); permissions = StructuredSkillContract.texts(permissions, "permissions");
            stateChanges = StructuredSkillContract.texts(stateChanges, "stateChanges"); exceptionHandling = StructuredSkillContract.texts(exceptionHandling, "exceptionHandling");
            externalDependencies = StructuredSkillContract.texts(externalDependencies, "externalDependencies"); evidenceKeys = StructuredSkillContract.keyReferences(evidenceKeys, "evidenceKeys");
        }
    }

    public enum RootCauseKind { MISSING_DOCUMENT_TRACEABILITY, MISSING_FUNCTION_SCOPE, MISSING_ROLE_PERMISSION_MATRIX,
        MISSING_PROCESS_OR_STATE, MISSING_INPUT_OR_DATA_DICTIONARY, MISSING_BUSINESS_RULE, MISSING_OUTPUT,
        MISSING_EXCEPTION_HANDLING, MISSING_EXTERNAL_DEPENDENCY, MISSING_SECURITY_OR_AUDIT,
        MISSING_ENVIRONMENT_OR_CONFIGURATION, CONFLICTING_REQUIREMENT, AMBIGUOUS_REQUIREMENT;
        @JsonValue public String wireValue() { return name().toLowerCase(Locale.ROOT); }
        @JsonCreator public static RootCauseKind fromWire(String value) { return valueOf(value.toUpperCase(Locale.ROOT)); }
    }
    public enum HandlingLevel { BLOCKING, CONTINUE_INCOMPLETE, IMPROVEMENT;
        @JsonValue public String wireValue() { return name().toLowerCase(Locale.ROOT); }
        @JsonCreator public static HandlingLevel fromWire(String value) { return valueOf(value.toUpperCase(Locale.ROOT)); }
    }
    public enum ProposalStatus { PENDING_CONFIRMATION;
        @JsonValue public String wireValue() { return name().toLowerCase(Locale.ROOT); }
        @JsonCreator public static ProposalStatus fromWire(String value) { return valueOf(value.toUpperCase(Locale.ROOT)); }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record AffectedScope(@JsonProperty("unit_keys") List<String> unitKeys, String summary) {
        public AffectedScope { unitKeys = StructuredSkillContract.keyReferences(unitKeys, "affectedScope.unitKeys"); summary = StructuredSkillContract.text(summary, "affectedScope.summary"); }
    }
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record BadSourceExample(@JsonProperty("evidence_key") String evidenceKey, String quote) {
        public BadSourceExample { evidenceKey = StructuredSkillContract.key(evidenceKey, "badSourceExample.evidenceKey"); quote = StructuredSkillContract.text(quote, "badSourceExample.quote"); }
    }
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ProposedGoodExample(ProposalStatus status, String text) {
        public ProposedGoodExample { if (status == null) throw new IllegalArgumentException("proposedGoodExample.status must not be null"); text = StructuredSkillContract.text(text, "proposedGoodExample.text"); }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ReviewFinding(@JsonProperty("finding_key") String findingKey,
            @JsonProperty("root_cause_kind") RootCauseKind rootCauseKind,
            @JsonProperty("issue_type") String issueType, @JsonProperty("affected_scope") AffectedScope affectedScope,
            @JsonProperty("bad_source_example") BadSourceExample badSourceExample,
            @JsonProperty("proposed_good_example") ProposedGoodExample proposedGoodExample,
            String description, @JsonProperty("evidence_keys") List<String> evidenceKeys,
            @JsonProperty("test_design_impact") String testDesignImpact,
            @JsonProperty("current_project_recommendation") String currentProjectRecommendation,
            @JsonProperty("design_center_guideline_recommendation") String designCenterGuidelineRecommendation,
            @JsonProperty("handling_level") HandlingLevel handlingLevel) {
        public ReviewFinding {
            findingKey = StructuredSkillContract.key(findingKey, "findingKey");
            issueType = StructuredSkillContract.text(issueType, "issueType"); description = StructuredSkillContract.text(description, "description");
            evidenceKeys = StructuredSkillContract.keyReferences(evidenceKeys, "evidenceKeys"); testDesignImpact = StructuredSkillContract.text(testDesignImpact, "testDesignImpact");
            currentProjectRecommendation = StructuredSkillContract.text(currentProjectRecommendation, "currentProjectRecommendation");
            designCenterGuidelineRecommendation = StructuredSkillContract.text(designCenterGuidelineRecommendation, "designCenterGuidelineRecommendation");
            if (rootCauseKind == null || handlingLevel == null) {
                throw new IllegalArgumentException("rootCauseKind and handlingLevel must not be null");
            }
            if (affectedScope == null || badSourceExample == null || proposedGoodExample == null) {
                throw new IllegalArgumentException("frozen review finding fields must not be null");
            }
        }
    }
}
