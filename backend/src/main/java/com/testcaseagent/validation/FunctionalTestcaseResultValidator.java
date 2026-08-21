package com.testcaseagent.validation;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Validates one test-point result and returns coverage without altering any candidate case. [Req-ID]: REQ-STG-001, REQ-STG-004, REQ-STG-005, REQ-FTG-001, REQ-FTG-002 */
public final class FunctionalTestcaseResultValidator {
    private static final List<String> TITLE_WRAPPERS = List.of("验证", "确认", "正常");
    private static final List<String> PRECONDITION_WRAPPERS = List.of("前提:", "前置条件:");
    private static final List<String> ACTION_WRAPPERS = List.of("操作:", "执行:");
    private static final List<String> EXPECTED_WRAPPERS = List.of("预期:", "预期结果:");

    /** Validates the exact echoed target, reference closure, statuses, and consecutive step numbering. */
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
        for (Testcase testcase : cases) {
            Testcase row = Objects.requireNonNull(testcase, "testcase must not be null");
            if (!caseKeys.add(required(row.caseKey(), "caseKey"))) throw new IllegalArgumentException("caseKey must be unique");
            ReaderFacingTextPolicy.requireSafe(row.title(), "case title");
            ReaderFacingTextPolicy.requireSafeItems(requiredList(row.preconditions(), "preconditions"), "precondition");
            if (row.caseStatus() == null) throw new IllegalArgumentException("caseStatus must not be null");
            if (item.basis() == Basis.GENERAL_EXPERIENCE && row.caseStatus() != CaseStatus.PENDING_CONFIRMATION) {
                throw new IllegalArgumentException("General-experience test points can only produce pending-confirmation cases");
            }
            List<String> factKeys = requiredList(row.requirementFactKeys(), "case requirementFactKeys");
            List<String> evidenceKeys = requiredList(row.evidenceKeys(), "case evidenceKeys");
            validateReferences(item, factKeys, evidenceKeys);
            if (row.caseStatus() == CaseStatus.FORMAL) {
                if (factKeys.isEmpty() || evidenceKeys.isEmpty()) {
                    throw new IllegalArgumentException("A formal testcase requires nonempty requirement-fact and evidence closures");
                }
                validateFormalGrounding(item, row, factKeys, evidenceKeys);
                hasFormalCoverage = true;
            }
            List<String> missingInformation = requiredList(row.missingInformation(), "case missingInformation");
            if (row.caseStatus() == CaseStatus.PENDING_CONFIRMATION) {
                requireNonblankItems(missingInformation, "case missingInformation");
            } else {
                for (String value : missingInformation) required(value, "case missingInformation");
            }
            ReaderFacingTextPolicy.requireSafeItems(missingInformation, "case missingInformation");
            validateSteps(requiredList(row.steps(), "steps"));
        }
        if (item.basis() == Basis.FORMAL_REQUIREMENT && !hasFormalCoverage) {
            throw new IllegalArgumentException("A formal requirement test point requires at least one formal testcase");
        }
        return new ValidationOutcome(checked, item.basis() == Basis.FORMAL_REQUIREMENT && hasFormalCoverage);
    }

    private static void validateFormalGrounding(
            WorkItem item, Testcase testcase, List<String> factKeys, List<String> evidenceKeys) {
        List<FormalSupport> supports = item.formalSupports().stream()
                .filter(support -> factKeys.contains(support.factKey())).toList();
        requireDirectSupport(testcase.title(), "case title", titleSources(supports), evidenceKeys, supports,
                TITLE_WRAPPERS, true);
        for (String precondition : testcase.preconditions()) {
            List<String> sources = new ArrayList<>();
            supports.forEach(support -> {
                sources.addAll(support.roles());
                sources.addAll(support.triggerConditions());
                sources.addAll(support.businessRules());
                sources.addAll(support.permissions());
                sources.addAll(support.stateChanges());
            });
            requireDirectSupport(precondition, "case precondition", sources, evidenceKeys, supports,
                    PRECONDITION_WRAPPERS, false);
        }
        for (Step step : testcase.steps()) {
            List<String> actionSources = new ArrayList<>();
            supports.forEach(support -> {
                actionSources.addAll(support.triggerConditions());
                actionSources.addAll(support.inputs());
            });
            requireDirectSupport(step.action(), "step action", actionSources, evidenceKeys, supports,
                    ACTION_WRAPPERS, false);
            List<String> expectedSources = new ArrayList<>();
            supports.forEach(support -> {
                expectedSources.addAll(support.outputs());
                expectedSources.addAll(support.stateChanges());
                expectedSources.addAll(support.exceptionHandling());
                expectedSources.addAll(support.externalDependencies());
            });
            requireDirectSupport(step.expected(), "step expected", expectedSources, evidenceKeys, supports,
                    EXPECTED_WRAPPERS, false);
        }
    }

    private static List<String> titleSources(List<FormalSupport> supports) {
        List<String> sources = new ArrayList<>();
        supports.forEach(support -> sources.add(support.function()));
        return sources;
    }

    private static void requireDirectSupport(String value, String field, List<String> typedSources,
            List<String> evidenceKeys, List<FormalSupport> supports, List<String> wrappers, boolean repeatWrapper) {
        String claim = normalize(stripFieldWrapper(value, field, wrappers, repeatWrapper));
        if (claim.isEmpty()) throw new IllegalArgumentException(field + " has no business content after normalization");
        if (typedSources.stream().map(FunctionalTestcaseResultValidator::normalize)
                .anyMatch(source -> !source.isEmpty() && source.contains(claim))) return;
        boolean supportedByEvidence = supports.stream()
                .flatMap(support -> support.evidenceTexts().entrySet().stream())
                .filter(entry -> evidenceKeys.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .map(FunctionalTestcaseResultValidator::normalize)
                .anyMatch(source -> !source.isEmpty() && source.contains(claim));
        if (!supportedByEvidence) {
            throw new IllegalArgumentException(field + " is not directly supported by its bound formal facts or evidence");
        }
    }

    private static String stripFieldWrapper(
            String value, String field, List<String> wrappers, boolean repeatWrapper) {
        String result = Normalizer.normalize(required(value, field), Normalizer.Form.NFKC).strip();
        boolean removed;
        do {
            removed = false;
            for (String wrapper : wrappers) {
                if (result.startsWith(wrapper) && result.length() > wrapper.length()) {
                    result = result.substring(wrapper.length()).strip();
                    removed = true;
                    break;
                }
            }
        } while (removed && repeatWrapper);
        return result;
    }

    private static String normalize(String value) {
        String normalized = Normalizer.normalize(required(value, "grounding text"), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        StringBuilder compact = new StringBuilder(normalized.length());
        normalized.codePoints().filter(Character::isLetterOrDigit).forEach(compact::appendCodePoint);
        return compact.toString();
    }

    private static void validateReferences(WorkItem item, List<String> factKeys, List<String> evidenceKeys) {
        requireSubset(factKeys, item.requirementFactKeys(), StructuredKeyType.REQUIREMENT_FACT, item.registry(), "requirement fact");
        Set<String> distinctEvidence = new HashSet<>();
        for (String value : evidenceKeys) {
            String evidenceKey = required(value, "evidenceKey");
            if (!distinctEvidence.add(evidenceKey) || !item.evidenceKeys().contains(evidenceKey)) {
                throw new IllegalArgumentException("Case evidence is duplicate or outside the test-point closure");
            }
            item.registry().requireEvidence(evidenceKey);
        }
    }

    private static void validateSteps(List<Step> steps) {
        if (steps.isEmpty() || steps.size() > 50) throw new IllegalArgumentException("steps must contain 1..50 rows");
        for (int index = 0; index < steps.size(); index++) {
            Step step = Objects.requireNonNull(steps.get(index), "step must not be null");
            if (step.stepNo() != index + 1) throw new IllegalArgumentException("stepNo must start at 1 and be consecutive");
            ReaderFacingTextPolicy.requireSafe(step.action(), "step action");
            ReaderFacingTextPolicy.requireSafe(step.expected(), "step expected");
        }
    }

    private static void requireSubset(List<String> values, List<String> expected, StructuredKeyType type,
            StructuredValidationRegistry registry, String label) {
        Set<String> distinct = new HashSet<>();
        for (String value : values) {
            String key = required(value, label + " key");
            if (!distinct.add(key) || !expected.contains(key)) throw new IllegalArgumentException("Case references a duplicate or out-of-test-point " + label);
            registry.require(type, key);
        }
    }

    private static <T> List<T> requiredList(List<T> values, String field) {
        if (values == null) throw new IllegalArgumentException(field + " must not be null");
        return List.copyOf(values);
    }

    private static void requireNonblankItems(List<String> values, String field) {
        if (values.isEmpty()) throw new IllegalArgumentException(field + " must not be empty");
        for (String value : values) required(value, field);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    /** Immutable request-side closure for exactly one test point. */
    public record WorkItem(StructuredValidationRegistry registry, String functionKey, String functionName, String testPointKey, String description,
            TestPointType testPointType, Basis basis, List<String> requirementFactKeys, List<String> evidenceKeys,
            List<String> missingInformation, List<FormalSupport> formalSupports) {
        public WorkItem {
            registry = Objects.requireNonNull(registry, "registry must not be null");
            required(functionKey, "functionKey");
            ReaderFacingTextPolicy.requireSafe(functionName, "functionName");
            required(testPointKey, "testPointKey");
            ReaderFacingTextPolicy.requireSafe(description, "description");
            if (testPointType == null || basis == null) throw new IllegalArgumentException("testPointType and basis must not be null");
            requirementFactKeys = requiredList(requirementFactKeys, "requirementFactKeys");
            evidenceKeys = requiredList(evidenceKeys, "evidenceKeys");
            missingInformation = requiredList(missingInformation, "missingInformation");
            formalSupports = requiredList(formalSupports, "formalSupports");
            ReaderFacingTextPolicy.requireSafeItems(missingInformation, "missingInformation");
            registry.require(StructuredKeyType.FUNCTION, functionKey);
            registry.require(StructuredKeyType.TEST_POINT, testPointKey);
            requireSubset(requirementFactKeys, requirementFactKeys, StructuredKeyType.REQUIREMENT_FACT, registry, "requirement fact");
            for (String evidenceKey : evidenceKeys) registry.requireEvidence(evidenceKey);
            if (basis == Basis.GENERAL_EXPERIENCE) requireNonblankItems(missingInformation, "missingInformation");
            validateSupportClosure(basis, requirementFactKeys, evidenceKeys, formalSupports);
        }
    }

    private static void validateSupportClosure(Basis basis, List<String> factKeys, List<String> evidenceKeys,
            List<FormalSupport> supports) {
        if (basis == Basis.GENERAL_EXPERIENCE) return;
        Set<String> supportedFacts = new LinkedHashSet<>();
        Set<String> supportedEvidence = new LinkedHashSet<>();
        for (FormalSupport support : supports) {
            FormalSupport checked = Objects.requireNonNull(support, "formal support must not be null");
            if (!factKeys.contains(checked.factKey()) || !supportedFacts.add(checked.factKey())) {
                throw new IllegalArgumentException("Formal support facts must exactly match the test-point closure");
            }
            for (String evidenceKey : checked.evidenceTexts().keySet()) {
                if (!evidenceKeys.contains(evidenceKey)) {
                    throw new IllegalArgumentException("Formal support evidence is outside the test-point closure");
                }
                supportedEvidence.add(evidenceKey);
            }
        }
        if (!supportedFacts.equals(new LinkedHashSet<>(factKeys))
                || !supportedEvidence.equals(new LinkedHashSet<>(evidenceKeys))) {
            throw new IllegalArgumentException("Formal support must cover every frozen fact and evidence key");
        }
    }

    /** Persisted formal fact text and its exact parsed-unit evidence, never test-point narration. */
    public record FormalSupport(String factKey, String function, List<String> roles, List<String> triggerConditions,
            List<String> inputs, List<String> businessRules, List<String> outputs, List<String> permissions,
            List<String> stateChanges, List<String> exceptionHandling, List<String> externalDependencies,
            Map<String, String> evidenceTexts) {
        public FormalSupport {
            required(factKey, "formal support factKey");
            required(function, "formal support function");
            roles = safeCopy(roles, "formal support roles");
            triggerConditions = safeCopy(triggerConditions, "formal support triggerConditions");
            inputs = safeCopy(inputs, "formal support inputs");
            businessRules = safeCopy(businessRules, "formal support businessRules");
            outputs = safeCopy(outputs, "formal support outputs");
            permissions = safeCopy(permissions, "formal support permissions");
            stateChanges = safeCopy(stateChanges, "formal support stateChanges");
            exceptionHandling = safeCopy(exceptionHandling, "formal support exceptionHandling");
            externalDependencies = safeCopy(externalDependencies, "formal support externalDependencies");
            Map<String, String> copiedEvidence = new java.util.LinkedHashMap<>();
            Objects.requireNonNull(evidenceTexts, "formal support evidenceTexts must not be null").forEach((key, text) -> {
                required(key, "formal support evidence key");
                required(text, "formal support evidence text");
                if (copiedEvidence.putIfAbsent(key, text) != null) {
                    throw new IllegalArgumentException("formal support evidence keys must be unique");
                }
            });
            evidenceTexts = Map.copyOf(copiedEvidence);
        }
    }

    private static List<String> safeCopy(List<String> values, String field) {
        List<String> copied = requiredList(values, field);
        for (String value : copied) required(value, field);
        return copied;
    }

    /** Exact Skill result for one test-point request. */
    public record Result(String functionKey, String testPointKey, List<Testcase> testcases) { }

    /** One candidate test case, saved only by the caller after this validation succeeds. */
    public record Testcase(String caseKey, String title, List<String> preconditions, List<Step> steps,
            List<String> requirementFactKeys, List<String> evidenceKeys, CaseStatus caseStatus,
            List<String> missingInformation) { }

    /** One numbered action and expected result pair. */
    public record Step(int stepNo, String action, String expected) { }

    /** Acceptance outcome that keeps formal coverage separate from processing completion. */
    public record ValidationOutcome(Result result, boolean formalCoverageSatisfied) { }

    /** Frozen seven-category test-point vocabulary. */
    public enum TestPointType { NORMAL_BEHAVIOR, INPUT_VALIDATION, BOUNDARY_VALUE, PERMISSION, STATE_TRANSITION, BUSINESS_EXCEPTION, DEPENDENCY_FAILURE }

    /** Frozen evidence bases. */
    public enum Basis { FORMAL_REQUIREMENT, GENERAL_EXPERIENCE }

    /** Frozen testcase statuses. */
    public enum CaseStatus { FORMAL, PENDING_CONFIRMATION }
}
