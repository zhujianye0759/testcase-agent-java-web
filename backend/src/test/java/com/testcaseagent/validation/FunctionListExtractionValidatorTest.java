package com.testcaseagent.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests Java-owned function-list identity and evidence closure. [Req-ID]: REQ-STG-001, REQ-STG-003 */
class FunctionListExtractionValidatorTest {
    private final FunctionListExtractionValidator validator = new FunctionListExtractionValidator();

    @Test
    void createsStableTaskOwnedKeysOnlyAfterTheWholeSlicePassesValidation() {
        FunctionListExtractionValidator.WorkItem workItem = workItem();
        FunctionListExtractionValidator.Result result = new FunctionListExtractionValidator.Result(List.of(
                new FunctionListExtractionValidator.ModelItem(
                        "订单 / 提交", "提交订单", List.of("evidence-33"), "订单提交")));

        List<FunctionListExtractionValidator.ValidatedItem> first = validator.validate(workItem, result);
        List<FunctionListExtractionValidator.ValidatedItem> equivalent = validator.validate(workItem,
                new FunctionListExtractionValidator.Result(List.of(
                        new FunctionListExtractionValidator.ModelItem(
                                "  订单 /  提交 ", "ＴＥＳＴ", List.of("evidence-33"), "订单提交"))));
        List<FunctionListExtractionValidator.ValidatedItem> equivalentAgain = validator.validate(workItem,
                new FunctionListExtractionValidator.Result(List.of(
                        new FunctionListExtractionValidator.ModelItem(
                                "订单 / 提交", "test", List.of("evidence-33"), "订单提交"))));

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

    /** [Req-ID]: REQ-FTG-014 */
    @Test
    void rejectsMissingOrUnboundTargetQuotesBeforeReturningAnyItem() {
        FunctionListExtractionValidator.WorkItem workItem = targetQuoteWorkItem();

        assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem,
                new FunctionListExtractionValidator.Result(List.of(
                        new FunctionListExtractionValidator.ModelItem(
                                "订单/提交", "提交订单", List.of("evidence-33"), null)))));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem,
                new FunctionListExtractionValidator.Result(List.of(
                        new FunctionListExtractionValidator.ModelItem(
                                "订单/提交", "提交订单", List.of("evidence-33"), "上下文中的层级名称")))));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem,
                new FunctionListExtractionValidator.Result(List.of(
                        new FunctionListExtractionValidator.ModelItem(
                                "订单/提交", "提交订单", List.of("evidence-33"), "取消订单")))));
    }

    /** [Req-ID]: REQ-FTG-014 */
    @Test
    void acceptsAnExactQuoteFromOneReferencedTargetUnitAfterLayoutWhitespaceNormalization() {
        var result = validator.validate(targetQuoteWorkItem(),
                new FunctionListExtractionValidator.Result(List.of(
                        new FunctionListExtractionValidator.ModelItem(
                                "订单提交", "提交订单", List.of("evidence-33"), "订单提交"))));

        assertEquals(List.of("订单提交"), result.get(0).targetQuotes());
    }

    /** [Req-ID]: REQ-FTG-014 */
    @Test
    void rejectsTargetTextThatDoesNotContainTheFinalPathLeaf() {
        StructuredValidationRegistry registry = StructuredValidationRegistry.forTask("task-1")
                .register(StructuredKeyType.MATERIAL, "material-function-list")
                .registerEvidence(new StructuredEvidence(
                        "u-target", "task-1", "material-function-list", false, false, true));
        var workItem = new FunctionListExtractionValidator.WorkItem(
                registry, "material-function-list", List.of("u-target"), Map.of("u-target", "备注"));
        var result = new FunctionListExtractionValidator.Result(List.of(
                new FunctionListExtractionValidator.ModelItem(
                        "用户管理/批量删除", "管理员批量删除用户", List.of("u-target"), "备")));

        assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem, result));
    }

    /** [Req-ID]: REQ-FTG-014 */
    @Test
    void extractsTheFinalLeafAcrossEveryFrozenPathSeparator() {
        for (String separator : List.of("/", "\\", "→", ">", "›", "»", "|")) {
            var result = validator.validate(targetQuoteWorkItem(),
                    new FunctionListExtractionValidator.Result(List.of(
                            new FunctionListExtractionValidator.ModelItem(
                                    "订单" + separator + "订单提交", "提交订单",
                                    List.of("evidence-33"), "订单提交"))));
            assertEquals(1, result.size(), separator);
        }
    }

    /** [Req-ID]: REQ-FTG-014 */
    @Test
    void normalizesFullWidthSeparatorsAndIgnoresATrailingEmptyPathSegment() {
        for (String path : List.of("订单／提交", "订单/提交/")) {
            var result = validator.validate(targetQuoteWorkItem(),
                    new FunctionListExtractionValidator.Result(List.of(
                            new FunctionListExtractionValidator.ModelItem(
                                    path, "提交订单", List.of("evidence-33"), "订单提交"))));
            assertEquals(1, result.size(), path);
        }
    }

    /** [Req-ID]: REQ-FTG-014 */
    @Test
    void usesLocaleRootFullLowercaseForGreekFinalSigma() {
        var workItem = targetQuoteWorkItem("ΟΣ");

        assertEquals(1, validator.validate(workItem,
                new FunctionListExtractionValidator.Result(List.of(
                        new FunctionListExtractionValidator.ModelItem(
                                "ος", "希腊字母", List.of("evidence-33"), "ος")))).size());
        assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem,
                new FunctionListExtractionValidator.Result(List.of(
                        new FunctionListExtractionValidator.ModelItem(
                                "οσ", "希腊字母", List.of("evidence-33"), "οσ")))));
    }

    /** [Req-ID]: REQ-FTG-014 */
    @Test
    void removesJavaLayoutSeparatorU001cButPreservesNelU0085() {
        assertEquals(1, validator.validate(targetQuoteWorkItem("订单\u001c提交"),
                new FunctionListExtractionValidator.Result(List.of(
                        new FunctionListExtractionValidator.ModelItem(
                                "订单提交", "提交订单", List.of("evidence-33"), "订单提交")))).size());
        assertThrows(IllegalArgumentException.class, () -> validator.validate(targetQuoteWorkItem("订单\u0085提交"),
                new FunctionListExtractionValidator.Result(List.of(
                        new FunctionListExtractionValidator.ModelItem(
                                "订单提交", "提交订单", List.of("evidence-33"), "订单提交")))));
    }

    /** [Req-ID]: REQ-FTG-014 */
    @Test
    void acceptsAtMost512UnicodeCodePointsAndRejectsLongerTargetQuotes() {
        String maximum = "功能" + "甲".repeat(510);
        String oversized = maximum + "乙";
        StructuredValidationRegistry registry = StructuredValidationRegistry.forTask("task-1")
                .register(StructuredKeyType.MATERIAL, "material-function-list")
                .registerEvidence(new StructuredEvidence(
                        "u-target", "task-1", "material-function-list", false, false, true));
        var workItem = new FunctionListExtractionValidator.WorkItem(
                registry, "material-function-list", List.of("u-target"), Map.of("u-target", oversized));

        assertEquals(1, validator.validate(workItem, new FunctionListExtractionValidator.Result(List.of(
                new FunctionListExtractionValidator.ModelItem(
                        "功能", "功能说明", List.of("u-target"), maximum)))).size());
        assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem,
                new FunctionListExtractionValidator.Result(List.of(
                        new FunctionListExtractionValidator.ModelItem(
                                "功能", "功能说明", List.of("u-target"), oversized)))));
    }

    /** [Req-ID]: REQ-FTG-014 */
    @Test
    void rejectsTheWholeBatchWhenALaterItemUsesContextTextAsItsQuote() {
        var result = new FunctionListExtractionValidator.Result(List.of(
                new FunctionListExtractionValidator.ModelItem(
                        "订单/提交", "提交订单", List.of("evidence-33"), "订单提交"),
                new FunctionListExtractionValidator.ModelItem(
                        "订单/查询", "查询订单", List.of("evidence-33"), "上下文中的层级名称")));

        assertThrows(IllegalArgumentException.class, () -> validator.validate(targetQuoteWorkItem(), result));
    }

    @Test
    void rejectsInternalStableKeysInFunctionListPathOrDescription() {
        String internalItem = "fli-bc5dafcd3684fbf0005736a8110f1ef6adc1af19c63a3e8728e992cb534d0b95";
        assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem(),
                new FunctionListExtractionValidator.Result(List.of(
                        new FunctionListExtractionValidator.ModelItem(
                                "订单/" + internalItem, "提交订单", List.of("evidence-33"), "订单提交")))));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(workItem(),
                new FunctionListExtractionValidator.Result(List.of(
                        new FunctionListExtractionValidator.ModelItem(
                                "订单/提交", "对应 " + internalItem, List.of("evidence-33"), "订单提交")))));
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
        StructuredValidationRegistry registry = StructuredValidationRegistry.forTask("task-1")
                .register(StructuredKeyType.MATERIAL, "material-function-list")
                .registerEvidence(new StructuredEvidence(
                        "evidence-33", "task-1", "material-function-list", false, false, true));
        var workItem = new FunctionListExtractionValidator.WorkItem(
                registry, "material-function-list", List.of("evidence-33"),
                Map.of("evidence-33", "a 和 a\u0000b"));
        List<FunctionListExtractionValidator.ValidatedItem> rows = validator.validate(workItem,
                new FunctionListExtractionValidator.Result(List.of(
                        new FunctionListExtractionValidator.ModelItem(
                                "a", "b\u0000c", List.of("evidence-33"), "a"),
                        new FunctionListExtractionValidator.ModelItem(
                                "a\u0000b", "c", List.of("evidence-33"), "a\u0000b"))));

        assertEquals(2, rows.stream().map(FunctionListExtractionValidator.ValidatedItem::itemKey).distinct().count());
        assertEquals(rows.get(0).itemKey(), validator.validate(workItem,
                new FunctionListExtractionValidator.Result(List.of(
                        new FunctionListExtractionValidator.ModelItem(
                                "a", "b\u0000c", List.of("evidence-33"), "a"))))
                .get(0).itemKey());
    }

    private static FunctionListExtractionValidator.WorkItem workItem() {
        StructuredValidationRegistry registry = StructuredValidationRegistry.forTask("task-1")
                .register(StructuredKeyType.MATERIAL, "material-function-list")
                .registerEvidence(new StructuredEvidence("evidence-33", "task-1", "material-function-list", false, false, true))
                .registerEvidence(new StructuredEvidence("evidence-same-material-other-slice", "task-1", "material-function-list", false, false, true))
                .registerEvidence(new StructuredEvidence("evidence-other-material", "task-1", "material-requirements", false, false, true));
        return new FunctionListExtractionValidator.WorkItem(
                registry, "material-function-list", List.of("evidence-33"),
                Map.of("evidence-33", "功能路径：订单提交"));
    }

    private static FunctionListExtractionValidator.WorkItem targetQuoteWorkItem() {
        StructuredValidationRegistry registry = StructuredValidationRegistry.forTask("task-1")
                .register(StructuredKeyType.MATERIAL, "material-function-list")
                .registerEvidence(new StructuredEvidence(
                        "evidence-33", "task-1", "material-function-list", false, false, true))
                .registerEvidence(new StructuredEvidence(
                        "evidence-34", "task-1", "material-function-list", false, false, true));
        return new FunctionListExtractionValidator.WorkItem(
                registry, "material-function-list", List.of("evidence-33", "evidence-34"),
                Map.of("evidence-33", "功能路径：订\n单 提交", "evidence-34", "取消订单"));
    }

    private static FunctionListExtractionValidator.WorkItem targetQuoteWorkItem(String targetText) {
        StructuredValidationRegistry registry = StructuredValidationRegistry.forTask("task-1")
                .register(StructuredKeyType.MATERIAL, "material-function-list")
                .registerEvidence(new StructuredEvidence(
                        "evidence-33", "task-1", "material-function-list", false, false, true));
        return new FunctionListExtractionValidator.WorkItem(
                registry, "material-function-list", List.of("evidence-33"), Map.of("evidence-33", targetText));
    }

    private static FunctionListExtractionValidator.Result resultWithEvidence(String... evidenceKeys) {
        return new FunctionListExtractionValidator.Result(List.of(
                new FunctionListExtractionValidator.ModelItem(
                        "订单/提交", "提交订单", List.of(evidenceKeys), "订单提交")));
    }

    private static FunctionListExtractionValidator.ValidatedItem validated(
            String key, String path, String description, String evidenceKey) {
        return new FunctionListExtractionValidator.ValidatedItem(
                key, path, description, List.of(evidenceKey), List.of("提交订单"));
    }
}
