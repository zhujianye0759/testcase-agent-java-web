package com.testcaseagent.task;

import java.util.Objects;

/**
 * Frozen task, input, and artifact contract versions for the generation-v2 route.
 * Keeping the three axes explicit prevents a later artifact change from silently reinterpreting stored inputs.
 *
 * [Req-ID]: REQ-TGV2-001, REQ-TGV2-010
 */
public record GenerationContractVersions(String workflowVersion, String inputVersion, String artifactVersion) {
    /** Historical snapshots predate explicit version columns and are read as V1. */
    public static final String V1 = "1.0";
    public static final String V2 = "2.0";

    public GenerationContractVersions {
        workflowVersion = required(workflowVersion, "workflowVersion");
        inputVersion = required(inputVersion, "inputVersion");
        artifactVersion = required(artifactVersion, "artifactVersion");
    }

    /** Returns whether every independently persisted axis selects the frozen V2 contract. */
    public boolean isV2() {
        return V2.equals(workflowVersion) && V2.equals(inputVersion) && V2.equals(artifactVersion);
    }

    private static String required(String value, String field) {
        String checked = Objects.requireNonNull(value, field + " must not be null").trim();
        if (checked.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return checked;
    }
}
