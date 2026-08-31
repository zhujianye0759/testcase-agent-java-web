package com.testcaseagent.structuredgeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testcaseagent.knowledgeagent.FeatureScopeReconciliationPageInput;
import com.testcaseagent.knowledgeagent.FeatureScopeReconciliationPageResult;
import com.testcaseagent.knowledgeagent.FeatureScopeReconciliationResult;
import com.testcaseagent.knowledgeagent.FeatureScopeReconciliationV2Canonicalizer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Independent Java verification of KEE-derived V2 page identities and global closure. */
class StructuredReconciliationV2ValidatorTest {

    /** [Req-ID]: REQ-FSC-008 */
    @Test
    void recomputesRelationOwnerKeyEvidenceAndPublishesOneTerminalPerSource() {
        var plan = plan("同一功能", "同一功能");
        var window = plan.ownerWindows().get(0);
        var refs = window.ownerSourceRefs();
        var relation = relation(plan.runKey(), refs, List.of("evidence-fact", "evidence-item"));
        var result = result(plan, window, List.of(relation));
        var validator = new StructuredReconciliationV2Validator(new ObjectMapper());

        var stage = validator.validatePage(plan, window, result);
        var publication = validator.validateRun(plan, List.of(stage));

        assertThat(publication.catalogSources()).hasSize(2);
        assertThat(publication.relations()).containsExactly(stage.relations().get(0));
        assertThat(publication.acceptedResultSha256()).matches("[0-9a-f]{64}");
    }

    /** [Req-ID]: REQ-FSC-008 */
    @Test
    void rejectsEvidenceThatIsNotTheExactReferencedSourceUnion() {
        var plan = plan("同一功能", "同一功能");
        var window = plan.ownerWindows().get(0);
        var relation = relation(plan.runKey(), window.ownerSourceRefs(), List.of("evidence-item"));
        var validator = new StructuredReconciliationV2Validator(new ObjectMapper());

        assertThatThrownBy(() -> validator.validatePage(plan, window, result(plan, window, List.of(relation))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidence");
    }

    /** [Req-ID]: REQ-FSC-008 */
    @Test
    void permitsOverlappingRelationsWhenNoExactPathClosureIsDuplicated() {
        var plan = plan("功能甲", "需求乙");
        var window = plan.ownerWindows().get(0);
        var both = window.ownerSourceRefs();
        var itemOnly = List.of(both.get(0));
        var first = relation(plan.runKey(), both, List.of("evidence-fact", "evidence-item"),
                FeatureScopeReconciliationResult.Classification.CONFLICT,
                FeatureScopeReconciliationResult.ConfirmationStatus.PENDING_CONFIRMATION);
        var second = relation(plan.runKey(), itemOnly, List.of("evidence-item"),
                FeatureScopeReconciliationResult.Classification.FUNCTION_LIST_ONLY,
                FeatureScopeReconciliationResult.ConfirmationStatus.CONFIRMED);
        var validator = new StructuredReconciliationV2Validator(new ObjectMapper());

        var publication = validator.validateRun(plan,
                List.of(validator.validatePage(plan, window, result(plan, window, List.of(first, second)))));

        assertThat(publication.relations()).hasSize(2);
        assertThat(publication.catalogSources()).hasSize(2);
    }

    /** [Req-ID]: REQ-FSC-008 */
    @Test
    void acceptedHashChangesWhenValidatedReaderFacingPageContentChanges() {
        var plan = plan("同一功能", "同一功能");
        var window = plan.ownerWindows().get(0);
        var refs = window.ownerSourceRefs();
        var first = relation(plan.runKey(), refs, List.of("evidence-fact", "evidence-item"), "按来源闭合");
        var second = relation(plan.runKey(), refs, List.of("evidence-fact", "evidence-item"), "保持正式范围");
        var validator = new StructuredReconciliationV2Validator(new ObjectMapper());

        String firstHash = validator.validateRun(plan,
                List.of(validator.validatePage(plan, window, result(plan, window, List.of(first)))))
                .acceptedResultSha256();
        String secondHash = validator.validateRun(plan,
                List.of(validator.validatePage(plan, window, result(plan, window, List.of(second)))))
                .acceptedResultSha256();

        assertThat(first.reconciliationKey()).isEqualTo(second.reconciliationKey());
        assertThat(firstHash).isNotEqualTo(secondHash);
    }

    private static StructuredReconciliationV2Planner.RunPlan plan(String itemPath, String factFunction) {
        var item = new StructuredGenerationAcceptanceStore.AcceptedFunctionItem(
                "item-1", itemPath, "功能说明", List.of("evidence-item"));
        var fact = new StructuredGenerationAcceptanceStore.AcceptedFact(
                "fact-1", factFunction, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of("evidence-fact"), Map.of("evidence-fact", "证据"));
        return new StructuredReconciliationV2Planner().plan("task-1",
                new StructuredGenerationAcceptanceStore.AcceptedInputs(List.of(fact), List.of(item)));
    }

    private static FeatureScopeReconciliationPageResult result(
            StructuredReconciliationV2Planner.RunPlan plan,
            FeatureScopeReconciliationPageInput.OwnerWindow window,
            List<FeatureScopeReconciliationPageResult.Reconciliation> relations) {
        return new FeatureScopeReconciliationPageResult("reconcile_page", "2", plan.runKey(), window.pageKey(),
                window.ownerSourceRefs(), relations);
    }

    private static FeatureScopeReconciliationPageResult.Reconciliation relation(String runKey,
            List<FeatureScopeReconciliationPageInput.SourceRef> refs, List<String> evidence) {
        return relation(runKey, refs, evidence, FeatureScopeReconciliationResult.Classification.EXACT_MATCH,
                FeatureScopeReconciliationResult.ConfirmationStatus.CONFIRMED);
    }

    private static FeatureScopeReconciliationPageResult.Reconciliation relation(String runKey,
            List<FeatureScopeReconciliationPageInput.SourceRef> refs, List<String> evidence,
            String scopeRecommendation) {
        List<String> itemKeys = refs.stream()
                .filter(ref -> ref.sourceType() == FeatureScopeReconciliationPageInput.SourceType.FUNCTION_LIST_ITEM)
                .map(FeatureScopeReconciliationPageInput.SourceRef::sourceKey).toList();
        List<String> factKeys = refs.stream()
                .filter(ref -> ref.sourceType() == FeatureScopeReconciliationPageInput.SourceType.REQUIREMENT_FACT)
                .map(FeatureScopeReconciliationPageInput.SourceRef::sourceKey).toList();
        return new FeatureScopeReconciliationPageResult.Reconciliation(
                FeatureScopeReconciliationV2Canonicalizer.reconciliationKey(runKey,
                        FeatureScopeReconciliationResult.Classification.EXACT_MATCH,
                        FeatureScopeReconciliationResult.ConfirmationStatus.CONFIRMED, refs),
                refs.get(0), itemKeys, factKeys, FeatureScopeReconciliationResult.Classification.EXACT_MATCH,
                evidence, scopeRecommendation, FeatureScopeReconciliationResult.ConfirmationStatus.CONFIRMED);
    }

    private static FeatureScopeReconciliationPageResult.Reconciliation relation(String runKey,
            List<FeatureScopeReconciliationPageInput.SourceRef> refs, List<String> evidence,
            FeatureScopeReconciliationResult.Classification classification,
            FeatureScopeReconciliationResult.ConfirmationStatus confirmation) {
        List<String> itemKeys = refs.stream()
                .filter(ref -> ref.sourceType() == FeatureScopeReconciliationPageInput.SourceType.FUNCTION_LIST_ITEM)
                .map(FeatureScopeReconciliationPageInput.SourceRef::sourceKey).toList();
        List<String> factKeys = refs.stream()
                .filter(ref -> ref.sourceType() == FeatureScopeReconciliationPageInput.SourceType.REQUIREMENT_FACT)
                .map(FeatureScopeReconciliationPageInput.SourceRef::sourceKey).toList();
        return new FeatureScopeReconciliationPageResult.Reconciliation(
                FeatureScopeReconciliationV2Canonicalizer.reconciliationKey(runKey, classification, confirmation, refs),
                refs.get(0), itemKeys, factKeys, classification, evidence, "按来源闭合", confirmation);
    }
}
