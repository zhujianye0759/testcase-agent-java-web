package com.testcaseagent.knowledgeagent;

import com.testcaseagent.scope.RequirementScope;
import java.util.Objects;

/**
 * One isolated protocol V1 candidate call bound to an immutable requirement scope.
 *
 * [Req-ID]: REQ-AFCE-001, REQ-AFCE-008
 */
public record FunctionCandidateExtractionInvocation(
        String sessionId,
        String agentId,
        RequirementScope requirementScope,
        FunctionCandidateExtractionInput input) {
    public FunctionCandidateExtractionInvocation {
        sessionId = StructuredSkillContract.key(sessionId, "sessionId");
        agentId = StructuredSkillContract.key(agentId, "agentId");
        requirementScope = Objects.requireNonNull(requirementScope, "requirementScope must not be null");
        input = Objects.requireNonNull(input, "input must not be null");
    }
}
