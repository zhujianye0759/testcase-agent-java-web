package com.testcaseagent.knowledgeagent;

/** Typed success envelope returned only after strict protocol validation. [Req-ID]: REQ-SKI-004 */
public record StructuredSkillSuccessEnvelope<T>(boolean success, StructuredSkillSuccess<T> data) { }
