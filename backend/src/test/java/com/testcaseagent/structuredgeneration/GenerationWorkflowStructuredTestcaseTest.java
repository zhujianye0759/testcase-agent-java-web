package com.testcaseagent.structuredgeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignInput;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Proves Java-owned, fact-driven test points without a fixed 2N count. [Req-ID]: REQ-STG-004, REQ-STG-005 */
class GenerationWorkflowStructuredTestcaseTest {

    @Test
    void createsOnlyTheTestPointsSupportedByEachFormalFact() {
        var planner = new StructuredTestPointPlanner();
        var fact = new StructuredTestPointPlanner.FormalFact(
                "fact-1", "创建订单", List.of("订单参数"), List.of("金额不得小于零"),
                List.of("订单管理员"), List.of("草稿变为已提交"), List.of("库存不足"),
                List.of("库存服务"), List.of("evidence-1"));

        var points = planner.plan(new StructuredTestPointPlanner.FunctionDefinition(
                "function-1", "创建订单", List.of(fact), List.of()));

        assertThat(points).hasSize(7);
        assertThat(points).extracting(input -> input.testPoint().type()).containsExactly(
                FunctionalTestcaseDesignInput.TestPointType.NORMAL_BEHAVIOR,
                FunctionalTestcaseDesignInput.TestPointType.INPUT_VALIDATION,
                FunctionalTestcaseDesignInput.TestPointType.BOUNDARY_VALUE,
                FunctionalTestcaseDesignInput.TestPointType.PERMISSION,
                FunctionalTestcaseDesignInput.TestPointType.STATE_TRANSITION,
                FunctionalTestcaseDesignInput.TestPointType.BUSINESS_EXCEPTION,
                FunctionalTestcaseDesignInput.TestPointType.DEPENDENCY_FAILURE);
        assertThat(points).allSatisfy(input -> {
            assertThat(input.testPoint().basis()).isEqualTo(FunctionalTestcaseDesignInput.Basis.FORMAL_REQUIREMENT);
            assertThat(input.testPoint().requirementFactKeys()).containsExactly("fact-1");
            assertThat(input.testPoint().evidenceKeys()).containsExactly("evidence-1");
        });
    }

    @Test
    void preservesEveryFormalFactValueWithStableOrderAndUniqueValueOwnedKeys() {
        var planner = new StructuredTestPointPlanner();
        var fact = new StructuredTestPointPlanner.FormalFact(
                "fact-1", "创建订单", List.of("校验客户", "校验金额"), List.of("金额不得小于零", "金额不得超过额度"),
                List.of("订单管理员", "财务复核员"), List.of("草稿变为已提交", "已提交变为已支付"),
                List.of("库存不足", "支付失败"), List.of("库存服务", "支付服务"), List.of("evidence-1"));
        var definition = new StructuredTestPointPlanner.FunctionDefinition(
                "function-1", "创建订单", List.of(fact), List.of());

        var first = planner.plan(definition);
        var second = planner.plan(definition);

        assertThat(first).hasSize(13);
        assertThat(first).extracting(input -> input.testPoint().description()).containsExactly(
                "创建订单",
                "校验客户", "校验金额",
                "金额不得小于零", "金额不得超过额度",
                "订单管理员", "财务复核员",
                "草稿变为已提交", "已提交变为已支付",
                "库存不足", "支付失败",
                "库存服务", "支付服务");
        assertThat(first).extracting(input -> input.testPoint().testPointKey()).doesNotHaveDuplicates();
        assertThat(first).extracting(input -> input.testPoint().testPointKey())
                .containsExactlyElementsOf(second.stream().map(input -> input.testPoint().testPointKey()).toList());
    }

    @Test
    void keepsGeneralExperiencePendingByRequiringExplicitMissingInformation() {
        var planner = new StructuredTestPointPlanner();
        var gap = new StructuredTestPointPlanner.ExperienceGap(
                FunctionalTestcaseDesignInput.TestPointType.BOUNDARY_VALUE,
                "补充验证未说明的最大长度", List.of("evidence-1"), List.of("需求未给出最大长度"));

        var point = planner.plan(new StructuredTestPointPlanner.FunctionDefinition(
                "function-1", "创建订单", List.of(), List.of(gap))).get(0).testPoint();

        assertThat(point.basis()).isEqualTo(FunctionalTestcaseDesignInput.Basis.GENERAL_EXPERIENCE);
        assertThat(point.requirementFactKeys()).isEmpty();
        assertThat(point.missingInformation()).containsExactly("需求未给出最大长度");

        assertThatThrownBy(() -> new StructuredTestPointPlanner.ExperienceGap(
                FunctionalTestcaseDesignInput.TestPointType.BOUNDARY_VALUE,
                "补充边界", List.of("evidence-1"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
