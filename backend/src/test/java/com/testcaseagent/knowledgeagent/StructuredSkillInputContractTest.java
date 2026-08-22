package com.testcaseagent.knowledgeagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Contract tests for the three fixed structured Skill inputs. [Req-ID]: REQ-SKI-003 */
class StructuredSkillInputContractTest {

    /** [Req-ID]: REQ-SKI-003 */
    @Test
    void preservesAContinuousGlobalMaterialSliceWithoutRenumbering() {
        RequirementMaterialQualityReviewInput input = new RequirementMaterialQualityReviewInput("material-1",
                MaterialContentTypeKey.REQUIREMENTS_SPEC, "需求说明", List.of(
                        new RequirementMaterialQualityReviewInput.MaterialUnit("unit-33", 33, "内容一"),
                        new RequirementMaterialQualityReviewInput.MaterialUnit("unit-34", 34, "内容二")));

        assertThat(input.units()).extracting(RequirementMaterialQualityReviewInput.MaterialUnit::ordinal).containsExactly(33, 34);
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
}
