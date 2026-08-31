package com.testcaseagent.knowledgeagent;

import com.testcaseagent.scope.RequirementScope;
import java.util.Objects;

/**
 * One authorized KEE protocol V2 owner-window invocation. The requirement scope remains the frozen
 * task scope; paging changes only relation ownership and never broadens comparison evidence.
 *
 * [Req-ID]: REQ-FSC-008
 */
public record FeatureScopeReconciliationPageInvocation(String sessionId, String agentId,
        RequirementScope requirementScope, FeatureScopeReconciliationPageInput input) {
    public FeatureScopeReconciliationPageInvocation {
        sessionId = StructuredSkillContract.key(sessionId, "sessionId");
        agentId = StructuredSkillContract.key(agentId, "agentId");
        requirementScope = Objects.requireNonNull(requirementScope, "requirementScope must not be null");
        input = Objects.requireNonNull(input, "input must not be null");
    }
}
