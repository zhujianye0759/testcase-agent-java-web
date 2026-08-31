package com.testcaseagent.task;

import com.testcaseagent.structuredgeneration.StructuredGenerationAcceptanceStore;
import java.util.function.BooleanSupplier;

/**
 * Keeps one exact structured work attempt leased while its external call is active.
 *
 * [Req-ID]: REQ-SEW-002, REQ-SEW-003
 */
public interface StructuredWorkLeaseHeartbeat {

    /** Starts renewal for one claim until the returned scope is closed. */
    ActiveLease start(StructuredGenerationAcceptanceStore.WorkClaim claim,
            BooleanSupplier cancellationRequested);

    /** Execution-scoped lease health check used immediately before business acceptance. */
    interface ActiveLease extends AutoCloseable {
        /** Fails closed when cancellation or lease loss was observed. */
        void requireActive();

        /** Stops all future renewal without interrupting an in-progress database operation. */
        @Override
        void close();
    }
}
