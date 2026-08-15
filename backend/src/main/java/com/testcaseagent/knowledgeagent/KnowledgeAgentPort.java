package com.testcaseagent.knowledgeagent;

import com.testcaseagent.markdown.MarkdownFeatureRow;
import java.util.List;

/**
 * Invokes the configured remote knowledge agent through one isolated session.
 *
 * [Spec-Ref]: design.md §5 KnowledgeAgentPort
 * [Req-ID]: REQ-KAG-001, REQ-KAG-002, REQ-KAG-003, REQ-KAG-004, REQ-KAG-005
 */
public interface KnowledgeAgentPort {

    KnowledgeAgentInvocationResult invoke(KnowledgeAgentInvocation invocation);

    List<MarkdownFeatureRow> discoverFeatures(FeatureDiscoveryInvocation invocation);
}
