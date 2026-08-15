package com.testcaseagent.knowledgeagent;

import com.testcaseagent.fewshot.ExampleScope;
import com.testcaseagent.scope.RequirementScope;
import com.testcaseagent.testcase.FewShotPolicy;

import java.util.List;
import java.util.Objects;

/**
 * Frozen, separated input for one remote agent invocation.
 *
 * [Req-ID]: REQ-KAG-002, REQ-KAG-003, REQ-SCP-003, REQ-FEW-001
 */
public record KnowledgeAgentInvocation(
        String agentId,
        RequirementScope requirementScope,
        ExampleScope exampleScope,
        List<String> requirementAdmissionTypeKeys,
        String prompt,
        FewShotPolicy fewShotPolicy) {

    public KnowledgeAgentInvocation {
        agentId = requireText(agentId, "agentId");
        requirementScope = Objects.requireNonNull(requirementScope, "requirementScope must not be null");
        exampleScope = Objects.requireNonNull(exampleScope, "exampleScope must not be null");
        exampleScope.requireIndependentFrom(requirementScope);
        requirementAdmissionTypeKeys = requiredTypes(requirementAdmissionTypeKeys);
        prompt = requireText(prompt, "prompt");
        fewShotPolicy = Objects.requireNonNull(fewShotPolicy, "fewShotPolicy must not be null");
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

    public KnowledgeAgentInvocation(
            String agentId, RequirementScope requirementScope, ExampleScope exampleScope,
            String requirementAdmissionTypeKey, String prompt) {
        this(agentId, requirementScope, exampleScope, List.of(requirementAdmissionTypeKey), prompt, FewShotPolicy.AUTO);
    }

    public KnowledgeAgentInvocation(
            String agentId, RequirementScope requirementScope, ExampleScope exampleScope,
            List<String> requirementAdmissionTypeKeys, String prompt) {
        this(agentId, requirementScope, exampleScope, requirementAdmissionTypeKeys, prompt, FewShotPolicy.AUTO);
    }
}
