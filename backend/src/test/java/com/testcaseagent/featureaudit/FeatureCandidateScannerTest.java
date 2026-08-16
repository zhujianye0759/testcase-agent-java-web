package com.testcaseagent.featureaudit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * [Req-ID]: REQ-BFA-001, REQ-BFA-004
 *
 * <p>Exercises the bounded scanner seam without an HTTP, Office, or example dependency.</p>
 */
class FeatureCandidateScannerTest {

    private final FeatureCandidateScanner scanner = new FeatureCandidateScanner();

    @Test
    void preservesEveryRepeatedVisibleSequenceAndTextAsItsOwnSourceOccurrence() {
        MaterialInventoryUnit unit = functionUnit();

        FeatureCandidateScanResult result = scanner.accept(unit, 1, response("""
                | 113 | 订单查询 | 功能项 | documentId=function-doc; unitId=unit-7 |
                | 113 | 订单查询 | 功能项 | documentId=function-doc; unitId=unit-7 |
                """));

        assertThat(result.converged()).isTrue();
        assertThat(result.candidates()).extracting(FeatureSourceCandidate::modelSequence).containsExactly(113, 113);
        assertThat(result.candidates()).extracting(FeatureSourceCandidate::featureText)
                .containsExactly("订单查询", "订单查询");
        assertThat(result.candidates()).extracting(FeatureSourceCandidate::sourceRowPosition).containsExactly(1, 2);
        assertThat(result.candidates()).extracting(FeatureSourceCandidate::occurrenceId).doesNotHaveDuplicates();
        assertThat(result.candidates()).allSatisfy(candidate -> {
            assertThat(candidate.kind()).isEqualTo(FeatureCandidateKind.FUNCTION_LIST);
            assertThat(candidate.documentId()).isEqualTo("function-doc");
            assertThat(candidate.unitId()).isEqualTo("unit-7");
            assertThat(candidate.ordinal()).isEqualTo(4);
        });
    }

    @Test
    void promptContainsOnlyTheSuppliedUnitAndTheFixedContract() {
        String prompt = scanner.promptFor(functionUnit());

        assertThat(prompt).contains("documentId=function-doc", "unitId=unit-7", "每个非空候选行", "一个材料单元", "only unit text",
                "必须且只能四列", "第三列只能填写问题分类", "documentId=<exact>; unitId=<exact>; ",
                "不得用 <br> 紧接 unitId", "第二表必须为零数据行", "按列顺序将非空值组成一个业务路径", "不同层级值不是冲突", "标记证据不足");
        assertThat(prompt).doesNotContain("other document", "other unit");
    }

    @Test
    void acceptsEvidenceBodyOnlyAfterTheTwoSemicolonDelimitedCoordinates() {
        FeatureCandidateScanResult result = scanner.accept(functionUnit(), 1, response("""
                | 1 | 订单查询 | 功能项 | documentId=function-doc; unitId=unit-7; 明确的单元内证据 |
                """));

        assertThat(result.candidates()).singleElement()
                .extracting(FeatureSourceCandidate::evidenceText)
                .isEqualTo("documentId=function-doc; unitId=unit-7; 明确的单元内证据");
    }

    @Test
    void failsClosedForWrongRoleMissingEvidenceIdentityOrAnyTestCaseRow() {
        assertThatThrownBy(() -> scanner.accept(requirementUnit(), 1, response("")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("FUNCTION_LIST");
        assertThatThrownBy(() -> scanner.accept(functionUnit(), 1, response("""
                | 1 | 订单查询 | 功能项 | documentId=function-doc |
                """)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("documentId and unitId");
        assertThatThrownBy(() -> scanner.accept(functionUnit(), 1, response("""
                | 1 | 订单查询 | 功能项 | documentId=function-doc; unitId=unit-7 |

                ## 测试用例
                | 用例名称 | 功能模块 | 前提约束 | 执行步骤 | 预期结果 | 对应需求内容 |
                |---|---|---|---|---|---|
                | 不应存在 | 订单 | 无 | 1. 查询 | 成功 | documentId=function-doc; unitId=unit-7 |
                """)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("test-case rows");
    }

    @Test
    void failsClosedForRenamedOrExtraContractContent() {
        assertThatThrownBy(() -> scanner.accept(functionUnit(), 1, response("""
                | 1 | 订单查询 | 功能项 | documentId=function-doc; unitId=unit-7 |
                """).replace("## 测试用例", "## 用例")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("heading");
        assertThatThrownBy(() -> scanner.accept(functionUnit(), 1, response("""
                | 1 | 订单查询 | 功能项 | documentId=function-doc; unitId=unit-7 |
                """) + "说明文字"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("trailing content");
        assertThatThrownBy(() -> scanner.accept(functionUnit(), 1, response("""
                | 1 | 订单查询 | 功能项 | documentId=function-doc; unitId=unit-7 | 额外列 |
                """)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("column count");
        assertThatThrownBy(() -> scanner.accept(functionUnit(), 1, response("""
                | 1 | 订单查询 | 功能项 documentId=function-doc; unitId=unit-7; 证据 | |
                """)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("non-blank 证据对照");
    }

    @Test
    void rejectsPrefixMatchesDifferentCoordinatesAndMalformedCoordinateTokens() {
        assertThatThrownBy(() -> scanner.accept(functionUnit(), 1, response("""
                | 1 | 订单查询 | 功能项 | documentId=function-doc2; unitId=unit-7 |
                """)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exact documentId and unitId");
        assertThatThrownBy(() -> scanner.accept(functionUnit(), 1, response("""
                | 1 | 订单查询 | 功能项 | documentId=function-doc; unitId=unit-77 |
                """)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exact documentId and unitId");
        assertThatThrownBy(() -> scanner.accept(functionUnit(), 1, response("""
                | 1 | 订单查询 | 功能项 | documentId=function-doc; documentId=function-doc; unitId=unit-7 |
                """)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate or malformed");
        assertThatThrownBy(() -> scanner.accept(functionUnit(), 1, response("""
                | 1 | 订单查询 | 功能项 | documentId =function-doc; unitId=unit-7 |
                """)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate or malformed");
        assertThatThrownBy(() -> scanner.accept(functionUnit(), 1, response("""
                | 1 | 订单查询 | 功能项 | documentId=function-doc; unitId=unit-7<br>说明 |
                """)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("documentId and unitId");
    }

    private static MaterialInventoryUnit functionUnit() {
        return new MaterialInventoryUnit("function-doc", "FUNCTION_LIST", "unit-7", 0, 4, "only unit text", 0, 14);
    }

    private static MaterialInventoryUnit requirementUnit() {
        return new MaterialInventoryUnit("requirement-doc", "REQUIREMENT", "unit-r", 0, 1, "requirement", 0, 11);
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
