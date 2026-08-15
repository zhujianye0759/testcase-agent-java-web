package com.testcaseagent.scope;

/**
 * Raised when KEE cannot provide a complete, trustworthy catalog snapshot.
 *
 * [Req-ID]: REQ-CAT-003
 */
public final class ScopeCatalogUnavailableException extends RuntimeException {
    public ScopeCatalogUnavailableException(String message) {
        super(message);
    }

    public ScopeCatalogUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
