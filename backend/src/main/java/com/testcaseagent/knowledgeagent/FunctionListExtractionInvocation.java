package com.testcaseagent.knowledgeagent;

import com.testcaseagent.scope.RequirementScope;
import java.util.Objects;

/** One isolated extract-function-list call that carries no ordinary chat fields. [Req-ID]: REQ-SKI-002, REQ-SKI-003 */
public record FunctionListExtractionInvocation(
        String sessionId, String agentId, RequirementScope requirementScope, FunctionListExtractionInput input) {
    public FunctionListExtractionInvocation {
        sessionId = StructuredSkillContract.key(sessionId, "sessionId");
        agentId = StructuredSkillContract.key(agentId, "agentId");
        requirementScope = Objects.requireNonNull(requirementScope, "requirementScope must not be null");
        input = Objects.requireNonNull(input, "input must not be null");
    }
}
