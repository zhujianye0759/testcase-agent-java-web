package com.testcaseagent.validation;

import java.util.Objects;

/** Business-validation rejection whose message and field path are safe to persist. [Req-ID]: REQ-FSC-007 */
public final class StructuredValidationException extends IllegalArgumentException {
    private final StructuredValidationFailure failure;

    /** Creates an exception from an already enumerated safe diagnostic. */
    public StructuredValidationException(StructuredValidationFailure failure) {
        super(Objects.requireNonNull(failure, "failure must not be null").message());
        this.failure = failure;
    }

    /** Returns the only diagnostic payload that callers may persist or log. */
    public StructuredValidationFailure failure() {
        return failure;
    }

    @Override
    public String toString() {
        return getClass().getName() + ": " + failure;
    }
}
