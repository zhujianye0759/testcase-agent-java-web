package com.testcaseagent.validation;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Validates the complete frozen testcase result before any atomic acceptance. [Req-ID]: REQ-FTG-001, REQ-FTG-002, REQ-FTG-006 */
public final class FunctionalTestcaseResultValidator {
    public static final String STEP_EVALUATION = "实际结果满足本步骤预期结果。";
    public static final String TERMINATION_OR_ERROR = "系统服务终止，或执行过程中无法执行下一步操作。";
    public static final String RESULT_COLLECTION = "记录实际结果、提示信息及必要证据。";
    public static final String EVALUATION = "满足前提和约束且未触发终止条件，逐步执行并记录结果。";
    public static final String RESULT_EVALUATION = "全部预期结果满足则通过，任一不满足则不通过。";

    /** Validates echo identities, every frozen reader field, and formal-coverage separation. */
    public ValidationOutcome validate(WorkItem workItem, Result result) {
        WorkItem item = Objects.requireNonNull(workItem, "workItem must not be null");
        Result checked = Objects.requireNonNull(result, "result must not be null");
        if (!item.functionKey().equals(checked.functionKey()) || !item.testPointKey().equals(checked.testPointKey())) {
            throw new IllegalArgumentException("Result functionKey and testPointKey must exactly echo the work item");
        }
        List<Testcase> cases = requiredList(checked.testcases(), "testcases");
        if (cases.isEmpty() || cases.size() > 50) throw new IllegalArgumentException("testcases must contain 1..50 rows");
        Set<String> caseKeys = new HashSet<>();
        boolean hasFormalCoverage = false;
        for (Testcase row : cases) {
            row = Objects.requireNonNull(row, "testcase must not be null");
            if (!caseKeys.add(required(row.caseKey(), "caseKey"))) throw new IllegalArgumentException("caseKey must be unique");
            validateShape(item, row);
            List<String> factKeys = requiredList(row.requirementFactKeys(), "case requirementFactKeys");
            List<String> evidenceKeys = requiredList(row.evidenceKeys(), "case evidenceKeys");
            validateReferences(item, factKeys, evidenceKeys);
            List<String> missingInformation = requiredList(row.missingInformation(), "case missingInformation");
            if (row.caseStatus() == CaseStatus.PENDING_CONFIRMATION) {
                requireNonblankItems(missingInformation, "case missingInformation");
            } else if (!missingInformation.isEmpty()) {
                throw new IllegalArgumentException("A formal testcase must not contain missingInformation");
            }
            ReaderFacingTextPolicy.requireSafeItems(missingInformation, "case missingInformation");
            if (item.basis() == Basis.GENERAL_EXPERIENCE) {
                if (row.caseStatus() != CaseStatus.PENDING_CONFIRMATION) {
                    throw new IllegalArgumentException("General-experience test points can only produce pending-confirmation cases");
                }
            } else if (row.caseStatus() == CaseStatus.FORMAL) {
                validateFormalGrounding(item, row, factKeys, evidenceKeys);
                hasFormalCoverage = true;
            }
        }
        if (item.basis() == Basis.FORMAL_REQUIREMENT && !hasFormalCoverage) {
            throw new IllegalArgumentException("A formal requirement test point requires at least one formal testcase");
        }
        return new ValidationOutcome(checked, item.basis() == Basis.FORMAL_REQUIREMENT && hasFormalCoverage);
    }

    private static void validateShape(WorkItem item, Testcase row) {
        ReaderFacingTextPolicy.requireSafe(row.name(), "case name");
        ReaderFacingTextPolicy.requireSafe(row.title(), "case title");
        if (row.priority() == null || row.caseStatus() == null) throw new IllegalArgumentException("priority and caseStatus must not be null");
        ReaderFacingTextPolicy.requireSafeItems(requiredList(row.preconditions(), "preconditions"), "precondition");
        validateInitialization(row.initialization());
        for (Input input : requiredList(row.inputs(), "inputs")) validateInput(input);
        validateSteps(requiredList(row.steps(), "steps"));
        ReaderFacingTextPolicy.requireSafeItems(requiredList(row.expectedResults(), "expectedResults"), "expected result");
        ReaderFacingTextPolicy.requireSafe(row.evaluationCriteria(), "evaluationCriteria");
        ReaderFacingTextPolicy.requireSafe(row.resultEvaluationCriteria(), "resultEvaluationCriteria");
        ReaderFacingTextPolicy.requireSafeItems(requiredList(row.terminationConditions(), "terminationConditions"), "termination condition");
        ReaderFacingTextPolicy.requireSafe(row.resultCollection(), "resultCollection");
        if (!item.authoringInformation().equals(row.authoringInformation())) {
            throw new IllegalArgumentException("authoringInformation must exactly echo the request");
        }
    }

    private static void validateInitialization(Initialization initialization) {
        Initialization value = Objects.requireNonNull(initialization, "initialization must not be null");
        ReaderFacingTextPolicy.requireSafeItems(requiredList(value.hardwareConfiguration(), "hardwareConfiguration"), "hardware configuration");
        ReaderFacingTextPolicy.requireSafeItems(requiredList(value.softwareConfiguration(), "softwareConfiguration"), "software configuration");
        ReaderFacingTextPolicy.requireSafeItems(requiredList(value.testConfiguration(), "testConfiguration"), "test configuration");
        ReaderFacingTextPolicy.requireSafeItems(requiredList(value.parameterConfiguration(), "parameterConfiguration"), "parameter configuration");
    }

    private static void validateInput(Input input) {
        Input value = Objects.requireNonNull(input, "input must not be null");
        ReaderFacingTextPolicy.requireSafe(value.content(), "input content");
        if (value.nature() == null || value.source() == null || value.method() == null || value.authenticity() == null) {
            throw new IllegalArgumentException("input enum fields must not be null");
        }
        if (!value.sequence().isEmpty()) ReaderFacingTextPolicy.requireSafe(value.sequence(), "input sequence");
    }

    private static void validateFormalGrounding(WorkItem item, Testcase testcase, List<String> factKeys,
            List<String> evidenceKeys) {
        if (factKeys.isEmpty() || evidenceKeys.isEmpty()) {
            throw new IllegalArgumentException("A formal testcase requires nonempty requirement-fact and evidence closures");
        }
        List<FormalSupport> supports = item.formalSupports().stream().filter(support -> factKeys.contains(support.factKey())).toList();
        LinkedHashSet<String> expectedEvidence = new LinkedHashSet<>();
        supports.forEach(support -> expectedEvidence.addAll(support.evidenceTexts().keySet()));
        if (!expectedEvidence.equals(new LinkedHashSet<>(evidenceKeys))) {
            throw new IllegalArgumentException("Formal testcase evidence must exactly close its selected fact evidence");
        }
        requireDirectSupport(testcase.name(), "case name", functionSources(supports), evidenceKeys, supports);
        requireDirectSupport(testcase.title(), "case title", functionSources(supports), evidenceKeys, supports);
        List<String> setupSources = categorized(supports, false);
        for (String precondition : testcase.preconditions()) requireDirectSupport(precondition, "case precondition", setupSources, evidenceKeys, supports);
        Initialization initialization = testcase.initialization();
        for (String value : initialization.hardwareConfiguration()) requireDirectSupport(value, "hardware configuration", setupSources, evidenceKeys, supports);
        for (String value : initialization.softwareConfiguration()) requireDirectSupport(value, "software configuration", setupSources, evidenceKeys, supports);
        for (String value : initialization.testConfiguration()) requireDirectSupport(value, "test configuration", setupSources, evidenceKeys, supports);
        for (String value : initialization.parameterConfiguration()) requireDirectSupport(value, "parameter configuration", setupSources, evidenceKeys, supports);
        List<String> actionSources = categorized(supports, true);
        List<String> expectedSources = expectedSources(supports);
        for (Input input : testcase.inputs()) {
            requireDirectSupport(input.content(), "input content", actionSources, evidenceKeys, supports);
            if (!input.sequence().isEmpty()) requireDirectSupport(input.sequence(), "input sequence", actionSources, evidenceKeys, supports);
        }
        for (Step step : testcase.steps()) {
            requireDirectSupport(step.action(), "step action", actionSources, evidenceKeys, supports);
            requireDirectSupport(step.expected(), "step expected", expectedSources, evidenceKeys, supports);
            requireDirectSupportOrGeneric(step.evaluationCriteria(), "step evaluationCriteria", expectedSources,
                    evidenceKeys, supports, STEP_EVALUATION);
            requireDirectSupportOrGeneric(step.terminationOrError(), "step terminationOrError", expectedSources,
                    evidenceKeys, supports, TERMINATION_OR_ERROR);
            requireDirectSupportOrGeneric(step.resultCollection(), "step resultCollection", expectedSources,
                    evidenceKeys, supports, RESULT_COLLECTION);
        }
        for (String result : testcase.expectedResults()) requireDirectSupport(result, "expected result", expectedSources, evidenceKeys, supports);
        requireDirectSupportOrGeneric(testcase.evaluationCriteria(), "evaluationCriteria", expectedSources,
                evidenceKeys, supports, EVALUATION);
        requireDirectSupportOrGeneric(testcase.resultEvaluationCriteria(), "resultEvaluationCriteria", expectedSources,
                evidenceKeys, supports, RESULT_EVALUATION);
        for (String condition : testcase.terminationConditions()) {
            requireDirectSupportOrGeneric(condition, "termination condition", expectedSources, evidenceKeys,
                    supports, TERMINATION_OR_ERROR);
        }
        requireDirectSupportOrGeneric(testcase.resultCollection(), "resultCollection", expectedSources,
                evidenceKeys, supports, RESULT_COLLECTION);
    }

    private static List<String> functionSources(List<FormalSupport> supports) { return supports.stream().map(FormalSupport::function).toList(); }
    private static List<String> categorized(List<FormalSupport> supports, boolean action) {
        List<String> sources = new ArrayList<>();
        for (FormalSupport support : supports) {
            if (action) { sources.addAll(support.triggerConditions()); sources.addAll(support.inputs()); }
            else { sources.addAll(support.roles()); sources.addAll(support.triggerConditions()); sources.addAll(support.businessRules()); sources.addAll(support.permissions()); sources.addAll(support.stateChanges()); sources.addAll(support.externalDependencies()); }
        }
        return sources;
    }
    private static List<String> expectedSources(List<FormalSupport> supports) {
        List<String> sources = new ArrayList<>();
        for (FormalSupport support : supports) { sources.addAll(support.outputs()); sources.addAll(support.stateChanges()); sources.addAll(support.exceptionHandling()); sources.addAll(support.externalDependencies()); }
        return sources;
    }
    private static void requireDirectSupport(String value, String field, List<String> typedSources, List<String> evidenceKeys,
            List<FormalSupport> supports) {
        String claim = normalized(value);
        if (claim.isEmpty()) throw new IllegalArgumentException(field + " has no business content after normalization");
        if (typedSources.stream().map(FunctionalTestcaseResultValidator::normalized).anyMatch(claim::equals)) return;
        boolean supportedByEvidence = supports.stream().flatMap(support -> support.evidenceTexts().entrySet().stream())
                .filter(entry -> evidenceKeys.contains(entry.getKey())).map(Map.Entry::getValue)
                .map(FunctionalTestcaseResultValidator::normalized).anyMatch(claim::equals);
        if (!supportedByEvidence) throw new IllegalArgumentException(field + " is not exactly supported by its bound formal facts or evidence");
    }
    private static void requireDirectSupportOrGeneric(String value, String field, List<String> typedSources,
            List<String> evidenceKeys, List<FormalSupport> supports, String genericClause) {
        if (!genericClause.equals(value)) {
            requireDirectSupport(value, field, typedSources, evidenceKeys, supports);
        }
    }
    private static String normalized(String value) { return Normalizer.normalize(required(value, "grounding text"), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip(); }

    private static void validateReferences(WorkItem item, List<String> factKeys, List<String> evidenceKeys) {
        requireSubset(factKeys, item.requirementFactKeys(), StructuredKeyType.REQUIREMENT_FACT, item.registry(), "requirement fact");
        Set<String> distinctEvidence = new HashSet<>();
        for (String value : evidenceKeys) { String key = required(value, "evidenceKey"); if (!distinctEvidence.add(key) || !item.evidenceKeys().contains(key)) throw new IllegalArgumentException("Case evidence is duplicate or outside the test-point closure"); item.registry().requireEvidence(key); }
    }
    private static void validateSteps(List<Step> steps) {
        if (steps.isEmpty() || steps.size() > 50) throw new IllegalArgumentException("steps must contain 1..50 rows");
        for (int index = 0; index < steps.size(); index++) { Step step = Objects.requireNonNull(steps.get(index), "step must not be null"); if (step.stepNo() != index + 1) throw new IllegalArgumentException("stepNo must start at 1 and be consecutive"); ReaderFacingTextPolicy.requireSafe(step.action(), "step action"); ReaderFacingTextPolicy.requireSafe(step.expected(), "step expected"); ReaderFacingTextPolicy.requireSafe(step.evaluationCriteria(), "step evaluationCriteria"); ReaderFacingTextPolicy.requireSafe(step.resultCollection(), "step resultCollection"); ReaderFacingTextPolicy.requireSafe(step.terminationOrError(), "step terminationOrError"); }
    }
    private static void requireSubset(List<String> values, List<String> expected, StructuredKeyType type, StructuredValidationRegistry registry, String label) { Set<String> distinct = new HashSet<>(); for (String value : values) { String key = required(value, label + " key"); if (!distinct.add(key) || !expected.contains(key)) throw new IllegalArgumentException("Case references a duplicate or out-of-test-point " + label); registry.require(type, key); } }
    private static <T> List<T> requiredList(List<T> values, String field) { if (values == null) throw new IllegalArgumentException(field + " must not be null"); return List.copyOf(values); }
    private static void requireNonblankItems(List<String> values, String field) { if (values.isEmpty()) throw new IllegalArgumentException(field + " must not be empty"); for (String value : values) required(value, field); }
    private static String required(String value, String field) { if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank"); return value; }

    /** Immutable request-side closure for exactly one test point. */
    public record WorkItem(StructuredValidationRegistry registry, String functionKey, String functionName, String testPointKey, String description, TestPointType testPointType, Basis basis, List<String> requirementFactKeys, List<String> evidenceKeys, List<String> missingInformation, AuthoringInformation authoringInformation, List<FormalSupport> formalSupports) {
        public WorkItem { registry = Objects.requireNonNull(registry, "registry must not be null"); required(functionKey, "functionKey"); ReaderFacingTextPolicy.requireSafe(functionName, "functionName"); required(testPointKey, "testPointKey"); ReaderFacingTextPolicy.requireSafe(description, "description"); if (testPointType == null || basis == null) throw new IllegalArgumentException("testPointType and basis must not be null"); requirementFactKeys = requiredList(requirementFactKeys, "requirementFactKeys"); evidenceKeys = requiredList(evidenceKeys, "evidenceKeys"); missingInformation = requiredList(missingInformation, "missingInformation"); authoringInformation = Objects.requireNonNull(authoringInformation, "authoringInformation must not be null"); formalSupports = requiredList(formalSupports, "formalSupports"); ReaderFacingTextPolicy.requireSafeItems(missingInformation, "missingInformation"); registry.require(StructuredKeyType.FUNCTION, functionKey); registry.require(StructuredKeyType.TEST_POINT, testPointKey); requireSubset(requirementFactKeys, requirementFactKeys, StructuredKeyType.REQUIREMENT_FACT, registry, "requirement fact"); for (String evidenceKey : evidenceKeys) registry.requireEvidence(evidenceKey); if (basis == Basis.GENERAL_EXPERIENCE) requireNonblankItems(missingInformation, "missingInformation"); validateSupportClosure(basis, requirementFactKeys, evidenceKeys, formalSupports); }
        public WorkItem(StructuredValidationRegistry registry, String functionKey, String functionName, String testPointKey, String description, TestPointType testPointType, Basis basis, List<String> requirementFactKeys, List<String> evidenceKeys, List<String> missingInformation, List<FormalSupport> formalSupports) { this(registry, functionKey, functionName, testPointKey, description, testPointType, basis, requirementFactKeys, evidenceKeys, missingInformation, AuthoringInformation.empty(), formalSupports); }
    }
    private static void validateSupportClosure(Basis basis, List<String> factKeys, List<String> evidenceKeys, List<FormalSupport> supports) { if (basis == Basis.GENERAL_EXPERIENCE) return; Set<String> supportedFacts = new LinkedHashSet<>(); Set<String> supportedEvidence = new LinkedHashSet<>(); for (FormalSupport support : supports) { FormalSupport checked = Objects.requireNonNull(support, "formal support must not be null"); if (!factKeys.contains(checked.factKey()) || !supportedFacts.add(checked.factKey())) throw new IllegalArgumentException("Formal support facts must exactly match the test-point closure"); for (String key : checked.evidenceTexts().keySet()) { if (!evidenceKeys.contains(key)) throw new IllegalArgumentException("Formal support evidence is outside the test-point closure"); supportedEvidence.add(key); } } if (!supportedFacts.equals(new LinkedHashSet<>(factKeys)) || !supportedEvidence.equals(new LinkedHashSet<>(evidenceKeys))) throw new IllegalArgumentException("Formal support must cover every frozen fact and evidence key"); }

    /** Persisted formal fact text and its exact parsed-unit evidence. */
    public record FormalSupport(String factKey, String function, List<String> roles, List<String> triggerConditions, List<String> inputs, List<String> businessRules, List<String> outputs, List<String> permissions, List<String> stateChanges, List<String> exceptionHandling, List<String> externalDependencies, Map<String, String> evidenceTexts) { public FormalSupport { required(factKey, "formal support factKey"); required(function, "formal support function"); roles = safeCopy(roles, "formal support roles"); triggerConditions = safeCopy(triggerConditions, "formal support triggerConditions"); inputs = safeCopy(inputs, "formal support inputs"); businessRules = safeCopy(businessRules, "formal support businessRules"); outputs = safeCopy(outputs, "formal support outputs"); permissions = safeCopy(permissions, "formal support permissions"); stateChanges = safeCopy(stateChanges, "formal support stateChanges"); exceptionHandling = safeCopy(exceptionHandling, "formal support exceptionHandling"); externalDependencies = safeCopy(externalDependencies, "formal support externalDependencies"); Map<String,String> copy = new LinkedHashMap<>(); Objects.requireNonNull(evidenceTexts, "formal support evidenceTexts must not be null").forEach((key, text) -> { required(key, "formal support evidence key"); required(text, "formal support evidence text"); if (copy.putIfAbsent(key, text) != null) throw new IllegalArgumentException("formal support evidence keys must be unique"); }); evidenceTexts = Map.copyOf(copy); } }
    private static List<String> safeCopy(List<String> values, String field) { List<String> copy = requiredList(values, field); for (String value : copy) required(value, field); return copy; }

    /** Java-owned validation projection of one structured testcase result. [Req-ID]: REQ-FTG-006 */
    public record Result(String functionKey, String testPointKey, List<Testcase> testcases) { }

    /** One candidate case whose reader fields are checked before persistence. [Req-ID]: REQ-FTG-006 */
    public record Testcase(String caseKey, String name, String title, Priority priority, List<String> preconditions, Initialization initialization, List<Input> inputs, List<Step> steps, List<String> expectedResults, String evaluationCriteria, String resultEvaluationCriteria, List<String> terminationConditions, String resultCollection, AuthoringInformation authoringInformation, List<String> requirementFactKeys, List<String> evidenceKeys, CaseStatus caseStatus, List<String> missingInformation) { public Testcase(String caseKey, String title, List<String> preconditions, List<Step> steps, List<String> requirementFactKeys, List<String> evidenceKeys, CaseStatus caseStatus, List<String> missingInformation) { this(caseKey, title, title, Priority.MEDIUM, preconditions, Initialization.empty(), List.of(), steps, steps.stream().map(Step::expected).toList(), EVALUATION, RESULT_EVALUATION, List.of(), RESULT_COLLECTION, AuthoringInformation.empty(), requirementFactKeys, evidenceKeys, caseStatus, missingInformation); } }

    /** Four explicit source-backed initialization dimensions. [Req-ID]: REQ-FTG-006 */
    public record Initialization(List<String> hardwareConfiguration, List<String> softwareConfiguration, List<String> testConfiguration, List<String> parameterConfiguration) { public static Initialization empty() { return new Initialization(List.of(), List.of(), List.of(), List.of()); } }

    /** Source-grounded input value plus testing-method metadata. [Req-ID]: REQ-FTG-006 */
    public record Input(String content, InputNature nature, InputSource source, TestMethod method, Authenticity authenticity, String sequence) { }

    /** One executable ordered action with its expected, evaluation, stop, and collection text. [Req-ID]: REQ-FTG-006 */
    public record Step(int stepNo, String action, String expected, String evaluationCriteria, String terminationOrError, String resultCollection) { public Step(int stepNo, String action, String expected) { this(stepNo, action, expected, STEP_EVALUATION, TERMINATION_OR_ERROR, RESULT_COLLECTION); } }

    /** Caller-owned author/date that every returned case must echo exactly. [Req-ID]: REQ-FTG-006 */
    public record AuthoringInformation(String author, String date) { public AuthoringInformation { author = author == null ? "" : author; date = date == null ? "" : date; } public static AuthoringInformation empty() { return new AuthoringInformation("", ""); } }
    public record ValidationOutcome(Result result, boolean formalCoverageSatisfied) { }
    public enum TestPointType { NORMAL_BEHAVIOR, INPUT_VALIDATION, BOUNDARY_VALUE, PERMISSION, STATE_TRANSITION, BUSINESS_EXCEPTION, DEPENDENCY_FAILURE }
    public enum Basis { FORMAL_REQUIREMENT, GENERAL_EXPERIENCE }
    public enum CaseStatus { FORMAL, PENDING_CONFIRMATION }
    public enum Priority { HIGH, MEDIUM, LOW }
    public enum InputNature { VALID, INVALID, BOUNDARY, OTHER, UNSPECIFIED }
    public enum InputSource { MANUAL, PROGRAM, FILE, SIMULATION, OTHER, UNSPECIFIED }
    public enum TestMethod { EQUIVALENCE_PARTITIONING, BOUNDARY_VALUE_ANALYSIS, ERROR_GUESSING, CAUSE_EFFECT_GRAPH, OTHER, UNSPECIFIED }
    public enum Authenticity { REAL, SIMULATED, UNSPECIFIED }
}
