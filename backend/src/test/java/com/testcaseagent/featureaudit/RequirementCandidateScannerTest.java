package com.testcaseagent.featureaudit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * [Req-ID]: REQ-BFA-002
 *
 * <p>Proves the fixed two-pass requirement scan converges without treating repeated model rows as new evidence.</p>
 */
class RequirementCandidateScannerTest {

    private final RequirementCandidateScanner scanner = new RequirementCandidateScanner();

    @Test
    void acceptsOnlyNewSecondPassOccurrencesAndReportsConvergence() {
        MaterialInventoryUnit unit = requirementUnit();
        RequirementCandidateScanResult first = scanner.accept(unit, 1, List.of(), response("""
                | 1 | 提交订单 | 正常流程 | documentId=requirement-doc; unitId=unit-r |
                """));

        String secondPrompt = scanner.promptFor(unit, 2, first.candidates());
        RequirementCandidateScanResult second = scanner.accept(unit, 2, first.candidates(), response("""
                | 1 | 提交订单 | 正常流程 | documentId=requirement-doc; unitId=unit-r |
                | 2 | 取消订单 | 异常流程 | documentId=requirement-doc; unitId=unit-r |
                """));

        assertThat(first.converged()).isFalse();
        assertThat(second.converged()).isTrue();
        assertThat(second.candidates()).extracting(FeatureSourceCandidate::featureText).containsExactly("取消订单");
        assertThat(second.duplicateOccurrences()).extracting(FeatureSourceCandidate::featureText).containsExactly("提交订单");
        assertThat(second.candidates()).first().satisfies(candidate -> {
            assertThat(candidate.kind()).isEqualTo(FeatureCandidateKind.REQUIREMENT);
            assertThat(candidate.passNumber()).isEqualTo(2);
            assertThat(candidate.sourceRowPosition()).isEqualTo(2);
        });
        assertThat(secondPrompt).contains("提交订单", "documentId=requirement-doc", "unitId=unit-r",
                "必须且只能四列", "第三列只能填写问题分类", "documentId=<exact>; unitId=<exact>; ",
                "不得用 <br> 紧接 unitId", "第二表必须为零数据行");
    }

    @Test
    void acceptsAnExplicitZeroRowSecondPassAsConverged() {
        MaterialInventoryUnit unit = requirementUnit();
        RequirementCandidateScanResult first = scanner.accept(unit, 1, List.of(), response("""
                | 1 | 提交订单 | 正常流程 | documentId=requirement-doc; unitId=unit-r |
                """));

        RequirementCandidateScanResult second = scanner.accept(unit, 2, first.candidates(), response(""));

        assertThat(second.converged()).isTrue();
        assertThat(second.candidates()).isEmpty();
        assertThat(second.duplicateOccurrences()).isEmpty();
    }

    @Test
    void rejectsThirdPassWrongRoleMalformedContractAndEvidenceOutsideTheUnit() {
        assertThatThrownBy(() -> scanner.promptFor(requirementUnit(), 3, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("two passes");
        assertThatThrownBy(() -> scanner.accept(functionUnit(), 1, List.of(), response("")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("WORK_ORDER_PLAN or REQUIREMENT");
        assertThatThrownBy(() -> scanner.accept(requirementUnit(), 1, List.of(), "```json\n{}\n```"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Markdown");
        assertThatThrownBy(() -> scanner.accept(requirementUnit(), 1, List.of(), response("""
                | 1 | 提交订单 | 正常流程 | documentId=requirement-doc; unitId=another-unit |
                """)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("documentId and unitId");
    }

    private static MaterialInventoryUnit requirementUnit() {
        return new MaterialInventoryUnit("requirement-doc", "REQUIREMENT", "unit-r", 2, 8, "payment only unit", 20, 37);
    }

    private static MaterialInventoryUnit functionUnit() {
        return new MaterialInventoryUnit("function-doc", "FUNCTION_LIST", "unit-f", 0, 1, "function", 0, 8);
    }

    private static String response(String auditRows) {
        return ("""
                ## 需求与功能清单审查发现
                | 序号 | 对象/功能点 | 问题分类 | 证据对照 |
                |---|---|---|---|
                """ + auditRows + """

                ## 测试用例
                | 用例名称 | 功能模块 | 前提约束 | 执行步骤 | 预期结果 | 对应需求内容 |
                |---|---|---|---|---|---|
                """).stripIndent();
    }
}
