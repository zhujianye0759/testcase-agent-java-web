package com.testcaseagent.knowledgeagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.HashSet;
import java.util.List;

/** Exact input for one test-point testcase-design call. [Req-ID]: REQ-SKI-003, REQ-FTG-004 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record FunctionalTestcaseDesignInput(@JsonProperty("function_key") String functionKey,
        @JsonProperty("function_name") String functionName, @JsonProperty("test_point") TestPoint testPoint,
        @JsonProperty("formal_supports") List<FormalSupport> formalSupports) {
    public FunctionalTestcaseDesignInput {
        functionKey = StructuredSkillContract.key(functionKey, "functionKey");
        functionName = StructuredSkillContract.text(functionName, "functionName");
        if (testPoint == null) throw new IllegalArgumentException("testPoint must not be null");
        formalSupports = StructuredSkillContract.list(formalSupports, "formalSupports", 0,
                StructuredSkillContract.MAX_KEY_REFERENCES);
        List<String> factKeys = formalSupports.stream().map(FormalSupport::factKey).toList();
        if (new HashSet<>(factKeys).size() != factKeys.size()) {
            throw new IllegalArgumentException("formalSupports fact keys must be unique");
        }
    }

    /** Planner-only shape; a formal invocation must attach persisted supports before it crosses the port. */
    public FunctionalTestcaseDesignInput(String functionKey, String functionName, TestPoint testPoint) {
        this(functionKey, functionName, testPoint, List.of());
    }

    void requireExecutable() {
        if (testPoint.basis() == Basis.GENERAL_EXPERIENCE) {
            if (!testPoint.requirementFactKeys().isEmpty() || !formalSupports.isEmpty()) {
                throw new IllegalArgumentException("General-experience input must not carry formal supports");
            }
            return;
        }
        if (testPoint.requirementFactKeys().isEmpty() || testPoint.evidenceKeys().isEmpty()
                || formalSupports.isEmpty()) {
            throw new IllegalArgumentException("Formal testcase input requires fact, evidence, and support text");
        }
        if (!formalSupports.stream().map(FormalSupport::factKey).toList()
                .equals(testPoint.requirementFactKeys())) {
            throw new IllegalArgumentException("Formal supports must exactly match the test-point fact order");
        }
    }
    public enum TestPointType { NORMAL_BEHAVIOR, INPUT_VALIDATION, BOUNDARY_VALUE, PERMISSION, STATE_TRANSITION, BUSINESS_EXCEPTION, DEPENDENCY_FAILURE; @JsonValue public String wireValue(){return name().toLowerCase(java.util.Locale.ROOT);} @JsonCreator public static TestPointType fromWire(String value){return valueOf(value.toUpperCase(java.util.Locale.ROOT));} }
    public enum Basis { FORMAL_REQUIREMENT, GENERAL_EXPERIENCE; @JsonValue public String wireValue(){return name().toLowerCase(java.util.Locale.ROOT);} @JsonCreator public static Basis fromWire(String value){return valueOf(value.toUpperCase(java.util.Locale.ROOT));} }
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record TestPoint(@JsonProperty("test_point_key") String testPointKey, @JsonProperty("type") TestPointType type,
            String description, @JsonProperty("requirement_fact_keys") List<String> requirementFactKeys,
            @JsonProperty("evidence_keys") List<String> evidenceKeys, Basis basis,
            @JsonProperty("missing_information") List<String> missingInformation) {
        public TestPoint { testPointKey = StructuredSkillContract.key(testPointKey, "testPointKey"); if (type == null || basis == null) throw new IllegalArgumentException("type and basis must not be null"); description = StructuredSkillContract.text(description, "description"); requirementFactKeys = StructuredSkillContract.keyReferences(requirementFactKeys, "requirementFactKeys"); evidenceKeys = StructuredSkillContract.keyReferences(evidenceKeys, "evidenceKeys"); missingInformation = StructuredSkillContract.texts(missingInformation, "missingInformation"); }
    }
}
