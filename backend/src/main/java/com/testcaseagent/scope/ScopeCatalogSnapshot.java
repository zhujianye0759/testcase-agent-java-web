package com.testcaseagent.scope;

import java.time.Instant;
import java.util.Map;

/**
 * One atomically published immutable catalog and its private coordinate lookup.
 *
 * [Req-ID]: REQ-CAT-003
 */
public record ScopeCatalogSnapshot(
        ScopeCatalogView view,
        Map<String, ScopeSelection> selections,
        Instant loadedAt) {
    public ScopeCatalogSnapshot {
        selections = Map.copyOf(selections);
    }
}
