package com.testcaseagent.knowledgeagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Exact structured-call projection of one frozen system/version/project/document scope. [Req-ID]: REQ-SKI-002 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SystemScopePayload(@JsonProperty("knowledge_base_id") String knowledgeBaseId,
        @JsonProperty("version_id") String versionId, @JsonProperty("project_id") String projectId,
        @JsonProperty("knowledge_ids") List<String> knowledgeIds) {
    public SystemScopePayload {
        knowledgeBaseId = StructuredSkillContract.key(knowledgeBaseId, "knowledgeBaseId");
        versionId = StructuredSkillContract.key(versionId, "versionId");
        projectId = StructuredSkillContract.key(projectId, "projectId");
        knowledgeIds = StructuredSkillContract.keyReferences(knowledgeIds, "knowledgeIds");
        if (knowledgeIds.isEmpty() || knowledgeIds.size() > 100) throw new IllegalArgumentException("knowledgeIds has an invalid size");
    }
}
