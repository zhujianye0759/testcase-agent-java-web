package com.testcaseagent.knowledgeagent;

import com.testcaseagent.scope.RequirementScope;
import java.util.Objects;

/** One server-selected material review call with no chat prompt or tool fields. [Req-ID]: REQ-SKI-002, REQ-SKI-003 */
public record RequirementMaterialQualityReviewInvocation(String sessionId, String agentId, RequirementScope requirementScope,
        RequirementMaterialQualityReviewInput input) {
    public RequirementMaterialQualityReviewInvocation { sessionId = StructuredSkillContract.key(sessionId, "sessionId"); agentId = StructuredSkillContract.key(agentId, "agentId"); requirementScope = Objects.requireNonNull(requirementScope, "requirementScope must not be null"); input = Objects.requireNonNull(input, "input must not be null"); }
}
