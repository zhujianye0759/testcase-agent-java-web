package com.testcaseagent.task;

import java.util.Objects;

/**
 * Reader-safe answer for one explicit structured-work retry decision.
 *
 * <p>The reason is a fixed Chinese message and never contains a work identifier, failure payload, evidence key,
 * model response, credential, URL, or stack trace.</p>
 *
 * [Req-ID]: REQ-ESR-004
 */
public record StructuredRetryEligibility(boolean canRetry, String unavailableReason) {

    public StructuredRetryEligibility {
        unavailableReason = Objects.requireNonNull(unavailableReason, "unavailableReason must not be null");
        if (canRetry && !unavailableReason.isEmpty()) {
            throw new IllegalArgumentException("Eligible retry must not have an unavailable reason");
        }
        if (!canRetry && unavailableReason.isBlank()) {
            throw new IllegalArgumentException("Ineligible retry requires a reader-safe reason");
        }
    }

    public static StructuredRetryEligibility eligible() {
        return new StructuredRetryEligibility(true, "");
    }

    public static StructuredRetryEligibility unavailable(String reason) {
        return new StructuredRetryEligibility(false, reason);
    }
}
