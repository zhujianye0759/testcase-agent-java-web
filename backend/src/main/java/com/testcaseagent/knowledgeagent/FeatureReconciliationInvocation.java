package com.testcaseagent.knowledgeagent;

import com.testcaseagent.scope.RequirementScope;
import java.util.List;
import java.util.Objects;

/**
 * Frozen request for one feature-list and requirement-candidate reconciliation. It deliberately
 * excludes examples because examples cannot establish formal scope facts.
 *
 * [Req-ID]: REQ-KSI-001, REQ-KSI-002, REQ-KSI-003, REQ-BFA-003
 */
public record FeatureReconciliationInvocation(
        String agentId,
        RequirementScope requirementScope,
        List<String> requirementAdmissionTypeKeys,
        String prompt) {

    public FeatureReconciliationInvocation {
        agentId = requireText(agentId, "agentId");
        requirementScope = Objects.requireNonNull(requirementScope, "requirementScope must not be null");
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
}
