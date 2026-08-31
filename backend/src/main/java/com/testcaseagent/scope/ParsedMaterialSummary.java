package com.testcaseagent.scope;

import java.util.Objects;

/**
 * Terminal identity returned only after every parsed-material page passes the remote traversal contract.
 *
 * [Req-ID]: REQ-TGV2-003
 */
public record ParsedMaterialSummary(String knowledgeId, int totalUnits) {

    public ParsedMaterialSummary {
        knowledgeId = Objects.requireNonNull(knowledgeId, "knowledgeId must not be null");
        if (knowledgeId.isBlank() || totalUnits < 0) {
            throw new IllegalArgumentException("Parsed material summary is invalid");
        }
    }
}
