package com.testcaseagent.knowledgeagent;

/**
 * Domain-neutral summary of an upstream SSE event observed during an invocation.
 *
 * [Req-ID]: REQ-KAG-001, REQ-KAG-004
 */
public record KnowledgeAgentStreamEvent(String responseType, boolean done) {
}
