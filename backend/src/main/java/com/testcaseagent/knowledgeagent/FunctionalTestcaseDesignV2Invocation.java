package com.testcaseagent.knowledgeagent;

import com.testcaseagent.scope.RequirementScope;
import java.util.Objects;

/** One V2 testcase-design call over the task's immutable admitted requirement scope. [Req-ID]: REQ-TGV2-005 */
public record FunctionalTestcaseDesignV2Invocation(
        String sessionId,
        String agentId,
        RequirementScope requirementScope,
        FunctionalTestcaseDesignV2Input input) {
    public FunctionalTestcaseDesignV2Invocation {
        sessionId = StructuredSkillContract.key(sessionId, "sessionId");
        agentId = StructuredSkillContract.key(agentId, "agentId");
        requirementScope = Objects.requireNonNull(requirementScope, "requirementScope must not be null");
        input = Objects.requireNonNull(input, "input must not be null");
    }
}
