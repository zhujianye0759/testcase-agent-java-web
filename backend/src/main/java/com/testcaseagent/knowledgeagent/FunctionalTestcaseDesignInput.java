package com.testcaseagent.knowledgeagent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Exact input for one frozen functional-testcase-design call. [Req-ID]: REQ-SKI-003, REQ-FTG-004, REQ-FTG-006 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FunctionalTestcaseDesignInput(@JsonProperty("function_key") String functionKey,
        @JsonProperty("function_name") String functionName,
        @JsonProperty("authoring_information") AuthoringInformation authoringInformation,
        @JsonProperty("test_point") TestPoint testPoint,
        @JsonProperty("formal_supports") List<FormalSupport> formalSupports) {
    public FunctionalTestcaseDesignInput {
        functionKey = StructuredSkillContract.key(functionKey, "functionKey");
        functionName = StructuredSkillContract.text(functionName, "functionName");
        if (testPoint == null) throw new IllegalArgumentException("testPoint must not be null");
        formalSupports = StructuredSkillContract.list(formalSupports, "formalSupports", 0,
                StructuredSkillContract.MAX_KEY_REFERENCES);
        List<String> factKeys = formalSupports.stream().map(FormalSupport::factKey).toList();
        if (new LinkedHashSet<>(factKeys).size() != factKeys.size()) {
            throw new IllegalArgumentException("formalSupports fact keys must be unique");
        }
    }

    /** Planner-only compatibility shape; callers may omit authoring information. */
    public FunctionalTestcaseDesignInput(String functionKey, String functionName, TestPoint testPoint,
            List<FormalSupport> formalSupports) {
        this(functionKey, functionName, null, testPoint, formalSupports);
    }

    /** Planner-only compatibility shape; a formal invocation attaches persisted supports before crossing the port. */
    public FunctionalTestcaseDesignInput(String functionKey, String functionName, TestPoint testPoint) {
        this(functionKey, functionName, null, testPoint, List.of());
    }

    void requireExecutable() {
        if (testPoint.basis() == Basis.GENERAL_EXPERIENCE) {
            if (!testPoint.requirementFactKeys().isEmpty() || !formalSupports.isEmpty()) {
                throw new IllegalArgumentException("General-experience input must not carry formal supports");
            }
            return;
        }
        if (testPoint.requirementFactKeys().isEmpty() || testPoint.evidenceKeys().isEmpty() || formalSupports.isEmpty()) {
            throw new IllegalArgumentException("Formal testcase input requires fact, evidence, and support text");
        }
        if (!formalSupports.stream().map(FormalSupport::factKey).toList().equals(testPoint.requirementFactKeys())) {
            throw new IllegalArgumentException("Formal supports must exactly match the test-point fact order");
        }
        LinkedHashSet<String> supportEvidence = new LinkedHashSet<>();
        for (FormalSupport support : formalSupports) {
            if (!testPoint.evidenceKeys().containsAll(support.evidenceKeys())) {
                throw new IllegalArgumentException("Formal support evidence is outside the test-point closure");
            }
            supportEvidence.addAll(support.evidenceKeys());
        }
        if (!supportEvidence.equals(new LinkedHashSet<>(testPoint.evidenceKeys()))) {
            throw new IllegalArgumentException("Formal support evidence must exactly close the test-point closure");
        }
    }

    /** Optional author/date supplied by the caller and echoed verbatim in every returned case. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record AuthoringInformation(String author, String date) {
        public AuthoringInformation {
            author = StructuredSkillContract.optionalText(author, "authoringInformation.author");
            date = StructuredSkillContract.optionalText(date, "authoringInformation.date");
        }
    }

    public enum TestPointType { NORMAL_BEHAVIOR, INPUT_VALIDATION, BOUNDARY_VALUE, PERMISSION, STATE_TRANSITION, BUSINESS_EXCEPTION, DEPENDENCY_FAILURE;
        @JsonValue public String wireValue() { return name().toLowerCase(Locale.ROOT); }
        @JsonCreator public static TestPointType fromWire(String value) { return valueOf(value.toUpperCase(Locale.ROOT)); }
    }
    public enum Basis { FORMAL_REQUIREMENT, GENERAL_EXPERIENCE;
        @JsonValue public String wireValue() { return name().toLowerCase(Locale.ROOT); }
        @JsonCreator public static Basis fromWire(String value) { return valueOf(value.toUpperCase(Locale.ROOT)); }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record TestPoint(@JsonProperty("test_point_key") String testPointKey, @JsonProperty("type") TestPointType type,
            String description, @JsonProperty("requirement_fact_keys") List<String> requirementFactKeys,
            @JsonProperty("evidence_keys") List<String> evidenceKeys, Basis basis,
            @JsonProperty("missing_information") List<String> missingInformation) {
        public TestPoint {
            testPointKey = StructuredSkillContract.key(testPointKey, "testPointKey");
            if (type == null || basis == null) throw new IllegalArgumentException("type and basis must not be null");
            description = StructuredSkillContract.text(description, "description");
            requirementFactKeys = StructuredSkillContract.keyReferences(requirementFactKeys, "requirementFactKeys");
            evidenceKeys = StructuredSkillContract.keyReferences(evidenceKeys, "evidenceKeys");
            missingInformation = StructuredSkillContract.texts(missingInformation, "missingInformation");
        }
    }
}
