package com.testcaseagent.featureaudit;

import java.util.Objects;

/**
 * One application-owned, authorized parsed text unit retained for a task's material audit.
 *
 * [Req-ID]: REQ-BFA-001
 */
public record MaterialInventoryUnit(
        String documentId,
        String documentRole,
        String unitId,
        int chunkIndex,
        int ordinal,
        String content,
        long startAt,
        long endAt) {

    public MaterialInventoryUnit {
        documentId = Objects.requireNonNull(documentId, "documentId must not be null");
        documentRole = Objects.requireNonNull(documentRole, "documentRole must not be null");
        unitId = Objects.requireNonNull(unitId, "unitId must not be null");
        content = Objects.requireNonNull(content, "content must not be null");
        if (chunkIndex < 0 || ordinal <= 0 || startAt < 0 || endAt < startAt) {
            throw new IllegalArgumentException("Material inventory coordinates are invalid");
        }
    }
}
