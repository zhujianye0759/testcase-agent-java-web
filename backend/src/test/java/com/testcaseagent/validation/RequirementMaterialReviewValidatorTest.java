package com.testcaseagent.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests requirement-material result business acceptance. [Req-ID]: REQ-STG-001, REQ-STG-002 */
class RequirementMaterialReviewValidatorTest {
    private final RequirementMaterialReviewValidator validator = new RequirementMaterialReviewValidator();

    @Test
    void acceptsFindingsOnlyForSupplementaryPrototypeMaterial() {
        RequirementMaterialReviewValidator.WorkItem workItem = workItem("prototype");
        RequirementMaterialReviewValidator.Result result = new RequirementMaterialReviewValidator.Result(List.of(), List.of(finding()));

        assertDoesNotThrow(() -> validator.validate(workItem, result));
    }

    @Test
    void rejectsFormalFactsSupportedOnlyBySupplementaryMaterial() {
        RequirementMaterialReviewValidator.WorkItem workItem = workItem("prototype");
        RequirementMaterialReviewValidator.Result result = new RequirementMaterialReviewValidator.Result(List.of(fact()), List.of());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem, result));
        assertTrue(failure.getMessage().toLowerCase(java.util.Locale.ROOT).contains("supplementary"));
    }

    @Test
    void rejectsEmptyResultAndOutOfMaterialEvidence() {
        RequirementMaterialReviewValidator.WorkItem workItem = workItem("requirements_spec");
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(workItem, new RequirementMaterialReviewValidator.Result(List.of(), List.of())));
        RequirementMaterialReviewValidator.ReviewFinding outsideEvidence = new RequirementMaterialReviewValidator.ReviewFinding(
                "finding-1", "ambiguous", "description", List.of("evidence-other"), "impact", "project", "center",
                RequirementMaterialReviewValidator.HandlingLevel.BLOCKING);
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(workItem, new RequirementMaterialReviewValidator.Result(List.of(), List.of(outsideEvidence))));
    }

    private static RequirementMaterialReviewValidator.WorkItem workItem(String contentType) {
        StructuredValidationRegistry registry = StructuredValidationRegistry.forTask("task-1")
                .register(StructuredKeyType.MATERIAL, "material-1")
                .registerEvidence(new StructuredEvidence("evidence-1", "task-1", "material-1", false, false, true))
                .registerEvidence(new StructuredEvidence("evidence-other", "task-1", "material-2", false, false, true));
        return new RequirementMaterialReviewValidator.WorkItem(registry, "material-1", contentType, List.of("evidence-1"));
    }

    private static RequirementMaterialReviewValidator.RequirementFact fact() {
        return new RequirementMaterialReviewValidator.RequirementFact("fact-1", "submit application", List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of("evidence-1"));
    }

    private static RequirementMaterialReviewValidator.ReviewFinding finding() {
        return new RequirementMaterialReviewValidator.ReviewFinding("finding-1", "ambiguous", "description", List.of("evidence-1"),
                "impact", "project", "center", RequirementMaterialReviewValidator.HandlingLevel.CONTINUE_INCOMPLETE);
    }
}
