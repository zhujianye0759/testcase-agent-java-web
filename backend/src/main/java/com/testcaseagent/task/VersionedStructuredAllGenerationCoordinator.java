package com.testcaseagent.task;

import java.util.Objects;

/**
 * Selects the workflow exclusively from the task's persisted contract version.
 * A V2 failure is never retried through the historical material-review/reconciliation coordinator.
 *
 * [Req-ID]: REQ-TGV2-002, REQ-TGV2-010
 */
public final class VersionedStructuredAllGenerationCoordinator implements StructuredAllGenerationCoordinator {
    private final StructuredAllGenerationCoordinator historical;
    private final StructuredAllGenerationCoordinator v2;

    public VersionedStructuredAllGenerationCoordinator(StructuredAllGenerationCoordinator historical,
            StructuredAllGenerationCoordinator v2) {
        this.historical = Objects.requireNonNull(historical, "historical coordinator must not be null");
        this.v2 = Objects.requireNonNull(v2, "V2 coordinator must not be null");
    }

    /** Routes exactly once; neither branch can fall back to the other after an exception. */
    @Override
    public void execute(String taskId, CreateGenerationTaskRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (!request.isV2()) {
            // Historical V1 structured records remain available through detail and artifact reads only. Calling the
            // former coordinator would re-enter retired KEE operations and mutate their retained audit history.
            throw new IllegalStateException("Historical structured generation tasks are read-only");
        }
        v2.execute(taskId, request);
    }
}
