package com.testcaseagent.knowledgeagent;

/**
 * Signals that an isolated Skill-preparation conversation could not prove the exact Skill load.
 *
 * <p>The caller must not send that work item's business prompt after this exception. The flag only
 * describes whether the adapter exhausted a safe transport retry; it never permits a scope or SSE
 * contract failure to be retried as transport work.</p>
 *
 * [Req-ID]: REQ-KSI-002, REQ-KSI-003
 */
public final class KnowledgeAgentSkillPreparationException extends KnowledgeAgentInvocationException {

    private final boolean transportRetriesExhausted;

    public KnowledgeAgentSkillPreparationException(String message, boolean transportRetriesExhausted, Throwable cause) {
        super(message, cause);
        this.transportRetriesExhausted = transportRetriesExhausted;
    }

    /** Returns whether all bounded retries were consumed by a safe transient transport failure. */
    public boolean transportRetriesExhausted() {
        return transportRetriesExhausted;
    }
}
