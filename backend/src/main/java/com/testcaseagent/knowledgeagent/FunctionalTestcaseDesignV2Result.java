package com.testcaseagent.knowledgeagent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;
import java.util.Locale;

/** KEE V2 testcase candidates before Java assigns case identities and validates business grounding. [Req-ID]: REQ-TGV2-005 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record FunctionalTestcaseDesignV2Result(
        @JsonProperty("function_key") String functionKey,
        @JsonProperty("test_point_key") String testPointKey,
        @JsonProperty("generation_outcome") GenerationOutcome generationOutcome,
        @JsonProperty("missing_information") List<String> missingInformation,
        List<Testcase> testcases) {
    public FunctionalTestcaseDesignV2Result {
        functionKey = StructuredSkillContract.key(functionKey, "functionKey");
        testPointKey = StructuredSkillContract.key(testPointKey, "testPointKey");
        if (generationOutcome == null) throw new IllegalArgumentException("generationOutcome must not be null");
        missingInformation = StructuredSkillContract.texts(missingInformation, "missingInformation");
        testcases = StructuredSkillContract.list(testcases, "testcases", 0, 50);
    }

    public enum GenerationOutcome {
        GENERATED, PENDING_ONLY, UNABLE_TO_GENERATE;
        @JsonValue public String wireValue() { return name().toLowerCase(Locale.ROOT); }
        @JsonCreator public static GenerationOutcome fromWire(String value) {
            return valueOf(value.toUpperCase(Locale.ROOT));
        }
    }
    public enum CaseStatus { FORMAL, PENDING_CONFIRMATION;
        @JsonValue public String wireValue() { return name().toLowerCase(Locale.ROOT); }
        @JsonCreator public static CaseStatus fromWire(String value) { return valueOf(value.toUpperCase(Locale.ROOT)); }
    }
    public enum Priority { HIGH, MEDIUM, LOW;
        @JsonValue public String wireValue() { return name().toLowerCase(Locale.ROOT); }
        @JsonCreator public static Priority fromWire(String value) { return valueOf(value.toUpperCase(Locale.ROOT)); }
    }
    public enum InputNature { VALID, INVALID, BOUNDARY, OTHER, UNSPECIFIED;
        @JsonValue public String wireValue() { return name().toLowerCase(Locale.ROOT); }
        @JsonCreator public static InputNature fromWire(String value) { return valueOf(value.toUpperCase(Locale.ROOT)); }
    }
    public enum InputSource { MANUAL, PROGRAM, FILE, SIMULATION, OTHER, UNSPECIFIED;
        @JsonValue public String wireValue() { return name().toLowerCase(Locale.ROOT); }
        @JsonCreator public static InputSource fromWire(String value) { return valueOf(value.toUpperCase(Locale.ROOT)); }
    }
    public enum TestMethod { EQUIVALENCE_PARTITIONING, BOUNDARY_VALUE_ANALYSIS, ERROR_GUESSING, CAUSE_EFFECT_GRAPH, OTHER, UNSPECIFIED;
        @JsonValue public String wireValue() { return name().toLowerCase(Locale.ROOT); }
        @JsonCreator public static TestMethod fromWire(String value) { return valueOf(value.toUpperCase(Locale.ROOT)); }
    }
    public enum Authenticity { REAL, SIMULATED, UNSPECIFIED;
        @JsonValue public String wireValue() { return name().toLowerCase(Locale.ROOT); }
        @JsonCreator public static Authenticity fromWire(String value) { return valueOf(value.toUpperCase(Locale.ROOT)); }
    }

    /** One unpersisted case candidate; the result contract deliberately contains no case key or author/date. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Testcase(
            String name,
            String title,
            Priority priority,
            List<String> preconditions,
            Initialization initialization,
            List<Input> inputs,
            List<Step> steps,
            @JsonProperty("expected_results") List<String> expectedResults,
            @JsonProperty("evaluation_criteria") String evaluationCriteria,
            @JsonProperty("result_evaluation_criteria") String resultEvaluationCriteria,
            @JsonProperty("termination_conditions") List<String> terminationConditions,
            @JsonProperty("result_collection") String resultCollection,
            @JsonProperty("requirement_fact_keys") List<String> requirementFactKeys,
            @JsonProperty("evidence_keys") List<String> evidenceKeys,
            @JsonProperty("case_status") CaseStatus caseStatus,
            @JsonProperty("missing_information") List<String> missingInformation) {
        public Testcase {
            name = StructuredSkillContract.text(name, "name");
            title = StructuredSkillContract.text(title, "title");
            if (priority == null || initialization == null || caseStatus == null) {
                throw new IllegalArgumentException("testcase priority, initialization and caseStatus must not be null");
            }
            preconditions = StructuredSkillContract.texts(preconditions, "preconditions");
            inputs = StructuredSkillContract.list(inputs, "inputs", 0, 100);
            steps = StructuredSkillContract.list(steps, "steps", 1, 50);
            for (int index = 0; index < steps.size(); index++) {
                if (steps.get(index).stepNo() != index + 1) {
                    throw new IllegalArgumentException("step numbers must be continuous from one");
                }
            }
            expectedResults = StructuredSkillContract.list(expectedResults, "expectedResults", 1, 100);
            expectedResults.forEach(value -> StructuredSkillContract.text(value, "expectedResult"));
            evaluationCriteria = StructuredSkillContract.text(evaluationCriteria, "evaluationCriteria");
            resultEvaluationCriteria = StructuredSkillContract.text(resultEvaluationCriteria, "resultEvaluationCriteria");
            terminationConditions = StructuredSkillContract.texts(terminationConditions, "terminationConditions");
            resultCollection = StructuredSkillContract.text(resultCollection, "resultCollection");
            requirementFactKeys = StructuredSkillContract.keyReferences(requirementFactKeys, "requirementFactKeys");
            evidenceKeys = StructuredSkillContract.keyReferences(evidenceKeys, "evidenceKeys");
            missingInformation = StructuredSkillContract.texts(missingInformation, "case.missingInformation");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Initialization(
            @JsonProperty("hardware_configuration") List<String> hardwareConfiguration,
            @JsonProperty("software_configuration") List<String> softwareConfiguration,
            @JsonProperty("test_configuration") List<String> testConfiguration,
            @JsonProperty("parameter_configuration") List<String> parameterConfiguration) {
        public Initialization {
            hardwareConfiguration = StructuredSkillContract.texts(hardwareConfiguration, "hardwareConfiguration");
            softwareConfiguration = StructuredSkillContract.texts(softwareConfiguration, "softwareConfiguration");
            testConfiguration = StructuredSkillContract.texts(testConfiguration, "testConfiguration");
            parameterConfiguration = StructuredSkillContract.texts(parameterConfiguration, "parameterConfiguration");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Input(
            String content,
            InputNature nature,
            InputSource source,
            TestMethod method,
            Authenticity authenticity,
            String sequence) {
        public Input {
            content = StructuredSkillContract.text(content, "input.content");
            if (nature == null || source == null || method == null || authenticity == null) {
                throw new IllegalArgumentException("input enum fields must not be null");
            }
            sequence = StructuredSkillContract.optionalText(sequence, "input.sequence");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Step(
            @JsonProperty("step_no") int stepNo,
            String action,
            String expected,
            @JsonProperty("evaluation_criteria") String evaluationCriteria,
            @JsonProperty("termination_or_error") String terminationOrError,
            @JsonProperty("result_collection") String resultCollection) {
        public Step {
            if (stepNo < 1) throw new IllegalArgumentException("stepNo must be positive");
            action = StructuredSkillContract.text(action, "step.action");
            expected = StructuredSkillContract.text(expected, "step.expected");
            evaluationCriteria = StructuredSkillContract.text(evaluationCriteria, "step.evaluationCriteria");
            terminationOrError = StructuredSkillContract.optionalText(terminationOrError, "step.terminationOrError");
            resultCollection = StructuredSkillContract.text(resultCollection, "step.resultCollection");
        }
    }
}
