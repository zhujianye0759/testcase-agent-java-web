package com.testcaseagent.task;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.testcaseagent.fewshot.ExampleScope;
import com.testcaseagent.scope.RequirementScope;
import com.testcaseagent.testcase.FewShotPolicy;
import com.testcaseagent.testcase.GenerationTaskMode;
import com.testcaseagent.markdown.MarkdownFeatureRow;
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
        String prompt) {

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
        featurePaths = featureIds.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                id -> id, id -> requireText(suppliedPaths.get(id), "featurePath")));
        fewShotPolicy = Objects.requireNonNull(fewShotPolicy, "fewShotPolicy must not be null");
        schemaVersion = requireText(schemaVersion, "schemaVersion");
        promptVersion = requireText(promptVersion, "promptVersion");
        agentId = requireText(agentId, "agentId");
        requirementScope = Objects.requireNonNull(requirementScope, "requirementScope must not be null");
        exampleScope = Objects.requireNonNull(exampleScope, "exampleScope must not be null");
        exampleScope.requireIndependentFrom(requirementScope);
        requirementAdmissionTypeKeys = requiredTypes(requirementAdmissionTypeKeys);
        prompt = requireText(prompt, "prompt");
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
                requirementScope, exampleScope, List.of(requirementAdmissionTypeKey), prompt);
    }

    /** Freezes server-discovered ALL-mode feature names in their Markdown table order. */
    public CreateGenerationTaskRequest withDiscoveredFeatures(List<MarkdownFeatureRow> discoveredRows) {
        if (taskMode != GenerationTaskMode.ALL || discoveredRows == null || discoveredRows.isEmpty()) {
            throw new IllegalArgumentException("ALL mode requires discovered features");
        }
        List<String> ids = discoveredRows.stream().map(row -> stableFeatureId(row.sequence(), row.featureName())).toList();
        Map<String, String> paths = new java.util.LinkedHashMap<>();
        for (int index = 0; index < ids.size(); index++) paths.put(ids.get(index), discoveredRows.get(index).featureName());
        return new CreateGenerationTaskRequest(taskMode, ids.get(0), ids, paths, fewShotPolicy, schemaVersion, promptVersion,
                agentId, requirementScope, exampleScope, requirementAdmissionTypeKeys, prompt);
    }

    private static String stableFeatureId(int sequence, String name) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((sequence + "\\n" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "feature-" + java.util.HexFormat.of().formatHex(digest, 0, 12);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
