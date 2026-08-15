package com.testcaseagent.knowledgeagent;

/**
 * Invokes the configured remote knowledge agent through one isolated session.
 *
 * [Spec-Ref]: design.md §5 KnowledgeAgentPort
 * [Req-ID]: REQ-KAG-001, REQ-KAG-002, REQ-KAG-003, REQ-KAG-004, REQ-KAG-005
 */
public interface KnowledgeAgentPort {

    KnowledgeAgentInvocationResult invoke(KnowledgeAgentInvocation invocation);

    /**
     * Reconciles feature-list and requirement candidates within one frozen formal scope.
     *
     * [Req-ID]: REQ-KSI-001, REQ-KSI-002, REQ-KSI-003, REQ-BFA-003
     */
    KnowledgeAgentInvocationResult reconcileFeatures(FeatureReconciliationInvocation invocation);

}
