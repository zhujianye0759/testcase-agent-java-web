package com.testcaseagent.featureaudit;

import java.util.List;
import java.util.Objects;

/**
 * One bounded, not-yet-published page of a task-owned material inventory.
 *
 * <p>Pages are durable restart checkpoints. They become formal evidence only when the repository verifies and
 * atomically publishes the complete frozen document set.</p>
 *
 * [Req-ID]: REQ-TGV2-003
 */
public record MaterialInventoryPage(
        String documentId,
        String knowledgeId,
        String documentRole,
        int totalUnits,
        boolean terminalPage,
        List<MaterialInventoryUnit> units) {

    public MaterialInventoryPage {
        documentId = requireText(documentId, "documentId");
        knowledgeId = requireText(knowledgeId, "knowledgeId");
        documentRole = requireText(documentRole, "documentRole");
        if (totalUnits < 0) {
            throw new IllegalArgumentException("totalUnits must not be negative");
        }
        units = List.copyOf(Objects.requireNonNull(units, "units must not be null"));
        for (MaterialInventoryUnit unit : units) {
            if (!documentId.equals(unit.documentId()) || !documentRole.equals(unit.documentRole())) {
                throw new IllegalArgumentException("Material inventory page units must match their document");
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
