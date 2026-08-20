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
}
