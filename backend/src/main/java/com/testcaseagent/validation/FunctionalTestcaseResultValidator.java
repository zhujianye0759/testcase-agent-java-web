package com.testcaseagent.validation;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Validates one test-point result and returns coverage without altering any candidate case. [Req-ID]: REQ-STG-001, REQ-STG-004, REQ-STG-005 */
public final class FunctionalTestcaseResultValidator {

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
                hasFormalCoverage = true;
            }
            List<String> missingInformation = requiredList(row.missingInformation(), "case missingInformation");
            if (row.caseStatus() == CaseStatus.PENDING_CONFIRMATION) {
                requireNonblankItems(missingInformation, "case missingInformation");
            } else {
                for (String value : missingInformation) required(value, "case missingInformation");
            }
            validateSteps(requiredList(row.steps(), "steps"));
        }
        if (item.basis() == Basis.FORMAL_REQUIREMENT && !hasFormalCoverage) {
            throw new IllegalArgumentException("A formal requirement test point requires at least one formal testcase");
        }
        return new ValidationOutcome(checked, item.basis() == Basis.FORMAL_REQUIREMENT && hasFormalCoverage);
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
            List<String> missingInformation) {
        public WorkItem {
            registry = Objects.requireNonNull(registry, "registry must not be null");
            required(functionKey, "functionKey");
            required(functionName, "functionName");
            required(testPointKey, "testPointKey");
            required(description, "description");
            if (testPointType == null || basis == null) throw new IllegalArgumentException("testPointType and basis must not be null");
            requirementFactKeys = requiredList(requirementFactKeys, "requirementFactKeys");
            evidenceKeys = requiredList(evidenceKeys, "evidenceKeys");
            missingInformation = requiredList(missingInformation, "missingInformation");
            registry.require(StructuredKeyType.FUNCTION, functionKey);
            registry.require(StructuredKeyType.TEST_POINT, testPointKey);
            requireSubset(requirementFactKeys, requirementFactKeys, StructuredKeyType.REQUIREMENT_FACT, registry, "requirement fact");
            for (String evidenceKey : evidenceKeys) registry.requireEvidence(evidenceKey);
            if (basis == Basis.GENERAL_EXPERIENCE) requireNonblankItems(missingInformation, "missingInformation");
        }
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
