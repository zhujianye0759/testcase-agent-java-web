package com.testcaseagent.knowledgeagent;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Fixed inner success data for a structured Skill result. [Req-ID]: REQ-SKI-004 */
public record StructuredSkillSuccess<T>(@JsonProperty("schema_version") String schemaVersion,
        @JsonProperty("skill_name") String skillName, @JsonProperty("repair_attempted") boolean repairAttempted, T result) { }
