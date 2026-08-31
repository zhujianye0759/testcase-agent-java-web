package com.testcaseagent.structuredgeneration;

import static org.assertj.core.api.Assertions.assertThat;

import com.testcaseagent.featureaudit.MaterialInventoryDocument;
import com.testcaseagent.featureaudit.MaterialInventoryUnit;
import com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignV2Input;
import com.testcaseagent.knowledgeagent.RequirementFactExtractionV2Result.FactType;
import com.testcaseagent.knowledgeagent.StructuredSourceQuoteV2;
import com.testcaseagent.task.ApprovedFunctionScope;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** [Req-ID]: REQ-TGV2-003, REQ-TGV2-005, REQ-TGV2-006, REQ-TGV2-008 */
class V2GenerationPlannerTest {

    private final V2GenerationPlanner planner = new V2GenerationPlanner();

    @Test
    void plansStableSemanticWindowsWithExactTargetAndAdjacentContext() {
        MaterialInventoryDocument material = material(23);
        ApprovedFunctionScope.ApprovedFunction function = function("function-a");

        List<V2GenerationPlanner.FactWindow> first = planner.factWindows("task-a", function, material);
        List<V2GenerationPlanner.FactWindow> second = planner.factWindows("task-a", function, material);

        assertThat(first).hasSize(2).isEqualTo(second);
        assertThat(first).allSatisfy(window -> {
            assertThat(window.input().units()).hasSizeBetween(8, 16);
            assertThat(window.input().units()).extracting(unit -> unit.content())
                    .containsExactlyElementsOf(window.targetUnits().stream().map(MaterialInventoryUnit::content).toList());
            assertThat(window.targetUnits()).doesNotContainAnyElementsOf(window.contextUnits());
            assertThat(window.targetUnits().size() + window.contextUnits().size()).isLessThanOrEqualTo(32);
            assertThat(window.registration().identityKey()).isEqualTo(window.input().windowKey());
            assertThat(window.registration().materialDocumentId()).isEqualTo("document-a");
        });
        assertThat(first.stream().flatMap(window -> window.targetUnits().stream()).toList())
                .containsExactlyElementsOf(material.units());
    }

    @Test
    void boundedCursorPlanningMatchesTheCompleteFrozenInventoryForLargeMaterials() {
        MaterialInventoryDocument material = material(53);
        ApprovedFunctionScope.ApprovedFunction function = function("function-a");
        V2GenerationPlanner.MaterialDescriptor descriptor = new V2GenerationPlanner.MaterialDescriptor(
                material.documentId(), material.documentRole(), material.totalUnits(), 1, 53);
        List<V2GenerationPlanner.FactWindow> bounded = new ArrayList<>();
        int cursor = descriptor.firstOrdinal();

        while (cursor <= descriptor.lastOrdinal()) {
            int first = Math.max(descriptor.firstOrdinal(), cursor - 4);
            int last = Math.min(descriptor.lastOrdinal(), cursor + 19);
            List<MaterialInventoryUnit> neighborhood = material.units().subList(first - 1, last);
            V2GenerationPlanner.FactWindow window = planner.nextFactWindow(
                    "task-a", function, descriptor, neighborhood, cursor);
            bounded.add(window);
            cursor = window.registration().ordinalEnd() + 1;
        }

        assertThat(bounded).isEqualTo(planner.factWindows("task-a", function, material));
        assertThat(bounded.stream().flatMap(window -> window.targetUnits().stream()).toList())
                .containsExactlyElementsOf(material.units());
    }

    @Test
    void createsOneDynamicFormalPointPerAtomicFactRatherThanAFixedMultiplier() {
        ApprovedFunctionScope.ApprovedFunction function = function("function-a");
        List<V2GenerationPlanner.PersistedFact> facts = List.of(
                fact("fact-role", FactType.ROLE),
                fact("fact-input", FactType.INPUT),
                fact("fact-state", FactType.STATE_CHANGE));

        List<V2GenerationPlanner.TestPointPlan> points = planner.testPoints("task-a", function, facts);

        assertThat(points).hasSize(3);
        assertThat(points).extracting(point -> point.input().testPoint().type())
                .containsExactly(FunctionalTestcaseDesignV2Input.TestPointType.PERMISSION,
                        FunctionalTestcaseDesignV2Input.TestPointType.INPUT_VALIDATION,
                        FunctionalTestcaseDesignV2Input.TestPointType.STATE_TRANSITION);
        assertThat(points).allSatisfy(point -> {
            assertThat(point.input().testPoint().basis())
                    .isEqualTo(FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT);
            assertThat(point.input().requirementFacts()).hasSize(1);
            assertThat(point.registration().testPointKey()).isEqualTo(point.input().testPoint().testPointKey());
        });
    }

    @Test
    void capacitySplitKeepsEveryTargetOnceAndRecomputesAdjacentContext() {
        MaterialInventoryDocument material = material(23);
        V2GenerationPlanner.FactWindow root = planner.factWindows("task-a", function("function-a"), material).get(0);
        var persisted = new StructuredGenerationAcceptanceStore.MaterialWindowPlan(
                "parent-work", root.registration().identityKey(), "RUNNING",
                root.registration().ordinalStart(), root.registration().ordinalEnd(), "document-a",
                root.registration().allowedEvidenceKeys(), root.registration().contextEvidenceKeys(), null, 0);

        List<V2GenerationPlanner.FactWindow> children = planner.bisectFactWindow(
                "task-a", function("function-a"), material, persisted);

        assertThat(children).hasSize(2);
        assertThat(children.stream().flatMap(child -> child.targetUnits().stream()).toList())
                .containsExactlyElementsOf(root.targetUnits());
        assertThat(children).allSatisfy(child -> {
            assertThat(child.registration().parentWorkItemId()).isEqualTo("parent-work");
            assertThat(child.registration().splitDepth()).isEqualTo(1);
            assertThat(child.targetUnits()).doesNotContainAnyElementsOf(child.contextUnits());
            assertThat(child.targetUnits().size() + child.contextUnits().size()).isLessThanOrEqualTo(32);
        });
    }

    @Test
    void keepsGeneratingAPendingPointWhenNoFormalFactWasExtracted() {
        List<V2GenerationPlanner.TestPointPlan> points = planner.testPoints(
                "task-b", function("function-b"), List.of());

        assertThat(points).singleElement().satisfies(point -> {
            assertThat(point.input().testPoint().basis())
                    .isEqualTo(FunctionalTestcaseDesignV2Input.Basis.GENERAL_EXPERIENCE);
            assertThat(point.input().testPoint().missingInformation()).isNotEmpty();
            assertThat(point.input().requirementFacts()).isEmpty();
        });
    }

    private static ApprovedFunctionScope.ApprovedFunction function(String key) {
        return new ApprovedFunctionScope.ApprovedFunction(key, "提交申请", "业务/提交申请", "提交业务申请");
    }

    private static V2GenerationPlanner.PersistedFact fact(String key, FactType type) {
        return new V2GenerationPlanner.PersistedFact(key, type, "已审核的原子事实", List.of(
                new StructuredSourceQuoteV2("unit-1", "连续原文")));
    }

    private static MaterialInventoryDocument material(int count) {
        List<MaterialInventoryUnit> units = java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(index -> new MaterialInventoryUnit("document-a", "WORK_ORDER_PLAN", "unit-" + index,
                        index - 1, index, "第" + index + "项正式需求内容", index * 10L, index * 10L + 5))
                .toList();
        return new MaterialInventoryDocument("document-a", "document-a", "WORK_ORDER_PLAN", count, true, units);
    }
}
