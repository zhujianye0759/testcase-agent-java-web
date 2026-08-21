package com.testcaseagent.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests bidirectional source-terminal validation. [Req-ID]: REQ-STG-001, REQ-STG-003 */
class FeatureReconciliationValidatorTest {
    private final FeatureReconciliationValidator validator = new FeatureReconciliationValidator();

    @Test
    void acceptsPendingInsufficientEvidenceAsAnExplicitTerminalState() {
        FeatureReconciliationValidator.WorkItem workItem = workItem();
        FeatureReconciliationValidator.Result result = new FeatureReconciliationValidator.Result(List.of(
                reconciliation("reconciliation-1", List.of("item-1"), List.of("fact-1"),
                        FeatureReconciliationValidator.Classification.INSUFFICIENT_EVIDENCE,
                        FeatureReconciliationValidator.ConfirmationStatus.PENDING_CONFIRMATION)));

        assertDoesNotThrow(() -> validator.validate(workItem, result));
    }

    @Test
    void rejectsAResultThatLeavesAnyInputSourceWithoutATerminalDisposition() {
        FeatureReconciliationValidator.WorkItem workItem = workItem();
        FeatureReconciliationValidator.Result result = new FeatureReconciliationValidator.Result(List.of(
                reconciliation("reconciliation-1", List.of("item-1"), List.of(),
                        FeatureReconciliationValidator.Classification.FUNCTION_LIST_ONLY,
                        FeatureReconciliationValidator.ConfirmationStatus.PENDING_CONFIRMATION)));

        assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem, result));
    }

    @Test
    void rejectsInternalStableKeysInReaderFacingRecommendation() {
        FeatureReconciliationValidator.Reconciliation unsafe = new FeatureReconciliationValidator.Reconciliation(
                "reconciliation-1", List.of("item-1"), List.of("fact-1"),
                FeatureReconciliationValidator.Classification.EXACT_MATCH, List.of("evidence-1"),
                "合并 fli-bc5dafcd3684fbf0005736a8110f1ef6adc1af19c63a3e8728e992cb534d0b95 与 fact-1724e7041424efc97c0cc3dc53109f39",
                FeatureReconciliationValidator.ConfirmationStatus.CONFIRMED);

        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(workItem(), new FeatureReconciliationValidator.Result(List.of(unsafe))));
    }

    private static FeatureReconciliationValidator.WorkItem workItem() {
        StructuredValidationRegistry registry = StructuredValidationRegistry.forTask("task-1")
                .register(StructuredKeyType.FUNCTION_LIST_ITEM, "item-1")
                .register(StructuredKeyType.REQUIREMENT_FACT, "fact-1")
                .registerEvidence(new StructuredEvidence("evidence-1", "task-1", "material-1", false, false, true));
        return new FeatureReconciliationValidator.WorkItem(registry, List.of("item-1"), List.of("fact-1"), List.of("evidence-1"));
    }

    private static FeatureReconciliationValidator.Reconciliation reconciliation(
            String key, List<String> items, List<String> facts, FeatureReconciliationValidator.Classification classification,
            FeatureReconciliationValidator.ConfirmationStatus status) {
        return new FeatureReconciliationValidator.Reconciliation(key, items, facts, classification, List.of("evidence-1"), "scope", status);
    }
}
