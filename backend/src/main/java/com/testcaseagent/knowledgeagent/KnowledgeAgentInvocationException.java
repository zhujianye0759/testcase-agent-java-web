package com.testcaseagent.knowledgeagent;

/**
 * Signals an external protocol failure that cannot produce an accepted result.
 *
 * [Req-ID]: REQ-KAG-001, REQ-KAG-004, REQ-KAG-005
 */
public class KnowledgeAgentInvocationException extends RuntimeException {

    public KnowledgeAgentInvocationException(String message) {
        super(message);
    }

    public KnowledgeAgentInvocationException(String message, Throwable cause) {
        super(message, cause);
    }
}
