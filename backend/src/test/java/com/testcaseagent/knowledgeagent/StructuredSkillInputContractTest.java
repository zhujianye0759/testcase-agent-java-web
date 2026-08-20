package com.testcaseagent.knowledgeagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Contract tests for the three fixed structured Skill inputs. [Req-ID]: REQ-SKI-003 */
class StructuredSkillInputContractTest {

    /** [Req-ID]: REQ-SKI-003 */
    @Test
    void preservesAContinuousGlobalMaterialSliceWithoutRenumbering() {
        List<RequirementMaterialQualityReviewInput.MaterialUnit> units = new ArrayList<>();
        for (int ordinal = 33; ordinal <= 64; ordinal++) {
            units.add(new RequirementMaterialQualityReviewInput.MaterialUnit("unit-" + ordinal, ordinal, "内容"));
        }
        RequirementMaterialQualityReviewInput input = new RequirementMaterialQualityReviewInput("material-1",
                MaterialContentTypeKey.REQUIREMENTS_SPEC, "需求说明", units);

        assertThat(input.units()).extracting(RequirementMaterialQualityReviewInput.MaterialUnit::ordinal)
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(33, 64).boxed().toList());
    }

    /** [Req-ID]: REQ-SKI-003 */
    @Test
    void rejectsMaterialSliceGapsAndDuplicateKeys() {
        assertThatThrownBy(() -> new RequirementMaterialQualityReviewInput("material-1",
                MaterialContentTypeKey.REQUIREMENTS_SPEC, "需求说明", List.of(
                        new RequirementMaterialQualityReviewInput.MaterialUnit("unit-33", 33, "内容一"),
                        new RequirementMaterialQualityReviewInput.MaterialUnit("unit-35", 35, "内容二"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeatureScopeReconciliationInput(List.of(
                new FeatureScopeReconciliationInput.FunctionListItem("item-1", "功能", "描述", List.of("evidence-1")),
                new FeatureScopeReconciliationInput.FunctionListItem("item-1", "功能2", "描述", List.of("evidence-2"))), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** [Req-ID]: REQ-SKI-003 */
    @Test
    void countsKeyBoundsAsUnicodeCharactersRatherThanUtf8Bytes() {
        String chineseKey = "键".repeat(100);

        assertThat(new RequirementMaterialQualityReviewInput.MaterialUnit(chineseKey, 1, "内容").unitKey())
                .isEqualTo(chineseKey);
        assertThatThrownBy(() -> new RequirementMaterialQualityReviewInput.MaterialUnit("k".repeat(129), 1, "内容"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** [Req-ID]: REQ-SKI-003 */
    @Test
    void serializesTheFrozenFeatureAndTestPointEnumsAndFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        FeatureScopeReconciliationInput reconciliation = new FeatureScopeReconciliationInput(
                List.of(new FeatureScopeReconciliationInput.FunctionListItem(
                        "item-1", "订单/提交", "提交订单", List.of("evidence-1"))),
                List.of(new FeatureScopeReconciliationInput.RequirementFact(
                        "fact-1", "提交订单", List.of("evidence-1"))));
        FunctionalTestcaseDesignInput testcase = new FunctionalTestcaseDesignInput("function-1", "提交订单",
                new FunctionalTestcaseDesignInput.TestPoint("point-1",
                        FunctionalTestcaseDesignInput.TestPointType.DEPENDENCY_FAILURE, "支付网关失败",
                        List.of("fact-1"), List.of("evidence-1"),
                        FunctionalTestcaseDesignInput.Basis.GENERAL_EXPERIENCE, List.of("缺少超时阈值")));

        assertThat(mapper.writeValueAsString(reconciliation))
                .contains("\"function_list_items\"", "\"requirement_facts\"", "\"item_key\"", "\"evidence_keys\"");
        assertThat(mapper.writeValueAsString(testcase))
                .contains("\"test_point_key\":\"point-1\"", "\"type\":\"dependency_failure\"",
                        "\"basis\":\"general_experience\"", "\"missing_information\"");
    }
}
