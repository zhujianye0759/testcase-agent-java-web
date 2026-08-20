package com.testcaseagent.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests test-point to testcase acceptance and formal-coverage rules. [Req-ID]: REQ-STG-001, REQ-STG-004, REQ-STG-005 */
class FunctionalTestcaseResultValidatorTest {
    private final FunctionalTestcaseResultValidator validator = new FunctionalTestcaseResultValidator();

    @Test
    void formalPointRequiresAtLeastOneFormalCaseAndDoesNotRequireAFixedCount() {
        FunctionalTestcaseResultValidator.WorkItem workItem = workItem(FunctionalTestcaseResultValidator.Basis.FORMAL_REQUIREMENT);
        FunctionalTestcaseResultValidator.Result result = result("function-1", "point-1", FunctionalTestcaseResultValidator.CaseStatus.FORMAL);

        FunctionalTestcaseResultValidator.ValidationOutcome outcome = validator.validate(workItem, result);
        assertTrue(outcome.formalCoverageSatisfied());
    }

    @Test
    void generalExperienceCannotBeSilentlyPromotedToFormal() {
        FunctionalTestcaseResultValidator.WorkItem workItem = workItem(FunctionalTestcaseResultValidator.Basis.GENERAL_EXPERIENCE);
        assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem,
                result("function-1", "point-1", FunctionalTestcaseResultValidator.CaseStatus.FORMAL)));

        FunctionalTestcaseResultValidator.ValidationOutcome outcome = validator.validate(workItem,
                result("function-1", "point-1", FunctionalTestcaseResultValidator.CaseStatus.PENDING_CONFIRMATION));
        assertFalse(outcome.formalCoverageSatisfied());
    }

    @Test
    void rejectsMismatchedEchoAndNonConsecutiveSteps() {
        FunctionalTestcaseResultValidator.WorkItem workItem = workItem(FunctionalTestcaseResultValidator.Basis.FORMAL_REQUIREMENT);
        assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem,
                result("function-other", "point-1", FunctionalTestcaseResultValidator.CaseStatus.FORMAL)));
        FunctionalTestcaseResultValidator.Testcase malformed = new FunctionalTestcaseResultValidator.Testcase("case-1", "title", List.of(),
                List.of(new FunctionalTestcaseResultValidator.Step(2, "action", "expected")), List.of("fact-1"), List.of("evidence-1"),
                FunctionalTestcaseResultValidator.CaseStatus.FORMAL, List.of());
        assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem,
                new FunctionalTestcaseResultValidator.Result("function-1", "point-1", List.of(malformed))));
    }

    private static FunctionalTestcaseResultValidator.WorkItem workItem(FunctionalTestcaseResultValidator.Basis basis) {
        StructuredValidationRegistry registry = StructuredValidationRegistry.forTask("task-1")
                .register(StructuredKeyType.FUNCTION, "function-1")
                .register(StructuredKeyType.TEST_POINT, "point-1")
                .register(StructuredKeyType.REQUIREMENT_FACT, "fact-1")
                .registerEvidence(new StructuredEvidence("evidence-1", "task-1", "material-1", false, false, true));
        return new FunctionalTestcaseResultValidator.WorkItem(registry, "function-1", "point-1",
                FunctionalTestcaseResultValidator.TestPointType.BOUNDARY_VALUE, basis, List.of("fact-1"), List.of("evidence-1"));
    }

    private static FunctionalTestcaseResultValidator.Result result(
            String functionKey, String testPointKey, FunctionalTestcaseResultValidator.CaseStatus status) {
        FunctionalTestcaseResultValidator.Testcase testcase = new FunctionalTestcaseResultValidator.Testcase("case-1", "title", List.of(),
                List.of(new FunctionalTestcaseResultValidator.Step(1, "action", "expected")), List.of("fact-1"), List.of("evidence-1"),
                status, List.of());
        return new FunctionalTestcaseResultValidator.Result(functionKey, testPointKey, List.of(testcase));
    }
}
