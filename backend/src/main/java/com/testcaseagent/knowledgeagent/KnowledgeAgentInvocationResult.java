package com.testcaseagent.knowledgeagent;

import java.util.List;

/**
 * Accepted terminal output and the observable stream summary of one isolated session.
 *
 * [Req-ID]: REQ-KAG-001, REQ-KAG-004
 */
public record KnowledgeAgentInvocationResult(
        String sessionId,
        List<KnowledgeAgentStreamEvent> events,
        String terminalMarkdown) {

    public KnowledgeAgentInvocationResult {
        events = List.copyOf(events);
    }
}
