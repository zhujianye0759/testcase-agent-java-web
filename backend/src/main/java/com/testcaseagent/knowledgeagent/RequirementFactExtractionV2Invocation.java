package com.testcaseagent.knowledgeagent;

import com.testcaseagent.scope.RequirementScope;
import java.util.Objects;

/** One V2 fact extraction call with an exact single-document authorization. [Req-ID]: REQ-TGV2-003 */
public record RequirementFactExtractionV2Invocation(
        String sessionId,
        String agentId,
        RequirementScope requirementScope,
        RequirementFactExtractionV2Input input) {
    public RequirementFactExtractionV2Invocation {
        sessionId = StructuredSkillContract.key(sessionId, "sessionId");
        agentId = StructuredSkillContract.key(agentId, "agentId");
        requirementScope = Objects.requireNonNull(requirementScope, "requirementScope must not be null");
        if (requirementScope.documents().size() != 1) {
            throw new IllegalArgumentException("V2 fact extraction requires exactly one document");
        }
        input = Objects.requireNonNull(input, "input must not be null");
    }
}
