package com.testcaseagent.knowledgeagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;

/** Typed result of one requirement-material quality review. [Req-ID]: REQ-SKI-004 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record RequirementMaterialQualityReviewResult(@JsonProperty("requirement_facts") List<RequirementFact> requirementFacts,
        @JsonProperty("review_findings") List<ReviewFinding> reviewFindings) {
    public RequirementMaterialQualityReviewResult { requirementFacts = StructuredSkillContract.list(requirementFacts, "requirementFacts", 0, 200); reviewFindings = StructuredSkillContract.list(reviewFindings, "reviewFindings", 0, 200); if (requirementFacts.isEmpty() && reviewFindings.isEmpty()) throw new IllegalArgumentException("result must contain a fact or finding"); StructuredSkillContract.uniqueKeys(requirementFacts.stream().map(RequirementFact::factKey).toList(), "requirementFact"); StructuredSkillContract.uniqueKeys(reviewFindings.stream().map(ReviewFinding::findingKey).toList(), "reviewFinding"); }
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RequirementFact(@JsonProperty("fact_key") String factKey, String function, List<String> roles,
            @JsonProperty("trigger_conditions") List<String> triggerConditions, List<String> inputs,
            @JsonProperty("business_rules") List<String> businessRules, List<String> outputs, List<String> permissions,
            @JsonProperty("state_changes") List<String> stateChanges, @JsonProperty("exception_handling") List<String> exceptionHandling,
            @JsonProperty("external_dependencies") List<String> externalDependencies, @JsonProperty("evidence_keys") List<String> evidenceKeys) {
        public RequirementFact { factKey=StructuredSkillContract.key(factKey,"factKey"); function=StructuredSkillContract.text(function,"function"); roles=StructuredSkillContract.texts(roles,"roles"); triggerConditions=StructuredSkillContract.texts(triggerConditions,"triggerConditions"); inputs=StructuredSkillContract.texts(inputs,"inputs"); businessRules=StructuredSkillContract.texts(businessRules,"businessRules"); outputs=StructuredSkillContract.texts(outputs,"outputs"); permissions=StructuredSkillContract.texts(permissions,"permissions"); stateChanges=StructuredSkillContract.texts(stateChanges,"stateChanges"); exceptionHandling=StructuredSkillContract.texts(exceptionHandling,"exceptionHandling"); externalDependencies=StructuredSkillContract.texts(externalDependencies,"externalDependencies"); evidenceKeys=StructuredSkillContract.keyReferences(evidenceKeys,"evidenceKeys"); }
    }
    public enum HandlingLevel { BLOCKING, CONTINUE_INCOMPLETE, IMPROVEMENT; @JsonValue public String wireValue(){return name().toLowerCase(java.util.Locale.ROOT);} @JsonCreator public static HandlingLevel fromWire(String value){return valueOf(value.toUpperCase(java.util.Locale.ROOT));} }
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ReviewFinding(@JsonProperty("finding_key") String findingKey, @JsonProperty("issue_type") String issueType,
            String description, @JsonProperty("evidence_keys") List<String> evidenceKeys,
            @JsonProperty("test_design_impact") String testDesignImpact,
            @JsonProperty("current_project_recommendation") String currentProjectRecommendation,
            @JsonProperty("design_center_guideline_recommendation") String designCenterGuidelineRecommendation,
            @JsonProperty("handling_level") HandlingLevel handlingLevel) {
        public ReviewFinding { findingKey=StructuredSkillContract.key(findingKey,"findingKey"); issueType=StructuredSkillContract.text(issueType,"issueType"); description=StructuredSkillContract.text(description,"description"); evidenceKeys=StructuredSkillContract.keyReferences(evidenceKeys,"evidenceKeys"); testDesignImpact=StructuredSkillContract.text(testDesignImpact,"testDesignImpact"); currentProjectRecommendation=StructuredSkillContract.text(currentProjectRecommendation,"currentProjectRecommendation"); designCenterGuidelineRecommendation=StructuredSkillContract.text(designCenterGuidelineRecommendation,"designCenterGuidelineRecommendation"); if (handlingLevel==null) throw new IllegalArgumentException("handlingLevel must not be null"); }
    }
}
