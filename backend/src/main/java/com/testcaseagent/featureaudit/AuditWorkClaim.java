package com.testcaseagent.featureaudit;

import java.time.Instant;

/**
 * A lease-owned attempt to process one durable material-audit work item.
 *
 * [Req-ID]: REQ-BFA-001
 */
public record AuditWorkClaim(
        String workId,
        String attemptId,
        int attemptNumber,
        String taskId,
        String documentId,
        String unitId,
        int passNumber,
        String stage,
        Instant leaseExpiresAt) {
}
