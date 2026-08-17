package com.testcaseagent.featureaudit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.testcaseagent.scope.RequirementDocumentCoordinate;
import com.testcaseagent.scope.RequirementScope;
import com.testcaseagent.task.GenerationTaskRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Exercises the fail-closed, deterministic freeze boundary independently of batch generation.
 *
 * [Req-ID]: REQ-BFA-005
 */
class FrozenFeatureServiceTest {

    private static final String TASK_ID = "task-1";

    @Test
    void freezesEligibleAndInsufficientEvidenceTargetsInStableIdentityOrder() {
        GenerationTaskRepository repository = readyRepository(2, 2);
        FeatureSourceCandidate matchedCandidate = candidate("candidate-b", "订单查询", FeatureCandidateKind.REQUIREMENT);
        FeatureSourceCandidate insufficientCandidate = candidate("candidate-a", "订单导出");
        FeatureReviewConclusion matched = conclusion("conclusion-b", 2, FeatureReviewConclusionType.MATCHED,
                "订单查询", "candidate-b");
        FeatureReviewConclusion insufficient = conclusion("conclusion-a", 1, FeatureReviewConclusionType.INSUFFICIENT_EVIDENCE,
                "订单导出", "candidate-a");
        when(repository.featureSourceCandidates(TASK_ID)).thenReturn(List.of(matchedCandidate, insufficientCandidate));
        when(repository.featureReviewConclusions(TASK_ID)).thenReturn(List.of(matched, insufficient));

        FrozenFeatureResult result = new FrozenFeatureService(repository).freeze(TASK_ID, scope());

        assertThat(result.frozen()).isTrue();
        assertThat(result.targets()).extracting(FrozenFeatureTarget::featureName)
                .containsExactlyInAnyOrder("订单导出", "订单查询");
        assertThat(result.targets()).extracting(FrozenFeatureTarget::stableFeatureId).isSorted();
        assertThat(result.targets()).extracting(FrozenFeatureTarget::stableSequence).containsExactly(1, 2);
        assertThat(result.targets()).anySatisfy(target -> {
            assertThat(target.featureName()).isEqualTo("订单导出");
            assertThat(target.generationEligible()).isFalse();
            assertThat(target.source().candidateIds()).containsExactly("candidate-a");
        });
        verify(repository).persistFrozenFeatureTargets(eq(TASK_ID), eq(result.targets()));
    }

    @Test
    void createsOneTargetPerExplicitSplitPathWithTheSameTraceability() {
        GenerationTaskRepository repository = readyRepository(1, 1);
        FeatureSourceCandidate candidate = candidate("candidate-1", "订单处理", FeatureCandidateKind.REQUIREMENT);
        FeatureReviewConclusion split = conclusion("conclusion-1", 1, FeatureReviewConclusionType.SPLIT,
                "订单查询<br>订单导出", "candidate-1");
        when(repository.featureSourceCandidates(TASK_ID)).thenReturn(List.of(candidate));
        when(repository.featureReviewConclusions(TASK_ID)).thenReturn(List.of(split));

        FrozenFeatureResult result = new FrozenFeatureService(repository).freeze(TASK_ID, scope());

        assertThat(result.targets()).extracting(FrozenFeatureTarget::featureName).containsExactlyInAnyOrder("订单导出", "订单查询");
        assertThat(result.targets()).extracting(FrozenFeatureTarget::stableFeatureId).isSorted();
        assertThat(result.targets()).allSatisfy(target -> {
            assertThat(target.generationEligible()).isTrue();
            assertThat(target.source().conclusionId()).isEqualTo("conclusion-1");
            assertThat(target.source().conclusionType()).isEqualTo(FeatureReviewConclusionType.SPLIT);
            assertThat(target.source().candidateIds()).containsExactly("candidate-1");
            assertThat(target.source().decisionReason()).isEqualTo("订单查询<br>订单导出");
        });
    }

    @Test
    void rejectsUnresolvedConflictsAndDoesNotPersistAPartialFreeze() {
        GenerationTaskRepository repository = readyRepository(1, 1);
        FeatureSourceCandidate candidate = candidate("candidate-1", "订单处理");
        FeatureReviewConclusion conflict = conclusion("conclusion-1", 1, FeatureReviewConclusionType.CONFLICT,
                "订单状态含义冲突", "candidate-1");
        when(repository.featureSourceCandidates(TASK_ID)).thenReturn(List.of(candidate));
        when(repository.featureReviewConclusions(TASK_ID)).thenReturn(List.of(conflict));

        assertThatThrownBy(() -> new FrozenFeatureService(repository).freeze(TASK_ID, scope()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unresolved conflict");

        verify(repository, never()).persistFrozenFeatureTargets(eq(TASK_ID), org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void keepsFunctionListOnlyRequirementMissingTargetsEligibleForLaterConservativeGeneration() {
        GenerationTaskRepository repository = readyRepository(1, 1);
        FeatureSourceCandidate candidate = candidate("candidate-1", "订单导出");
        FeatureReviewConclusion missingRequirement = conclusion("conclusion-1", 1, FeatureReviewConclusionType.REQUIREMENT_MISSING,
                "订单导出", "candidate-1");
        when(repository.featureSourceCandidates(TASK_ID)).thenReturn(List.of(candidate));
        when(repository.featureReviewConclusions(TASK_ID)).thenReturn(List.of(missingRequirement));

        FrozenFeatureResult result = new FrozenFeatureService(repository).freeze(TASK_ID, scope());

        assertThat(result.targets()).singleElement().satisfies(target -> {
            assertThat(target.featureName()).isEqualTo("订单导出");
            assertThat(target.generationEligible()).isTrue();
        });
    }

    @Test
    void rejectsMissingCandidateDispositionBeforeAnyFreeze() {
        GenerationTaskRepository repository = readyRepository(1, 1);
        when(repository.featureSourceCandidates(TASK_ID)).thenReturn(List.of(candidate("candidate-1", "订单处理")));
        when(repository.featureReviewConclusions(TASK_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> new FrozenFeatureService(repository).freeze(TASK_ID, scope()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ledger counts changed");

        verify(repository, never()).persistFrozenFeatureTargets(eq(TASK_ID), org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void reusesTheAlreadyPersistedFreezeWithoutRecomputingIt() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        FrozenFeatureTarget retained = new FrozenFeatureTarget("ff-retained", 1, "订单查询", true,
                new FrozenFeatureSource("conclusion-1", FeatureReviewConclusionType.MATCHED, List.of("candidate-1"), "订单查询"));
        when(repository.frozenFeatureTargets(TASK_ID)).thenReturn(List.of(retained));
        when(repository.hasCompleteMaterialInventory(TASK_ID, scope())).thenReturn(true);
        when(repository.featureAuditCounts(TASK_ID)).thenReturn(new GenerationTaskRepository.FeatureAuditCounts(1, 1, 0, 1, 1, 1));

        FrozenFeatureResult result = new FrozenFeatureService(repository).freeze(TASK_ID, scope());

        assertThat(result).isEqualTo(new FrozenFeatureResult(true, List.of(retained)));
        verify(repository).hasCompleteMaterialInventory(TASK_ID, scope());
        verify(repository, never()).persistFrozenFeatureTargets(eq(TASK_ID), org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void rejectsAnExistingFreezeWhenTheCallerScopeNoLongerMatchesTheCompleteInventory() {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        when(repository.frozenFeatureTargets(TASK_ID)).thenReturn(List.of(new FrozenFeatureTarget("ff-retained", 1, "订单查询", true,
                new FrozenFeatureSource("conclusion-1", FeatureReviewConclusionType.MATCHED, List.of("candidate-1"), "订单查询"))));
        when(repository.hasCompleteMaterialInventory(TASK_ID, scope())).thenReturn(false);

        assertThatThrownBy(() -> new FrozenFeatureService(repository).freeze(TASK_ID, scope()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Material inventory is not complete");
    }

    @Test
    void rejectsBlankOrRepeatedSplitPathsInsteadOfGuessingABusinessPath() {
        GenerationTaskRepository repository = readyRepository(1, 1);
        FeatureSourceCandidate candidate = candidate("candidate-1", "订单处理");
        FeatureReviewConclusion split = conclusion("conclusion-1", 1, FeatureReviewConclusionType.SPLIT,
                "订单查询<br>订单查询", "candidate-1");
        when(repository.featureSourceCandidates(TASK_ID)).thenReturn(List.of(candidate));
        when(repository.featureReviewConclusions(TASK_ID)).thenReturn(List.of(split));

        assertThatThrownBy(() -> new FrozenFeatureService(repository).freeze(TASK_ID, scope()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("distinct business paths");
    }

    @Test
    void rejectsMarkupInAnyBusinessPathInsteadOfFreezingRenderedMarkupAsAFeature() {
        GenerationTaskRepository repository = readyRepository(1, 1);
        FeatureSourceCandidate candidate = candidate("candidate-1", "订单处理");
        FeatureReviewConclusion malformed = conclusion("conclusion-1", 1, FeatureReviewConclusionType.MATCHED,
                "<strong>订单查询</strong>", "candidate-1");
        when(repository.featureSourceCandidates(TASK_ID)).thenReturn(List.of(candidate));
        when(repository.featureReviewConclusions(TASK_ID)).thenReturn(List.of(malformed));

        assertThatThrownBy(() -> new FrozenFeatureService(repository).freeze(TASK_ID, scope()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("plain text");
    }

    @Test
    // [Req-ID]: REQ-BFA-003, REQ-BFA-005, REQ-BFA-007
    void acceptsGreaterThanAsAPlainTextHierarchySeparator() {
        GenerationTaskRepository repository = readyRepository(1, 1);
        FeatureSourceCandidate candidate = candidate("candidate-1", "订单处理");
        FeatureReviewConclusion matched = conclusion("conclusion-1", 1, FeatureReviewConclusionType.MATCHED,
                "信息中心 > 月度例会 > 查看", "candidate-1");
        when(repository.featureSourceCandidates(TASK_ID)).thenReturn(List.of(candidate));
        when(repository.featureReviewConclusions(TASK_ID)).thenReturn(List.of(matched));

        FrozenFeatureResult result = new FrozenFeatureService(repository).freeze(TASK_ID, scope());

        assertThat(result.targets()).singleElement()
                .extracting(FrozenFeatureTarget::featureName)
                .isEqualTo("信息中心 > 月度例会 > 查看");
    }

    @Test
    void rejectsARepeatedNormalizedBusinessPathAcrossSeparateConclusions() {
        GenerationTaskRepository repository = readyRepository(2, 2);
        FeatureSourceCandidate first = candidate("candidate-1", "订单查询");
        FeatureSourceCandidate second = candidate("candidate-2", "订单查询");
        when(repository.featureSourceCandidates(TASK_ID)).thenReturn(List.of(first, second));
        when(repository.featureReviewConclusions(TASK_ID)).thenReturn(List.of(
                conclusion("conclusion-1", 1, FeatureReviewConclusionType.MATCHED, "订单查询", "candidate-1"),
                conclusion("conclusion-2", 2, FeatureReviewConclusionType.FUNCTION_LIST_MISSING, " 订单查询 ", "candidate-2")));

        assertThatThrownBy(() -> new FrozenFeatureService(repository).freeze(TASK_ID, scope()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Frozen business paths must be distinct");
    }

    @Test
    void derivesTheSameIdentityFromSortedCandidateIdsAndANormalizedBusinessPath() {
        GenerationTaskRepository firstRepository = readyRepository(2, 1);
        GenerationTaskRepository replayRepository = readyRepository(2, 1);
        FeatureSourceCandidate first = candidate("candidate-z", "订单查询");
        FeatureSourceCandidate second = candidate("candidate-a", "订单查询");
        FeatureReviewConclusion original = new FeatureReviewConclusion("conclusion-original", 9, FeatureReviewConclusionType.MERGE,
                "订单  查询", "candidateIds=candidate-z,candidate-a", List.of("candidate-z", "candidate-a"));
        FeatureReviewConclusion replay = new FeatureReviewConclusion("conclusion-replay", 1, FeatureReviewConclusionType.MERGE,
                "订单 查询", "candidateIds=candidate-a,candidate-z", List.of("candidate-a", "candidate-z"));
        when(firstRepository.featureSourceCandidates(TASK_ID)).thenReturn(List.of(first, second));
        when(firstRepository.featureReviewConclusions(TASK_ID)).thenReturn(List.of(original));
        when(replayRepository.featureSourceCandidates(TASK_ID)).thenReturn(List.of(second, first));
        when(replayRepository.featureReviewConclusions(TASK_ID)).thenReturn(List.of(replay));

        FrozenFeatureTarget originalTarget = new FrozenFeatureService(firstRepository).freeze(TASK_ID, scope()).targets().get(0);
        FrozenFeatureTarget replayTarget = new FrozenFeatureService(replayRepository).freeze(TASK_ID, scope()).targets().get(0);

        assertThat(replayTarget.stableFeatureId()).isEqualTo(originalTarget.stableFeatureId());
        assertThat(replayTarget.source().candidateIds()).containsExactly("candidate-a", "candidate-z");
    }

    private static GenerationTaskRepository readyRepository(int candidateCount, int conclusionCount) {
        GenerationTaskRepository repository = mock(GenerationTaskRepository.class);
        when(repository.frozenFeatureTargets(TASK_ID)).thenReturn(List.of());
        when(repository.hasCompleteMaterialInventory(TASK_ID, scope())).thenReturn(true);
        when(repository.featureAuditCounts(TASK_ID)).thenReturn(new GenerationTaskRepository.FeatureAuditCounts(
                2, 2, 0, candidateCount, conclusionCount, candidateCount));
        return repository;
    }

    private static FeatureSourceCandidate candidate(String id, String text) {
        return candidate(id, text, FeatureCandidateKind.FUNCTION_LIST);
    }

    private static FeatureSourceCandidate candidate(String id, String text, FeatureCandidateKind kind) {
        return new FeatureSourceCandidate(id, kind, "function-document", "unit-1", 1, 1,
                text, "功能项", "documentId=function-document; unitId=unit-1", 1, 1);
    }

    private static FeatureReviewConclusion conclusion(
            String id, int sequence, FeatureReviewConclusionType type, String path, String candidateId) {
        return new FeatureReviewConclusion(id, sequence, type, path,
                "candidateIds=" + candidateId + "; documentId=function-document; unitId=unit-1", List.of(candidateId));
    }

    private static RequirementScope scope() {
        return new RequirementScope("requirement-kb", "system", "version", "admission_material", "project",
                List.of(new RequirementDocumentCoordinate("function-document")));
    }
}
