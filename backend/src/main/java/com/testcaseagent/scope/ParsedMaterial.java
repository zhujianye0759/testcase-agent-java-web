package com.testcaseagent.scope;

import java.util.List;
import java.util.Objects;

/**
 * Complete parsed material read from the current authorized document view.
 *
 * [Req-ID]: REQ-SMR-001, REQ-SMR-002, REQ-SMR-003
 */
public record ParsedMaterial(String knowledgeId, int totalUnits, List<ParsedMaterialUnit> units) {

    public ParsedMaterial {
        knowledgeId = Objects.requireNonNull(knowledgeId, "knowledgeId must not be null");
        units = List.copyOf(Objects.requireNonNull(units, "units must not be null"));
    }
}
