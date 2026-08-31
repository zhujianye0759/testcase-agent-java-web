package com.testcaseagent.structuredgeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testcaseagent.knowledgeagent.FeatureScopeReconciliationPageInput;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Deterministic global-catalog planning tests. [Req-ID]: REQ-FSC-008 */
class StructuredReconciliationV2PlannerTest {

    private final StructuredReconciliationV2Planner planner = new StructuredReconciliationV2Planner();

    /** [Req-ID]: REQ-FSC-008 */
    @Test
    void keepsTheComplete297By514CatalogVisibleWhilePartitioningEveryOwnerExactlyOnce() throws Exception {
        var plan = planner.plan("task-large", accepted(297, 514));

        assertThat(plan.catalog().functionListItems()).hasSize(297);
        assertThat(plan.catalog().requirementFacts()).hasSize(514);
        assertThat(plan.ownerWindows()).hasSize(9);
        assertThat(plan.ownerWindows()).allSatisfy(window -> assertThat(window.ownerSourceRefs()).hasSizeLessThanOrEqualTo(100));
        assertThat(plan.catalogSha256()).matches("[0-9a-f]{64}");
        assertThat(plan.runKey()).matches("[0-9a-f]{64}");

        List<FeatureScopeReconciliationPageInput.SourceRef> owners = plan.ownerWindows().stream()
                .flatMap(window -> window.ownerSourceRefs().stream()).toList();
        assertThat(owners).hasSize(811);
        assertThat(new LinkedHashSet<>(owners)).hasSize(811);
        assertThat(owners).isSorted();

        for (var window : plan.ownerWindows()) {
            String ownerJson = new ObjectMapper().writeValueAsString(window.ownerSourceRefs());
            String bytes = "reconcile-page-v2\n" + plan.runKey() + "\n" + ownerJson;
            assertThat(window.pageKey()).isEqualTo(sha256(bytes));
            assertThat(window.pageKey()).isNotEqualTo(sha256(bytes + "\n"));
        }
    }

    /** [Req-ID]: REQ-FSC-008 */
    @Test
    void deterministicSplitPreservesTheSameRunCatalogAndExactOwnerPartition() {
        var plan = planner.plan("task-split", accepted(150, 75));
        var parent = plan.ownerWindows().get(0);

        var children = planner.bisect(plan, parent);

        assertThat(children).hasSize(2);
        assertThat(children.get(0).ownerSourceRefs()).containsExactlyElementsOf(
                parent.ownerSourceRefs().subList(0, parent.ownerSourceRefs().size() / 2));
        assertThat(children.get(1).ownerSourceRefs()).containsExactlyElementsOf(
                parent.ownerSourceRefs().subList(parent.ownerSourceRefs().size() / 2,
                        parent.ownerSourceRefs().size()));
        assertThat(children).extracting(FeatureScopeReconciliationPageInput.OwnerWindow::pageKey)
                .doesNotHaveDuplicates();
    }

    /** [Req-ID]: REQ-FSC-008 */
    @Test
    void rejectsPersistedDuplicateEvidenceInsteadOfHidingCatalogCorruption() {
        var item = new StructuredGenerationAcceptanceStore.AcceptedFunctionItem(
                "item-1", "路径", "说明", List.of("evidence-1", "evidence-1"));
        var fact = fact(0);

        assertThatThrownBy(() -> planner.plan("task-duplicate-evidence",
                new StructuredGenerationAcceptanceStore.AcceptedInputs(List.of(fact), List.of(item))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique");
    }

    private static StructuredGenerationAcceptanceStore.AcceptedInputs accepted(int itemCount, int factCount) {
        List<StructuredGenerationAcceptanceStore.AcceptedFunctionItem> items = new ArrayList<>();
        for (int index = itemCount - 1; index >= 0; index--) {
            items.add(new StructuredGenerationAcceptanceStore.AcceptedFunctionItem(
                    "item-%04d".formatted(index), "功能/%04d".formatted(index), "说明 %04d".formatted(index),
                    List.of("item-evidence-%04d".formatted(index))));
        }
        List<StructuredGenerationAcceptanceStore.AcceptedFact> facts = new ArrayList<>();
        for (int index = factCount - 1; index >= 0; index--) facts.add(fact(index));
        return new StructuredGenerationAcceptanceStore.AcceptedInputs(facts, items);
    }

    private static StructuredGenerationAcceptanceStore.AcceptedFact fact(int index) {
        String evidence = "fact-evidence-%04d".formatted(index);
        return new StructuredGenerationAcceptanceStore.AcceptedFact(
                "fact-%04d".formatted(index), "需求/%04d".formatted(index),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(evidence), Map.of(evidence, "证据 %04d".formatted(index)));
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
