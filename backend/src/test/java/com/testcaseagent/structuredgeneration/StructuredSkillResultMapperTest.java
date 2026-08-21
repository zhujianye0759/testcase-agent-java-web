package com.testcaseagent.structuredgeneration;

import static org.assertj.core.api.Assertions.assertThat;

import com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignResult;
import com.testcaseagent.knowledgeagent.RequirementMaterialQualityReviewResult;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Proves DTO-to-business mapping does not silently drop accepted fields. [Req-ID]: REQ-STG-001 */
class StructuredSkillResultMapperTest {

    @Test
    void retainsAllRequirementFactFields() {
        var fact = new RequirementMaterialQualityReviewResult.RequirementFact(
                "fact-1", "提交订单", List.of("下单人"), List.of("购物车有效"), List.of("订单"),
                List.of("金额大于零"), List.of("订单号"), List.of("下单权限"), List.of("草稿到已提交"),
                List.of("库存不足"), List.of("库存服务"), List.of("evidence-1"));

        var mapped = StructuredSkillResultMapper.review(
                new RequirementMaterialQualityReviewResult(List.of(fact), List.of())).requirementFacts().get(0);

        assertThat(mapped.roles()).containsExactly("下单人");
        assertThat(mapped.triggerConditions()).containsExactly("购物车有效");
        assertThat(mapped.inputs()).containsExactly("订单");
        assertThat(mapped.businessRules()).containsExactly("金额大于零");
        assertThat(mapped.outputs()).containsExactly("订单号");
        assertThat(mapped.permissions()).containsExactly("下单权限");
        assertThat(mapped.stateChanges()).containsExactly("草稿到已提交");
        assertThat(mapped.exceptionHandling()).containsExactly("库存不足");
        assertThat(mapped.externalDependencies()).containsExactly("库存服务");
        assertThat(mapped.evidenceKeys()).containsExactly("evidence-1");
    }

    @Test
    void retainsEveryTestcaseStepReferenceStatusAndMissingInformation() {
        var result = new FunctionalTestcaseDesignResult("function-1", "point-1", List.of(
                new FunctionalTestcaseDesignResult.Testcase("case-1", "候选", List.of("已登录"),
                        List.of(new FunctionalTestcaseDesignResult.Step(1, "提交", "提示待确认")),
                        List.of("fact-1"), List.of("evidence-1"),
                        FunctionalTestcaseDesignResult.CaseStatus.PENDING_CONFIRMATION, List.of("上限未说明"))));

        var mapped = StructuredSkillResultMapper.testcases(result).testcases().get(0);

        assertThat(mapped.preconditions()).containsExactly("已登录");
        assertThat(mapped.steps()).singleElement().satisfies(step -> {
            assertThat(step.action()).isEqualTo("提交");
            assertThat(step.expected()).isEqualTo("提示待确认");
        });
        assertThat(mapped.requirementFactKeys()).containsExactly("fact-1");
        assertThat(mapped.evidenceKeys()).containsExactly("evidence-1");
        assertThat(mapped.missingInformation()).containsExactly("上限未说明");
    }

    @Test
    void retainsEveryFrozenReviewAndHighGranularityTestcaseField() {
        var finding = new RequirementMaterialQualityReviewResult.ReviewFinding("finding-1",
                RequirementMaterialQualityReviewResult.RootCauseKind.MISSING_EXCEPTION_HANDLING, "异常处理缺失",
                new RequirementMaterialQualityReviewResult.AffectedScope(List.of("unit-1"), "登录异常范围"),
                new RequirementMaterialQualityReviewResult.BadSourceExample("unit-1", "材料未说明异常处理"),
                new RequirementMaterialQualityReviewResult.ProposedGoodExample(
                        RequirementMaterialQualityReviewResult.ProposalStatus.PENDING_CONFIRMATION,
                        "待需求方确认：补充异常处理规则"),
                "需要补充异常处理", List.of("unit-1"), "影响异常用例", "本项目待确认",
                "设计中心补充规范", RequirementMaterialQualityReviewResult.HandlingLevel.CONTINUE_INCOMPLETE);
        var mappedFinding = StructuredSkillResultMapper.review(
                new RequirementMaterialQualityReviewResult(List.of(), List.of(finding))).reviewFindings().get(0);
        assertThat(mappedFinding.rootCauseKind().name()).isEqualTo("MISSING_EXCEPTION_HANDLING");
        assertThat(mappedFinding.affectedScope().summary()).isEqualTo("登录异常范围");
        assertThat(mappedFinding.badSourceExample().quote()).isEqualTo("材料未说明异常处理");
        assertThat(mappedFinding.proposedGoodExample().text()).contains("待需求方确认");

        var testcase = new FunctionalTestcaseDesignResult.Testcase("case-1", "登录正常场景", "账号登录",
                FunctionalTestcaseDesignResult.Priority.HIGH, List.of("已注册用户"),
                new FunctionalTestcaseDesignResult.Initialization(List.of("办公电脑"), List.of("浏览器"),
                        List.of("测试环境"), List.of("账号参数")),
                List.of(new FunctionalTestcaseDesignResult.Input("账号", FunctionalTestcaseDesignResult.InputNature.VALID,
                        FunctionalTestcaseDesignResult.InputSource.MANUAL,
                        FunctionalTestcaseDesignResult.TestMethod.EQUIVALENCE_PARTITIONING,
                        FunctionalTestcaseDesignResult.Authenticity.SIMULATED, "先输入账号")),
                List.of(new FunctionalTestcaseDesignResult.Step(1, "提交账号", "进入首页", "实际结果满足本步骤预期结果。",
                        FunctionalTestcaseDesignResult.GenericClauses.TERMINATION_OR_ERROR,
                        "记录实际结果、提示信息及必要证据。")), List.of("进入首页"),
                "满足前提和约束且未触发终止条件，逐步执行并记录结果。", "全部预期结果满足则通过，任一不满足则不通过。",
                List.of("系统服务终止"), "记录实际结果、提示信息及必要证据。",
                new FunctionalTestcaseDesignResult.AuthoringInformation("测试人员", "2026-08-22"),
                List.of("fact-1"), List.of("unit-1"), FunctionalTestcaseDesignResult.CaseStatus.FORMAL, List.of());
        var mappedCase = StructuredSkillResultMapper.testcases(
                new FunctionalTestcaseDesignResult("function-1", "point-1", List.of(testcase))).testcases().get(0);
        assertThat(mappedCase.name()).isEqualTo("登录正常场景");
        assertThat(mappedCase.priority().name()).isEqualTo("HIGH");
        assertThat(mappedCase.initialization().hardwareConfiguration()).containsExactly("办公电脑");
        assertThat(mappedCase.inputs()).singleElement().satisfies(input -> {
            assertThat(input.content()).isEqualTo("账号");
            assertThat(input.method().name()).isEqualTo("EQUIVALENCE_PARTITIONING");
        });
        assertThat(mappedCase.steps()).singleElement().satisfies(step ->
                assertThat(step.evaluationCriteria()).isEqualTo("实际结果满足本步骤预期结果。"));
        assertThat(mappedCase.expectedResults()).containsExactly("进入首页");
        assertThat(mappedCase.authoringInformation().author()).isEqualTo("测试人员");
    }
}
