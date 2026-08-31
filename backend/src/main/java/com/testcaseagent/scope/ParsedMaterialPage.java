package com.testcaseagent.scope;

import java.util.List;
import java.util.Objects;

/**
 * One bounded page from a frozen parsed-material traversal.
 *
 * <p>The page owns its immutable unit list; callers may persist it immediately and release its text before the
 * next remote page is read. {@code complete} only describes the remote traversal page. It does not publish the
 * task-owned material inventory.</p>
 *
 * [Req-ID]: REQ-TGV2-003
 */
public record ParsedMaterialPage(
        String knowledgeId,
        int totalUnits,
        List<ParsedMaterialUnit> units,
        boolean complete) {

    public ParsedMaterialPage {
        knowledgeId = Objects.requireNonNull(knowledgeId, "knowledgeId must not be null");
        if (knowledgeId.isBlank() || totalUnits < 0) {
            throw new IllegalArgumentException("Parsed material page identity or total is invalid");
        }
        units = List.copyOf(Objects.requireNonNull(units, "units must not be null"));
    }
}
