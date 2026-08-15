package com.testcaseagent.featureaudit;

import java.util.List;
import java.util.Objects;

/**
 * One fully enumerated document retained in a task-owned material inventory.
 *
 * [Req-ID]: REQ-SMR-002, REQ-SMR-003, REQ-BFA-001
 */
public record MaterialInventoryDocument(
        String documentId,
        String knowledgeId,
        String documentRole,
        int totalUnits,
        boolean complete,
        List<MaterialInventoryUnit> units) {

    public MaterialInventoryDocument {
        documentId = requireText(documentId, "documentId");
        knowledgeId = requireText(knowledgeId, "knowledgeId");
        documentRole = requireText(documentRole, "documentRole");
        if (totalUnits < 0) {
            throw new IllegalArgumentException("totalUnits must not be negative");
        }
        units = List.copyOf(Objects.requireNonNull(units, "units must not be null"));
        if (!complete || units.size() != totalUnits) {
            throw new IllegalArgumentException("A material inventory document must be complete with its exact unit count");
        }
        for (MaterialInventoryUnit unit : units) {
            if (!documentId.equals(unit.documentId()) || !documentRole.equals(unit.documentRole())) {
                throw new IllegalArgumentException("Material inventory units must match their document summary");
            }
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
