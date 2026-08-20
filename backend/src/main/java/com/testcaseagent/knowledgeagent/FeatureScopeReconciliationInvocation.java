package com.testcaseagent.knowledgeagent;

import com.testcaseagent.scope.RequirementScope;
import java.util.Objects;

/** One server-selected feature reconciliation call with no ordinary-chat fields. [Req-ID]: REQ-SKI-002, REQ-SKI-003 */
public record FeatureScopeReconciliationInvocation(String sessionId, String agentId, RequirementScope requirementScope,
        FeatureScopeReconciliationInput input) {
    public FeatureScopeReconciliationInvocation { sessionId = StructuredSkillContract.key(sessionId, "sessionId"); agentId = StructuredSkillContract.key(agentId, "agentId"); requirementScope = Objects.requireNonNull(requirementScope, "requirementScope must not be null"); input = Objects.requireNonNull(input, "input must not be null"); }
}
