package com.testcaseagent.knowledgeagent;

import com.testcaseagent.scope.RequirementScope;
import java.util.List;
import java.util.Objects;

/**
 * Frozen request for all-feature discovery; it deliberately has no example scope or client cursor.
 *
 * [Req-ID]: REQ-ANA-001, REQ-SCP-004
 */
public record FeatureDiscoveryInvocation(
        String agentId,
        RequirementScope requirementScope,
        List<String> requirementAdmissionTypeKeys) {

    public FeatureDiscoveryInvocation {
        agentId = requireText(agentId, "agentId");
        requirementScope = Objects.requireNonNull(requirementScope, "requirementScope must not be null");
        requirementAdmissionTypeKeys = requirementAdmissionTypeKeys == null ? List.of() : requirementAdmissionTypeKeys.stream()
                .map(value -> requireText(value, "requirementAdmissionTypeKey")).distinct().toList();
        if (requirementAdmissionTypeKeys.isEmpty()) throw new IllegalArgumentException("requirementAdmissionTypeKeys must not be empty");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
