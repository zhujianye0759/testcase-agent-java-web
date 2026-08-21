package com.testcaseagent.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests Java-owned function-list identity and evidence closure. [Req-ID]: REQ-STG-001, REQ-STG-003 */
class FunctionListExtractionValidatorTest {
    private final FunctionListExtractionValidator validator = new FunctionListExtractionValidator();

    @Test
    void createsStableTaskOwnedKeysOnlyAfterTheWholeSlicePassesValidation() {
        FunctionListExtractionValidator.WorkItem workItem = workItem();
        FunctionListExtractionValidator.Result result = new FunctionListExtractionValidator.Result(List.of(
                new FunctionListExtractionValidator.ModelItem("订单 / 提交", "提交订单", List.of("evidence-33"))));

        List<FunctionListExtractionValidator.ValidatedItem> first = validator.validate(workItem, result);
        List<FunctionListExtractionValidator.ValidatedItem> equivalent = validator.validate(workItem,
                new FunctionListExtractionValidator.Result(List.of(
                        new FunctionListExtractionValidator.ModelItem("  订单 /  提交 ", "ＴＥＳＴ", List.of("evidence-33")))));
        List<FunctionListExtractionValidator.ValidatedItem> equivalentAgain = validator.validate(workItem,
                new FunctionListExtractionValidator.Result(List.of(
                        new FunctionListExtractionValidator.ModelItem("订单 / 提交", "test", List.of("evidence-33")))));

        assertEquals(1, first.size());
        assertEquals(equivalent.get(0).itemKey(), equivalentAgain.get(0).itemKey());
        assertThrows(IllegalArgumentException.class,
                () -> workItem.registry().require(StructuredKeyType.FUNCTION_LIST_ITEM, first.get(0).itemKey()));
    }

    @Test
    void rejectsEvidenceOutsideTheCurrentSliceOrMaterialBeforeReturningAnyAcceptedItem() {
        FunctionListExtractionValidator.WorkItem workItem = workItem();

        assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem,
                resultWithEvidence("evidence-same-material-other-slice")));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem,
                resultWithEvidence("evidence-other-material")));
    }

    @Test
    void rejectsMissingOrDuplicateEvidence() {
        FunctionListExtractionValidator.WorkItem workItem = workItem();

        assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem, resultWithEvidence()));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(workItem, resultWithEvidence("evidence-33", "evidence-33")));
    }

    @Test
    void rejectsInternalStableKeysInFunctionListPathOrDescription() {
        String internalItem = "fli-bc5dafcd3684fbf0005736a8110f1ef6adc1af19c63a3e8728e992cb534d0b95";
        assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem(),
                new FunctionListExtractionValidator.Result(List.of(
                        new FunctionListExtractionValidator.ModelItem("订单/" + internalItem, "提交订单", List.of("evidence-33"))))));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem(),
                new FunctionListExtractionValidator.Result(List.of(
                        new FunctionListExtractionValidator.ModelItem("订单/提交", "对应 " + internalItem, List.of("evidence-33"))))));
    }

    @Test
    void mergesNormalizedCrossSliceDuplicatesAndUnionsEvidenceDeterministically() {
        List<FunctionListExtractionValidator.ValidatedItem> merged = validator.mergeSlices(List.of(
                validated("item-1", "订单 / 提交", "提交订单", "evidence-33"),
                validated("item-1", "  订单 /  提交 ", "提交订单", "evidence-34"),
                validated("item-2", "订单 / 取消", "取消订单", "evidence-35")));

        assertEquals(2, merged.size());
        assertEquals(List.of("evidence-33", "evidence-34"), merged.get(0).evidenceKeys());
        assertEquals("item-2", merged.get(1).itemKey());
    }

    @Test
    void lengthPrefixesPreventNulFromMovingAcrossStableKeyFieldBoundaries() {
        List<FunctionListExtractionValidator.ValidatedItem> rows = validator.validate(workItem(),
                new FunctionListExtractionValidator.Result(List.of(
                        new FunctionListExtractionValidator.ModelItem("a", "b\u0000c", List.of("evidence-33")),
                        new FunctionListExtractionValidator.ModelItem("a\u0000b", "c", List.of("evidence-33")))));

        assertEquals(2, rows.stream().map(FunctionListExtractionValidator.ValidatedItem::itemKey).distinct().count());
        assertEquals(rows.get(0).itemKey(), validator.validate(workItem(),
                new FunctionListExtractionValidator.Result(List.of(
                        new FunctionListExtractionValidator.ModelItem("a", "b\u0000c", List.of("evidence-33")))))
                .get(0).itemKey());
    }

    private static FunctionListExtractionValidator.WorkItem workItem() {
        StructuredValidationRegistry registry = StructuredValidationRegistry.forTask("task-1")
                .register(StructuredKeyType.MATERIAL, "material-function-list")
                .registerEvidence(new StructuredEvidence("evidence-33", "task-1", "material-function-list", false, false, true))
                .registerEvidence(new StructuredEvidence("evidence-same-material-other-slice", "task-1", "material-function-list", false, false, true))
                .registerEvidence(new StructuredEvidence("evidence-other-material", "task-1", "material-requirements", false, false, true));
        return new FunctionListExtractionValidator.WorkItem(
                registry, "material-function-list", List.of("evidence-33"));
    }

    private static FunctionListExtractionValidator.Result resultWithEvidence(String... evidenceKeys) {
        return new FunctionListExtractionValidator.Result(List.of(
                new FunctionListExtractionValidator.ModelItem("订单/提交", "提交订单", List.of(evidenceKeys))));
    }

    private static FunctionListExtractionValidator.ValidatedItem validated(
            String key, String path, String description, String evidenceKey) {
        return new FunctionListExtractionValidator.ValidatedItem(key, path, description, List.of(evidenceKey));
    }
}
