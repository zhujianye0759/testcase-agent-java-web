package com.testcaseagent.task;

import com.testcaseagent.testcase.FewShotPolicy;
import com.testcaseagent.testcase.GenerationTaskMode;
import java.util.Objects;

/**
 * Browser-safe task creation command that identifies one server-authorized scope option.
 *
 * [Req-ID]: REQ-KAG-006, REQ-SCP-004
 */
public record CreateGenerationTaskCommand(
        GenerationTaskMode taskMode,
        String featureDescription,
        FewShotPolicy fewShotPolicy,
        String schemaVersion,
        String promptVersion,
        String scopeOptionId,
        String prompt) {

    public CreateGenerationTaskCommand {
        taskMode = Objects.requireNonNull(taskMode, "taskMode must not be null");
        featureDescription = featureDescription == null ? "" : featureDescription.trim();
        if (taskMode == GenerationTaskMode.FEATURE && featureDescription.isEmpty()) {
            throw new IllegalArgumentException("请填写要生成的功能名称或功能描述");
        }
        fewShotPolicy = Objects.requireNonNull(fewShotPolicy, "fewShotPolicy must not be null");
        schemaVersion = requireText(schemaVersion, "schemaVersion");
        promptVersion = requireText(promptVersion, "promptVersion");
        scopeOptionId = requireText(scopeOptionId, "scopeOptionId");
        prompt = prompt == null ? "" : prompt.trim();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

}
