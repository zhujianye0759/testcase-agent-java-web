package com.testcaseagent.testcase;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.testcaseagent.featureaudit.FeatureReviewConclusionType;
import com.testcaseagent.featureaudit.FrozenFeatureSource;
import com.testcaseagent.featureaudit.FrozenFeatureTarget;
import com.testcaseagent.markdown.MarkdownAuditRow;
import com.testcaseagent.markdown.MarkdownGenerationResult;
import com.testcaseagent.markdown.MarkdownTestCaseRow;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * [Req-ID]: REQ-CAG-001, REQ-CAG-002, REQ-CAG-003
 *
 * <p>Specifies the deterministic acceptance seam between one frozen feature and one already-parsed Markdown batch.</p>
 */
class FrozenFeatureBatchAcceptanceValidatorTest {

    private final FrozenFeatureBatchAcceptanceValidator validator = new FrozenFeatureBatchAcceptanceValidator();

    @Test
    void acceptsExactlyOneNumberedPositiveAndNegativeCaseForTheFrozenPath() {
        assertDoesNotThrow(() -> validator.validate(frozenTarget(), result(List.of(), List.of(
                row("订单查询_正向", "订单管理/订单查询", "1. 输入订单号\n2. 点击查询", "1. 展示匹配订单\n2. 展示订单详情", "需求原文 3.1\ncandidateIds=candidate-a,candidate-b"),
                row("订单查询_反向", "订单管理/订单查询", "1. 输入不存在订单号\n2. 点击查询", "1. 提示无匹配订单\n2. 保持查询页", "依据通用经验，待确认")))));
    }

    @Test
    void rejectsASecondOrMissingCaseAndANameForAnotherFeature() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(frozenTarget(), result(List.of(), List.of(
                row("订单查询_正向", "订单管理/订单查询", "1. 操作", "1. 成功", "需求 3.1\ncandidateIds=candidate-a")))));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(frozenTarget(), result(List.of(), List.of(
                row("订单查询_正向", "订单管理/订单查询", "1. 操作", "1. 成功", "需求 3.1\ncandidateIds=candidate-a"),
                row("订单查询_反向", "订单管理/订单查询", "1. 操作", "1. 失败", "需求 3.1\ncandidateIds=candidate-a"),
                row("用户登录_正向", "订单管理/订单查询", "1. 操作", "1. 成功", "需求 3.1\ncandidateIds=candidate-a")))));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(frozenTarget(), result(List.of(), List.of(
                row("用户登录_正向", "订单管理/订单查询", "1. 操作", "1. 成功", "需求 3.1\ncandidateIds=candidate-a"),
                row("订单查询_反向", "订单管理/订单查询", "1. 操作", "1. 失败", "需求 3.1\ncandidateIds=candidate-a")))));
    }

    @Test
    void rejectsModuleThatDoesNotExactlyEqualTheFrozenFeaturePath() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(frozenTarget(), result(List.of(), List.of(
                row("订单查询_正向", "订单查询", "1. 操作", "1. 成功", "需求 3.1\ncandidateIds=candidate-a"),
                row("订单查询_反向", "订单管理/订单查询", "1. 操作", "1. 失败", "需求 3.1\ncandidateIds=candidate-a")))));
    }

    @Test
    void rejectsUnpairedOrNonContinuousStepAndExpectedNumbering() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(frozenTarget(), result(List.of(), List.of(
                row("订单查询_正向", "订单管理/订单查询", "1. 操作\n3. 再操作", "1. 成功\n2. 成功", "需求 3.1\ncandidateIds=candidate-a"),
                row("订单查询_反向", "订单管理/订单查询", "1. 操作\n2. 再操作", "1. 失败", "需求 3.1\ncandidateIds=candidate-a")))));
    }

    @Test
    void rejectsDifferentStepAndExpectedResultCountsEvenWhenBothStartAtOne() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(frozenTarget(), result(List.of(), List.of(
                row("订单查询_正向", "订单管理/订单查询", "1. 操作\n2. 再操作", "1. 成功", "需求 3.1\ncandidateIds=candidate-a"),
                row("订单查询_反向", "订单管理/订单查询", "1. 操作", "1. 失败", "依据通用经验，待确认")))));
    }

    @Test
    void rejectsMixedOrMisspelledGeneralExperienceContent() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(frozenTarget(), result(List.of(), List.of(
                row("订单查询_正向", "订单管理/订单查询", "1. 操作", "1. 成功", "需求 3.1；依据通用经验，待确认"),
                row("订单查询_反向", "订单管理/订单查询", "1. 操作", "1. 失败", "依据通用经验，待确认")))));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(frozenTarget(), result(List.of(), List.of(
                row("订单查询_正向", "订单管理/订单查询", "1. 操作", "1. 成功", "需求 3.1\ncandidateIds=candidate-a"),
                row("订单查询_反向", "订单管理/订单查询", "1. 操作", "1. 失败", "依据通用经验")))));
    }

    @Test
    void rejectsFormalRequirementContentWithoutAnExactFrozenCandidateReference() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(frozenTarget(), result(List.of(), List.of(
                row("订单查询_正向", "订单管理/订单查询", "1. 操作", "1. 成功", "需求原文摘要"),
                row("订单查询_反向", "订单管理/订单查询", "1. 操作", "1. 失败", "依据通用经验，待确认")))));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(frozenTarget(), result(List.of(), List.of(
                row("订单查询_正向", "订单管理/订单查询", "1. 操作", "1. 成功", "需求原文摘要\ncandidateIds=example-good"),
                row("订单查询_反向", "订单管理/订单查询", "1. 操作", "1. 失败", "依据通用经验，待确认")))));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(frozenTarget(), result(List.of(), List.of(
                row("订单查询_正向", "订单管理/订单查询", "1. 操作", "1. 成功", "需求原文摘要\ncandidateIds=candidate-a,candidate-a"),
                row("订单查询_反向", "订单管理/订单查询", "1. 操作", "1. 失败", "依据通用经验，待确认")))));
    }

    @Test
    void acceptsAuditRowsOnlyWhenTheyReferenceTheCurrentFrozenCandidateSet() {
        assertDoesNotThrow(() -> validator.validate(frozenTarget(), result(List.of(
                new MarkdownAuditRow(1, "订单查询", "未发现问题", "candidateIds=candidate-a; documentId=document-1; unitId=unit-1")), List.of(
                row("订单查询_正向", "订单管理/订单查询", "1. 操作", "1. 成功", "需求 3.1\ncandidateIds=candidate-a"),
                row("订单查询_反向", "订单管理/订单查询", "1. 操作", "1. 失败", "需求 3.1\ncandidateIds=candidate-a")))));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(frozenTarget(), result(List.of(
                new MarkdownAuditRow(1, "用户登录", "未发现问题", "candidateIds=other-candidate; documentId=document-2; unitId=unit-2")), List.of(
                row("订单查询_正向", "订单管理/订单查询", "1. 操作", "1. 成功", "需求 3.1\ncandidateIds=candidate-a"),
                row("订单查询_反向", "订单管理/订单查询", "1. 操作", "1. 失败", "需求 3.1\ncandidateIds=candidate-a")))));
    }

    private static FrozenFeatureTarget frozenTarget() {
        return new FrozenFeatureTarget("frozen-order-query", 1, "订单管理/订单查询", true,
                new FrozenFeatureSource("conclusion-1", FeatureReviewConclusionType.MATCHED,
                        List.of("candidate-a", "candidate-b"), "已完成双向核对"));
    }

    private static MarkdownGenerationResult result(List<MarkdownAuditRow> audits, List<MarkdownTestCaseRow> cases) {
        return new MarkdownGenerationResult("already parsed", audits, cases);
    }

    private static MarkdownTestCaseRow row(
            String name, String module, String steps, String expected, String requirementContent) {
        return new MarkdownTestCaseRow(name, module, "已登录", steps, expected, requirementContent);
    }
}
