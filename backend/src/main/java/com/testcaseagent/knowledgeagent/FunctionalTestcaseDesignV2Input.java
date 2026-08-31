package com.testcaseagent.knowledgeagent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** Exact V2 input for one audited function and one Java-owned test point. [Req-ID]: REQ-TGV2-005 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record FunctionalTestcaseDesignV2Input(
        @JsonProperty("function_key") String functionKey,
        @JsonProperty("function_name") String functionName,
        @JsonProperty("function_path") String functionPath,
        @JsonProperty("function_description") String functionDescription,
        @JsonProperty("test_point") TestPoint testPoint,
        @JsonProperty("requirement_facts") List<RequirementFact> requirementFacts) {
    public FunctionalTestcaseDesignV2Input {
        functionKey = StructuredSkillContract.key(functionKey, "functionKey");
        functionName = StructuredSkillContract.text(functionName, "functionName");
        functionPath = StructuredSkillContract.text(functionPath, "functionPath");
        functionDescription = StructuredSkillContract.optionalText(functionDescription, "functionDescription");
        if (testPoint == null) throw new IllegalArgumentException("testPoint must not be null");
        requirementFacts = StructuredSkillContract.list(requirementFacts, "requirementFacts", 0, 100);
        List<String> factKeys = requirementFacts.stream().map(RequirementFact::factKey).toList();
        if (new HashSet<>(factKeys).size() != factKeys.size()) {
            throw new IllegalArgumentException("requirementFacts fact keys must be unique");
        }
    }

    public enum TestPointType {
        NORMAL_BEHAVIOR, INPUT_VALIDATION, BOUNDARY_VALUE, PERMISSION, STATE_TRANSITION,
        BUSINESS_EXCEPTION, DEPENDENCY_FAILURE;
        @JsonValue public String wireValue() { return name().toLowerCase(Locale.ROOT); }
        @JsonCreator public static TestPointType fromWire(String value) {
            return valueOf(value.toUpperCase(Locale.ROOT));
        }
    }

    public enum Basis {
        FORMAL_REQUIREMENT, GENERAL_EXPERIENCE;
        @JsonValue public String wireValue() { return name().toLowerCase(Locale.ROOT); }
        @JsonCreator public static Basis fromWire(String value) {
            return valueOf(value.toUpperCase(Locale.ROOT));
        }
    }

    /** One Java-owned test point; requirement facts are carried once in the sibling input array. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record TestPoint(
            @JsonProperty("test_point_key") String testPointKey,
            TestPointType type,
            Basis basis,
            String description,
            @JsonProperty("missing_information") List<String> missingInformation) {
        public TestPoint {
            testPointKey = StructuredSkillContract.key(testPointKey, "testPointKey");
            if (type == null || basis == null) throw new IllegalArgumentException("test point enums must not be null");
            description = StructuredSkillContract.text(description, "testPoint.description");
            missingInformation = StructuredSkillContract.texts(missingInformation, "testPoint.missingInformation");
        }
    }

    /** One Java-validated fact supplied to testcase design with its exact source quotations. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RequirementFact(
            @JsonProperty("fact_key") String factKey,
            @JsonProperty("fact_type") RequirementFactExtractionV2Result.FactType factType,
            String statement,
            @JsonProperty("source_quotes") List<StructuredSourceQuoteV2> sourceQuotes) {
        public RequirementFact {
            factKey = StructuredSkillContract.key(factKey, "factKey");
            if (factType == null) throw new IllegalArgumentException("factType must not be null");
            statement = StructuredSkillContract.text(statement, "statement");
            sourceQuotes = StructuredSkillContract.list(sourceQuotes, "sourceQuotes", 1, 100);
        }
    }
}
