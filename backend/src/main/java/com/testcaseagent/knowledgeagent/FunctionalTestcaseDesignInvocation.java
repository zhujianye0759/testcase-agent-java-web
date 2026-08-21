package com.testcaseagent.knowledgeagent;

import com.testcaseagent.scope.RequirementScope;
import java.util.Objects;

/** One server-selected testcase design call with no ordinary-chat fields. [Req-ID]: REQ-SKI-002, REQ-SKI-003, REQ-FTG-004, REQ-FTG-006 */
public record FunctionalTestcaseDesignInvocation(String sessionId, String agentId, RequirementScope requirementScope,
        FunctionalTestcaseDesignInput input) {
    public FunctionalTestcaseDesignInvocation {
        sessionId = StructuredSkillContract.key(sessionId, "sessionId");
        agentId = StructuredSkillContract.key(agentId, "agentId");
        requirementScope = Objects.requireNonNull(requirementScope, "requirementScope must not be null");
        input = Objects.requireNonNull(input, "input must not be null");
        input.requireExecutable();
    }
}
