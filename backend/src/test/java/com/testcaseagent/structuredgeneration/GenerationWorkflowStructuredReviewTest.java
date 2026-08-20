package com.testcaseagent.structuredgeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.testcaseagent.featureaudit.MaterialInventoryUnit;
import com.testcaseagent.knowledgeagent.MaterialContentTypeKey;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Proves bounded material slicing without changing parsed-unit global ordinals. [Req-ID]: REQ-SKI-003, REQ-STG-002 */
class GenerationWorkflowStructuredReviewTest {

    @Test
    void slicesAtThirtyTwoAndPreservesGlobalOrdinals() {
        List<MaterialInventoryUnit> units = units(1, 65);

        var slices = new StructuredMaterialSlicePlanner().plan(
                "material-1", MaterialContentTypeKey.REQUIREMENTS_SPEC, "需求规格说明书", units);

        assertThat(slices).hasSize(3);
        assertThat(slices.get(0).units()).extracting(unit -> unit.ordinal()).containsExactlyElementsOf(range(1, 32));
        assertThat(slices.get(1).units()).extracting(unit -> unit.ordinal()).containsExactlyElementsOf(range(33, 64));
        assertThat(slices.get(2).units()).extracting(unit -> unit.ordinal()).containsExactly(65);
        assertThat(slices.get(1).units()).extracting(unit -> unit.unitKey()).startsWith("unit-33");
    }

    @Test
    void acceptsANonFirstSliceButRejectsAGapInsteadOfRenumberingIt() {
        var planner = new StructuredMaterialSlicePlanner();
        assertThat(planner.plan("material-1", MaterialContentTypeKey.WORK_ORDER_PLAN, "工单方案", units(33, 64)))
                .singleElement().satisfies(slice -> assertThat(slice.units())
                        .extracting(unit -> unit.ordinal()).containsExactlyElementsOf(range(33, 64)));

        List<MaterialInventoryUnit> broken = List.of(unit(33), unit(35));
        assertThatThrownBy(() -> planner.plan(
                "material-1", MaterialContentTypeKey.WORK_ORDER_PLAN, "工单方案", broken))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("continuous");
    }

    private static List<MaterialInventoryUnit> units(int first, int last) {
        return IntStream.rangeClosed(first, last).mapToObj(GenerationWorkflowStructuredReviewTest::unit).toList();
    }

    private static List<Integer> range(int first, int last) {
        return IntStream.rangeClosed(first, last).boxed().toList();
    }

    private static MaterialInventoryUnit unit(int ordinal) {
        return new MaterialInventoryUnit("document-1", "REQUIREMENTS_SPEC", "unit-" + ordinal,
                ordinal - 1, ordinal, "content-" + ordinal, ordinal * 10L, ordinal * 10L + 5);
    }
}
