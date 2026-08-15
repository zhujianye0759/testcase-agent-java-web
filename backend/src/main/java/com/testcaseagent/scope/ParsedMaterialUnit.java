package com.testcaseagent.scope;

import java.util.Objects;

/**
 * One persisted knowledge-engine text chunk and its retained chunk evidence coordinate.
 *
 * [Req-ID]: REQ-SMR-001, REQ-SMR-003
 */
public record ParsedMaterialUnit(
        String unitId,
        int chunkIndex,
        int ordinal,
        String content,
        long startAt,
        long endAt) {

    public ParsedMaterialUnit {
        unitId = Objects.requireNonNull(unitId, "unitId must not be null");
        content = Objects.requireNonNull(content, "content must not be null");
    }
}
