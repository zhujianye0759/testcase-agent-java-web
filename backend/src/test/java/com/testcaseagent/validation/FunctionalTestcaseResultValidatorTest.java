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
    void rejectsFormalCasesWithoutACompleteRequirementAndEvidenceClosure() {
        FunctionalTestcaseResultValidator.WorkItem workItem = workItem(FunctionalTestcaseResultValidator.Basis.FORMAL_REQUIREMENT);
        FunctionalTestcaseResultValidator.Testcase noFacts = new FunctionalTestcaseResultValidator.Testcase("case-1", "title", List.of(),
                List.of(new FunctionalTestcaseResultValidator.Step(1, "action", "expected")), List.of(), List.of("evidence-1"),
                FunctionalTestcaseResultValidator.CaseStatus.FORMAL, List.of());
        FunctionalTestcaseResultValidator.Testcase noEvidence = new FunctionalTestcaseResultValidator.Testcase("case-2", "title", List.of(),
                List.of(new FunctionalTestcaseResultValidator.Step(1, "action", "expected")), List.of("fact-1"), List.of(),
                FunctionalTestcaseResultValidator.CaseStatus.FORMAL, List.of());

        assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem,
                new FunctionalTestcaseResultValidator.Result("function-1", "point-1", List.of(noFacts))));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem,
                new FunctionalTestcaseResultValidator.Result("function-1", "point-1", List.of(noEvidence))));
    }

    @Test
    void formalRequirementPointDoesNotTreatAPendingCandidateAsFormalCoverage() {
        FunctionalTestcaseResultValidator.WorkItem workItem = workItem(FunctionalTestcaseResultValidator.Basis.FORMAL_REQUIREMENT);
        FunctionalTestcaseResultValidator.Testcase pending = new FunctionalTestcaseResultValidator.Testcase("case-1", "title", List.of(),
                List.of(new FunctionalTestcaseResultValidator.Step(1, "action", "expected")), List.of("fact-1"), List.of("evidence-1"),
                FunctionalTestcaseResultValidator.CaseStatus.PENDING_CONFIRMATION, List.of("Awaiting confirmation"));

        assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem,
                new FunctionalTestcaseResultValidator.Result("function-1", "point-1", List.of(pending))));
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
    void generalExperienceRequiresExplicitMissingInformationForThePointAndEveryCandidateCase() {
        StructuredValidationRegistry registry = registry();
        assertThrows(IllegalArgumentException.class, () -> new FunctionalTestcaseResultValidator.WorkItem(registry, "function-1", "point-1",
                FunctionalTestcaseResultValidator.TestPointType.BOUNDARY_VALUE, FunctionalTestcaseResultValidator.Basis.GENERAL_EXPERIENCE,
                List.of("fact-1"), List.of("evidence-1"), List.of()));

        FunctionalTestcaseResultValidator.WorkItem workItem = workItem(FunctionalTestcaseResultValidator.Basis.GENERAL_EXPERIENCE);
        FunctionalTestcaseResultValidator.Testcase unexplained = new FunctionalTestcaseResultValidator.Testcase("case-1", "title", List.of(),
                List.of(new FunctionalTestcaseResultValidator.Step(1, "action", "expected")), List.of("fact-1"), List.of("evidence-1"),
                FunctionalTestcaseResultValidator.CaseStatus.PENDING_CONFIRMATION, List.of());
        assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem,
                new FunctionalTestcaseResultValidator.Result("function-1", "point-1", List.of(unexplained))));
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
        StructuredValidationRegistry registry = registry();
        List<String> missingInformation = basis == FunctionalTestcaseResultValidator.Basis.GENERAL_EXPERIENCE
                ? List.of("No formal requirement evidence is available") : List.of();
        return new FunctionalTestcaseResultValidator.WorkItem(registry, "function-1", "point-1",
                FunctionalTestcaseResultValidator.TestPointType.BOUNDARY_VALUE, basis, List.of("fact-1"), List.of("evidence-1"),
                missingInformation);
    }

    private static StructuredValidationRegistry registry() {
        return StructuredValidationRegistry.forTask("task-1")
                .register(StructuredKeyType.FUNCTION, "function-1")
                .register(StructuredKeyType.TEST_POINT, "point-1")
                .register(StructuredKeyType.REQUIREMENT_FACT, "fact-1")
                .registerEvidence(new StructuredEvidence("evidence-1", "task-1", "material-1", false, false, true));
    }

    private static FunctionalTestcaseResultValidator.Result result(
            String functionKey, String testPointKey, FunctionalTestcaseResultValidator.CaseStatus status) {
        FunctionalTestcaseResultValidator.Testcase testcase = new FunctionalTestcaseResultValidator.Testcase("case-1", "title", List.of(),
                List.of(new FunctionalTestcaseResultValidator.Step(1, "action", "expected")), List.of("fact-1"), List.of("evidence-1"),
                status, status == FunctionalTestcaseResultValidator.CaseStatus.PENDING_CONFIRMATION
                        ? List.of("No formal requirement evidence is available") : List.of());
        return new FunctionalTestcaseResultValidator.Result(functionKey, testPointKey, List.of(testcase));
    }
}
