package com.testcaseagent.validation;

/**
 * Resolved evidence ownership needed before a model result can be accepted.
 *
 * <p>The workflow builds these values from its frozen scope and complete parsed-unit traversal; this validator never
 * interprets model text as evidence.</p>
 *
 * [Req-ID]: REQ-STG-001
 */
public record StructuredEvidence(
        String evidenceKey,
        String taskId,
        String materialKey,
        boolean exampleScope,
        boolean retired,
        boolean fullyTraversed) {

    /** Creates one immutable resolved evidence coordinate. */
    public StructuredEvidence {
        evidenceKey = required(evidenceKey, "evidenceKey");
        taskId = required(taskId, "taskId");
        materialKey = required(materialKey, "materialKey");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
