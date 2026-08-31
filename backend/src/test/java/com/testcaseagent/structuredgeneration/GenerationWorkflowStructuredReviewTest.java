package com.testcaseagent.structuredgeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.testcaseagent.featureaudit.MaterialInventoryUnit;
import com.testcaseagent.knowledgeagent.MaterialContentTypeKey;
import com.testcaseagent.knowledgeagent.FunctionCandidateExtractionInput;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Proves semantic target planning without changing parsed-unit global ordinals. [Req-ID]: REQ-FTG-013 */
class GenerationWorkflowStructuredReviewTest {

    /** [Req-ID]: REQ-FTG-013 */
    @Test
    void plansNormalTargetsBetweenEightAndSixteenWithoutLeavingATinyTail() {
        List<MaterialInventoryUnit> units = units(1, 40);

        var windows = new StructuredMaterialSlicePlanner().planWindows(units);

        assertThat(windows).allSatisfy(window -> assertThat(window.targetUnits()).hasSizeBetween(8, 16));
        assertThat(windows.stream().flatMap(window -> window.targetUnits().stream()).map(MaterialInventoryUnit::ordinal))
                .containsExactlyElementsOf(range(1, 40));
        assertThat(windows).allSatisfy(window -> {
            assertThat(window.targetUnits().size() + window.contextUnits().size()).isLessThanOrEqualTo(32);
            assertThat(window.contextUnits()).extracting(MaterialInventoryUnit::unitId)
                    .doesNotContainAnyElementsOf(window.targetUnits().stream().map(MaterialInventoryUnit::unitId).toList());
        });
    }

    /** [Req-ID]: REQ-FTG-013 */
    @Test
    void prefersAHeadingBoundaryAndKeepsImmediateReadOnlyContext() {
        List<MaterialInventoryUnit> material = new java.util.ArrayList<>(units(1, 25));
        material.set(10, unit(11, "## 第二章 功能范围"));

        var windows = new StructuredMaterialSlicePlanner().planWindows(material);

        assertThat(windows.get(0).targetUnits()).extracting(MaterialInventoryUnit::ordinal)
                .containsExactlyElementsOf(range(1, 10));
        assertThat(windows.get(0).contextUnits()).extracting(MaterialInventoryUnit::ordinal)
                .containsExactly(11, 12, 13, 14);
        assertThat(windows.get(1).contextUnits()).extracting(MaterialInventoryUnit::ordinal)
                .containsExactly(7, 8, 9, 10);
    }

    /** [Req-ID]: REQ-FTG-013 */
    @Test
    void prefersTableAndFunctionPathBoundariesWithoutUsingFixedCounts() {
        List<MaterialInventoryUnit> tableMaterial = new java.util.ArrayList<>(units(1, 28));
        for (int index = 11; index <= 15; index++) {
            tableMaterial.set(index, unit(index + 1, "| 字段 " + index + " | 说明 |"));
        }
        List<MaterialInventoryUnit> functionMaterial = new java.util.ArrayList<>(units(1, 28));
        functionMaterial.set(13, unit(14, "功能路径：用户中心/账号登录"));

        var planner = new StructuredMaterialSlicePlanner();

        assertThat(planner.planWindows(tableMaterial).get(0).targetUnits())
                .extracting(MaterialInventoryUnit::ordinal)
                .containsExactlyElementsOf(range(1, 11));
        assertThat(planner.planWindows(functionMaterial).get(0).targetUnits())
                .extracting(MaterialInventoryUnit::ordinal)
                .containsExactlyElementsOf(range(1, 13));
    }

    /** [Req-ID]: REQ-FTG-013 */
    @Test
    void acceptsShortWholeMaterialsAndRecomputesContextAfterCapacitySplit() {
        var planner = new StructuredMaterialSlicePlanner();
        List<MaterialInventoryUnit> material = units(33, 39);
        var root = planner.planWindows(material).get(0);

        assertThat(root.targetUnits()).extracting(MaterialInventoryUnit::ordinal)
                .containsExactlyElementsOf(range(33, 39));

        List<StructuredMaterialSlicePlanner.PlannedWindow> children = planner.bisect(material, root);
        assertThat(children).hasSize(2);
        assertThat(children.get(0).targetUnits()).extracting(MaterialInventoryUnit::ordinal)
                .containsExactly(33, 34, 35);
        assertThat(children.get(0).contextUnits()).extracting(MaterialInventoryUnit::ordinal)
                .containsExactly(36, 37, 38, 39);
        assertThat(children.get(1).targetUnits()).extracting(MaterialInventoryUnit::ordinal)
                .containsExactly(36, 37, 38, 39);
        assertThat(children.get(1).contextUnits()).extracting(MaterialInventoryUnit::ordinal)
                .containsExactly(33, 34, 35);
    }

    /** [Req-ID]: REQ-SKI-003, REQ-FTG-013 */
    @Test
    void acceptsANonFirstSliceButRejectsAGapInsteadOfRenumberingIt() {
        var planner = new StructuredMaterialSlicePlanner();
        assertThat(planner.planWindows(units(33, 39)))
                .singleElement().satisfies(window -> assertThat(window.targetUnits())
                        .extracting(MaterialInventoryUnit::ordinal).containsExactlyElementsOf(range(33, 39)));

        List<MaterialInventoryUnit> broken = List.of(unit(33), unit(35));
        assertThatThrownBy(() -> planner.planWindows(broken))
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
        return unit(ordinal, "content-" + ordinal);
    }

    /** [Req-ID]: REQ-AFCE-001, REQ-AFCE-007 */
    @Test
    void candidateWindowsKeepEveryTargetOnceAndRecomputeIdentityAndContextAfterSplit() {
        var planner = new StructuredMaterialSlicePlanner();
        List<MaterialInventoryUnit> material = units(1, 32);
        FunctionCandidateExtractionInput root = planner.planCandidateExtraction(
                "task-candidate-planner", "material-1", "功能清单", material).get(0);
        var restored = planner.restoreWindow(material,
                root.units().stream().map(FunctionCandidateExtractionInput.Unit::unitKey).toList(),
                root.contextUnits().stream().map(FunctionCandidateExtractionInput.Unit::unitKey).toList());
        List<FunctionCandidateExtractionInput> children = planner.bisect(material, restored).stream()
                .map(window -> planner.candidateExtractionInput(
                        "task-candidate-planner", "material-1", "功能清单", window)).toList();

        assertThat(root.units().size() + root.contextUnits().size()).isLessThanOrEqualTo(32);
        assertThat(children).hasSize(2).allSatisfy(child -> {
            assertThat(child.units().size() + child.contextUnits().size()).isLessThanOrEqualTo(32);
            assertThat(child.contextUnits()).extracting(FunctionCandidateExtractionInput.Unit::unitKey)
                    .doesNotContainAnyElementsOf(child.units().stream()
                            .map(FunctionCandidateExtractionInput.Unit::unitKey).toList());
            assertThat(child.windowKey()).isNotEqualTo(root.windowKey());
        });
        assertThat(children.stream().flatMap(child -> child.units().stream())
                .map(FunctionCandidateExtractionInput.Unit::ordinal))
                .containsExactlyElementsOf(root.units().stream()
                        .map(FunctionCandidateExtractionInput.Unit::ordinal).toList());
    }

    private static MaterialInventoryUnit unit(int ordinal, String content) {
        return new MaterialInventoryUnit("document-1", "REQUIREMENTS_SPEC", "unit-" + ordinal,
                ordinal - 1, ordinal, content, ordinal * 10L, ordinal * 10L + 5);
    }
}
