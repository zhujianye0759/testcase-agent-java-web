package com.testcaseagent.task;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.testcaseagent.fewshot.ExampleScope;
import com.testcaseagent.featureaudit.FrozenFeatureTarget;
import com.testcaseagent.scope.RequirementScope;
import com.testcaseagent.testcase.FewShotPolicy;
import com.testcaseagent.testcase.GenerationTaskMode;
import java.util.Objects;
import java.util.List;
import java.util.Map;

/**
 * Frozen request for the one-feature tracer task.
 *
 * [Req-ID]: REQ-TSK-001, REQ-KAG-002
 */
public record CreateGenerationTaskRequest(
        GenerationTaskMode taskMode,
        String featureId,
        List<String> featureIds,
        Map<String, String> featurePaths,
        FewShotPolicy fewShotPolicy,
        String schemaVersion,
        String promptVersion,
        String agentId,
        RequirementScope requirementScope,
        ExampleScope exampleScope,
        @JsonAlias("requirementAdmissionTypeKey")
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
        List<String> requirementAdmissionTypeKeys,
        String prompt,
        GenerationContractVersions contractVersions,
        ApprovedFunctionScope approvedFunctionScope) {

    public CreateGenerationTaskRequest {
        taskMode = Objects.requireNonNull(taskMode, "taskMode must not be null");
        featureId = requireText(featureId, "featureId");
        featureIds = featureIds == null ? List.of(featureId) : featureIds.stream()
                .map(id -> requireText(id, "featureId")).toList();
        if (featureIds.stream().distinct().count() != featureIds.size()) {
            throw new IllegalArgumentException("featureIds must not contain duplicates");
        }
        if (taskMode == GenerationTaskMode.FEATURE && !featureIds.equals(List.of(featureId))) {
            throw new IllegalArgumentException("FEATURE mode must contain exactly its selected feature");
        }
        Map<String, String> suppliedPaths = featurePaths == null ? Map.of() : Map.copyOf(featurePaths);
        if (!suppliedPaths.keySet().equals(java.util.Set.copyOf(featureIds))) {
            throw new IllegalArgumentException("featurePaths must match the frozen feature IDs");
        }
        java.util.LinkedHashMap<String, String> orderedPaths = new java.util.LinkedHashMap<>();
        for (String id : featureIds) orderedPaths.put(id, requireText(suppliedPaths.get(id), "featurePath"));
        featurePaths = java.util.Collections.unmodifiableMap(orderedPaths);
        fewShotPolicy = Objects.requireNonNull(fewShotPolicy, "fewShotPolicy must not be null");
        schemaVersion = requireText(schemaVersion, "schemaVersion");
        promptVersion = requireText(promptVersion, "promptVersion");
        agentId = requireText(agentId, "agentId");
        requirementScope = Objects.requireNonNull(requirementScope, "requirementScope must not be null");
        exampleScope = Objects.requireNonNull(exampleScope, "exampleScope must not be null");
        exampleScope.requireIndependentFrom(requirementScope);
        requirementAdmissionTypeKeys = requiredTypes(requirementAdmissionTypeKeys);
        prompt = requireText(prompt, "prompt");
        if ((contractVersions == null) != (approvedFunctionScope == null)) {
            throw new IllegalArgumentException("V2 versions and approved function scope must be supplied together");
        }
        if (contractVersions != null && !contractVersions.isV2()) {
            throw new IllegalArgumentException("Only the frozen V2 generation contract is supported for new tasks");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static List<String> requiredTypes(List<String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("requirementAdmissionTypeKeys must not be empty");
        }
        return values.stream().map(value -> requireText(value, "requirementAdmissionTypeKey"))
                .distinct().toList();
    }

    public CreateGenerationTaskRequest(
            GenerationTaskMode taskMode, String featureId, FewShotPolicy fewShotPolicy, String schemaVersion,
            String promptVersion, String agentId, RequirementScope requirementScope, ExampleScope exampleScope,
            String requirementAdmissionTypeKey, String prompt) {
        this(taskMode, featureId, List.of(featureId), Map.of(featureId, featureId), fewShotPolicy, schemaVersion, promptVersion, agentId,
                requirementScope, exampleScope, List.of(requirementAdmissionTypeKey), prompt, null, null);
    }

    /** Historical constructor used only to deserialize and exercise pre-V2 task snapshots. */
    public CreateGenerationTaskRequest(GenerationTaskMode taskMode, String featureId, List<String> featureIds,
            Map<String, String> featurePaths, FewShotPolicy fewShotPolicy, String schemaVersion,
            String promptVersion, String agentId, RequirementScope requirementScope, ExampleScope exampleScope,
            List<String> requirementAdmissionTypeKeys, String prompt) {
        this(taskMode, featureId, featureIds, featurePaths, fewShotPolicy, schemaVersion, promptVersion, agentId,
                requirementScope, exampleScope, requirementAdmissionTypeKeys, prompt, null, null);
    }

    /** True only for a task whose complete frozen version tuple selects generation V2. */
    public boolean isV2() { return contractVersions != null && contractVersions.isV2(); }

    /**
     * Replaces an unplanned ALL request with the server-owned, durably frozen generation subset.
     *
     * <p>Only eligible targets appear in the generation snapshot. When every frozen target is ineligible the
     * empty snapshot deliberately remains unplanned so the workflow can record the explicit partial outcome
     * without inventing a batch.</p>
     *
     * [Req-ID]: REQ-CAG-001, REQ-BFA-005
     */
    public CreateGenerationTaskRequest withFrozenFeatures(List<FrozenFeatureTarget> frozenTargets) {
        if (taskMode != GenerationTaskMode.ALL || frozenTargets == null) {
            throw new IllegalArgumentException("ALL mode requires frozen features");
        }
        List<FrozenFeatureTarget> ordered = frozenTargets.stream()
                .sorted(java.util.Comparator.comparingInt(FrozenFeatureTarget::stableSequence))
                .toList();
        if (ordered.stream().map(FrozenFeatureTarget::stableSequence).distinct().count() != ordered.size()
                || ordered.stream().map(FrozenFeatureTarget::stableFeatureId).distinct().count() != ordered.size()) {
            throw new IllegalArgumentException("Frozen feature sequence and IDs must be unique");
        }
        for (int index = 0; index < ordered.size(); index++) {
            if (ordered.get(index).stableSequence() != index + 1) {
                throw new IllegalArgumentException("Frozen feature sequence must be continuous from one");
            }
        }
        List<FrozenFeatureTarget> eligible = ordered.stream().filter(FrozenFeatureTarget::generationEligible).toList();
        List<String> ids = eligible.stream().map(FrozenFeatureTarget::stableFeatureId).toList();
        Map<String, String> paths = new java.util.LinkedHashMap<>();
        for (FrozenFeatureTarget target : eligible) paths.put(target.stableFeatureId(), target.featureName());
        String nextFeatureId = ids.isEmpty() ? featureId : ids.get(0);
        return new CreateGenerationTaskRequest(taskMode, nextFeatureId, ids, paths, fewShotPolicy, schemaVersion, promptVersion,
                agentId, requirementScope, exampleScope, requirementAdmissionTypeKeys, prompt,
                contractVersions, approvedFunctionScope);
    }
}
