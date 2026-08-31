package com.testcaseagent.knowledgeagent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** Public KEE V2 fact and local-testability result without Java-owned stable keys. [Req-ID]: REQ-TGV2-004 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record RequirementFactExtractionV2Result(
        @JsonProperty("function_key") String functionKey,
        @JsonProperty("window_key") String windowKey,
        @JsonProperty("requirement_facts") List<RequirementFact> requirementFacts,
        @JsonProperty("testability_observations") List<TestabilityObservation> testabilityObservations) {

    public RequirementFactExtractionV2Result {
        functionKey = StructuredSkillContract.key(functionKey, "functionKey");
        windowKey = StructuredSkillContract.key(windowKey, "windowKey");
        requirementFacts = StructuredSkillContract.list(requirementFacts, "requirementFacts", 0, 100);
        testabilityObservations = StructuredSkillContract.list(
                testabilityObservations, "testabilityObservations", 0, 50);
    }

    public enum FactType {
        ROLE, TRIGGER_CONDITION, INPUT, BUSINESS_RULE, OUTPUT, PERMISSION, STATE_CHANGE,
        EXCEPTION_HANDLING, EXTERNAL_DEPENDENCY;
        @JsonValue public String wireValue() { return name().toLowerCase(Locale.ROOT); }
        @JsonCreator public static FactType fromWire(String value) {
            return valueOf(value.toUpperCase(Locale.ROOT));
        }
    }

    public enum ObservationType {
        AMBIGUOUS, CONTRADICTORY, UNQUANTIFIED, UNOBSERVABLE_RESULT, PLACEHOLDER_OR_TODO;
        @JsonValue public String wireValue() { return name().toLowerCase(Locale.ROOT); }
        @JsonCreator public static ObservationType fromWire(String value) {
            return valueOf(value.toUpperCase(Locale.ROOT));
        }
    }

    /** One atomic fact candidate; Java later assigns its stable fact key. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RequirementFact(
            @JsonProperty("fact_type") FactType factType,
            String statement,
            @JsonProperty("source_quotes") List<StructuredSourceQuoteV2> sourceQuotes) {
        public RequirementFact {
            if (factType == null) throw new IllegalArgumentException("factType must not be null");
            statement = StructuredSkillContract.text(statement, "statement");
            sourceQuotes = StructuredSkillContract.list(sourceQuotes, "sourceQuotes", 1, 100);
        }
    }

    /** Non-blocking local observation that can be forwarded to the design feedback center. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record TestabilityObservation(
            @JsonProperty("observation_type") ObservationType observationType,
            String description,
            @JsonProperty("affected_fact_types") List<FactType> affectedFactTypes,
            @JsonProperty("source_quotes") List<StructuredSourceQuoteV2> sourceQuotes) {
        public TestabilityObservation {
            if (observationType == null) throw new IllegalArgumentException("observationType must not be null");
            description = StructuredSkillContract.text(description, "description");
            affectedFactTypes = StructuredSkillContract.list(affectedFactTypes, "affectedFactTypes", 0, 100);
            if (new HashSet<>(affectedFactTypes).size() != affectedFactTypes.size()) {
                throw new IllegalArgumentException("affectedFactTypes must be unique");
            }
            sourceQuotes = StructuredSkillContract.list(sourceQuotes, "sourceQuotes", 1, 100);
        }
    }
}
