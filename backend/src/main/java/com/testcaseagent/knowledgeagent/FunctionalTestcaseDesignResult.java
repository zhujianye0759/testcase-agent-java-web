package com.testcaseagent.knowledgeagent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;
import java.util.Locale;

/** Typed, high-granularity result of one function test-point testcase-design call. [Req-ID]: REQ-SKI-004, REQ-FTG-006 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record FunctionalTestcaseDesignResult(@JsonProperty("function_key") String functionKey,
        @JsonProperty("test_point_key") String testPointKey, List<Testcase> testcases) {
    public FunctionalTestcaseDesignResult {
        functionKey = StructuredSkillContract.key(functionKey, "functionKey");
        testPointKey = StructuredSkillContract.key(testPointKey, "testPointKey");
        testcases = StructuredSkillContract.list(testcases, "testcases", 1, 50);
        StructuredSkillContract.uniqueKeys(testcases.stream().map(Testcase::caseKey).toList(), "testcase");
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

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Testcase(@JsonProperty("case_key") String caseKey, String name, String title, Priority priority,
            List<String> preconditions, Initialization initialization, List<Input> inputs, List<Step> steps,
            @JsonProperty("expected_results") List<String> expectedResults,
            @JsonProperty("evaluation_criteria") String evaluationCriteria,
            @JsonProperty("result_evaluation_criteria") String resultEvaluationCriteria,
            @JsonProperty("termination_conditions") List<String> terminationConditions,
            @JsonProperty("result_collection") String resultCollection,
            @JsonProperty("authoring_information") AuthoringInformation authoringInformation,
            @JsonProperty("requirement_fact_keys") List<String> requirementFactKeys,
            @JsonProperty("evidence_keys") List<String> evidenceKeys,
            @JsonProperty("case_status") CaseStatus caseStatus,
            @JsonProperty("missing_information") List<String> missingInformation) {
        public Testcase {
            caseKey = StructuredSkillContract.key(caseKey, "caseKey");
            name = StructuredSkillContract.text(name, "name");
            title = StructuredSkillContract.text(title, "title");
            if (priority == null || initialization == null || authoringInformation == null || caseStatus == null) {
                throw new IllegalArgumentException("testcase priority, initialization, authoringInformation and caseStatus must not be null");
            }
            preconditions = StructuredSkillContract.texts(preconditions, "preconditions");
            inputs = StructuredSkillContract.list(inputs, "inputs", 0, StructuredSkillContract.MAX_KEY_REFERENCES);
            steps = StructuredSkillContract.list(steps, "steps", 1, 50);
            for (int index = 0; index < steps.size(); index++) {
                if (steps.get(index).stepNo() != index + 1) throw new IllegalArgumentException("step numbers must be continuous");
            }
            expectedResults = StructuredSkillContract.texts(expectedResults, "expectedResults");
            evaluationCriteria = StructuredSkillContract.text(evaluationCriteria, "evaluationCriteria");
            resultEvaluationCriteria = StructuredSkillContract.text(resultEvaluationCriteria, "resultEvaluationCriteria");
            terminationConditions = StructuredSkillContract.texts(terminationConditions, "terminationConditions");
            resultCollection = StructuredSkillContract.text(resultCollection, "resultCollection");
            requirementFactKeys = StructuredSkillContract.keyReferences(requirementFactKeys, "requirementFactKeys");
            evidenceKeys = StructuredSkillContract.keyReferences(evidenceKeys, "evidenceKeys");
            missingInformation = StructuredSkillContract.texts(missingInformation, "missingInformation");
        }

        /** Compatibility constructor for the former low-granularity Java-only result shape. */
        public Testcase(String caseKey, String title, List<String> preconditions, List<Step> steps,
                List<String> requirementFactKeys, List<String> evidenceKeys, CaseStatus caseStatus,
                List<String> missingInformation) {
            this(caseKey, title, title, Priority.MEDIUM, preconditions, Initialization.empty(), List.of(), steps,
                    steps.stream().map(Step::expected).toList(), GenericClauses.EVALUATION,
                    GenericClauses.RESULT_EVALUATION, List.of(), GenericClauses.RESULT_COLLECTION,
                    AuthoringInformation.empty(), requirementFactKeys, evidenceKeys, caseStatus, missingInformation);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Initialization(@JsonProperty("hardware_configuration") List<String> hardwareConfiguration,
            @JsonProperty("software_configuration") List<String> softwareConfiguration,
            @JsonProperty("test_configuration") List<String> testConfiguration,
            @JsonProperty("parameter_configuration") List<String> parameterConfiguration) {
        public Initialization {
            hardwareConfiguration = StructuredSkillContract.texts(hardwareConfiguration, "hardwareConfiguration");
            softwareConfiguration = StructuredSkillContract.texts(softwareConfiguration, "softwareConfiguration");
            testConfiguration = StructuredSkillContract.texts(testConfiguration, "testConfiguration");
            parameterConfiguration = StructuredSkillContract.texts(parameterConfiguration, "parameterConfiguration");
        }
        public static Initialization empty() { return new Initialization(List.of(), List.of(), List.of(), List.of()); }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Input(String content, InputNature nature, InputSource source, TestMethod method,
            Authenticity authenticity, String sequence) {
        public Input {
            content = StructuredSkillContract.text(content, "input.content");
            if (nature == null || source == null || method == null || authenticity == null) {
                throw new IllegalArgumentException("input enum fields must not be null");
            }
            sequence = StructuredSkillContract.optionalText(sequence, "input.sequence");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Step(@JsonProperty("step_no") int stepNo, String action, String expected,
            @JsonProperty("evaluation_criteria") String evaluationCriteria,
            @JsonProperty("termination_or_error") String terminationOrError,
            @JsonProperty("result_collection") String resultCollection) {
        public Step {
            if (stepNo < 1) throw new IllegalArgumentException("stepNo must be at least one");
            action = StructuredSkillContract.text(action, "action");
            expected = StructuredSkillContract.text(expected, "expected");
            evaluationCriteria = StructuredSkillContract.text(evaluationCriteria, "step.evaluationCriteria");
            terminationOrError = StructuredSkillContract.text(terminationOrError, "step.terminationOrError");
            resultCollection = StructuredSkillContract.text(resultCollection, "step.resultCollection");
        }
        /** Compatibility constructor for the former low-granularity Java-only result shape. */
        public Step(int stepNo, String action, String expected) {
            this(stepNo, action, expected, GenericClauses.STEP_EVALUATION,
                    GenericClauses.TERMINATION_OR_ERROR, GenericClauses.RESULT_COLLECTION);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record AuthoringInformation(String author, String date) {
        public AuthoringInformation {
            author = StructuredSkillContract.optionalText(author, "authoringInformation.author");
            date = StructuredSkillContract.optionalText(date, "authoringInformation.date");
        }
        public static AuthoringInformation empty() { return new AuthoringInformation("", ""); }
    }

    /** Server-owned generic clauses are the sole grounding bypasses in the frozen result contract. */
    public static final class GenericClauses {
        public static final String STEP_EVALUATION = "实际结果满足本步骤预期结果。";
        public static final String TERMINATION_OR_ERROR = "系统服务终止，或执行过程中无法执行下一步操作。";
        public static final String RESULT_COLLECTION = "记录实际结果、提示信息及必要证据。";
        public static final String EVALUATION = "满足前提和约束且未触发终止条件，逐步执行并记录结果。";
        public static final String RESULT_EVALUATION = "全部预期结果满足则通过，任一不满足则不通过。";
        private GenericClauses() { }
    }
}
