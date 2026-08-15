package com.testcaseagent.task;

import com.testcaseagent.fewshot.ExampleScope;
import com.testcaseagent.scope.RequirementScope;
import java.util.List;
import java.util.Objects;

/**
 * Server-only mapping from a browser-safe option ID to fixed agent and knowledge scopes.
 *
 * [Req-ID]: REQ-KAG-006, REQ-SCP-004
 */
public record TaskScopeOption(
        String id,
        String label,
        String agentId,
        RequirementScope requirementScope,
        ExampleScope exampleScope,
        List<String> requirementAdmissionTypeKeys) {

    public TaskScopeOption {
        id = requireText(id, "id");
        label = requireText(label, "label");
        agentId = requireText(agentId, "agentId");
        requirementScope = Objects.requireNonNull(requirementScope, "requirementScope must not be null");
        exampleScope = Objects.requireNonNull(exampleScope, "exampleScope must not be null");
        exampleScope.requireIndependentFrom(requirementScope);
        requirementAdmissionTypeKeys = requiredTypes(requirementAdmissionTypeKeys);
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

    public TaskScopeOption(
            String id, String label, String agentId, RequirementScope requirementScope, ExampleScope exampleScope,
            String requirementAdmissionTypeKey) {
        this(id, label, agentId, requirementScope, exampleScope, List.of(requirementAdmissionTypeKey));
    }
}
