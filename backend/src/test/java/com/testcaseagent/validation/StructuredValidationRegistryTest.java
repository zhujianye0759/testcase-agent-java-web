package com.testcaseagent.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Tests task-local key and evidence ownership gates. [Req-ID]: REQ-STG-001 */
class StructuredValidationRegistryTest {

    @Test
    void rejectsEvidenceOutsideTheCurrentTaskMaterialAndTraversal() {
        StructuredValidationRegistry registry = StructuredValidationRegistry.forTask("task-1")
                .register(StructuredKeyType.MATERIAL, "material-1")
                .register(StructuredKeyType.UNIT, "unit-1")
                .registerEvidence(new StructuredEvidence("evidence-ok", "task-1", "material-1", false, false, true))
                .registerEvidence(new StructuredEvidence("evidence-example", "task-1", "material-1", true, false, true))
                .registerEvidence(new StructuredEvidence("evidence-retired", "task-1", "material-1", false, true, true))
                .registerEvidence(new StructuredEvidence("evidence-unread", "task-1", "material-1", false, false, false));

        assertThrows(IllegalArgumentException.class, () -> registry.registerEvidence(
                new StructuredEvidence("evidence-other-task", "task-2", "material-1", false, false, true)));
        assertDoesNotThrow(() -> registry.requireEvidence("evidence-ok", "material-1"));
        assertThrows(IllegalArgumentException.class, () -> registry.requireEvidence("evidence-other-task", "material-1"));
        assertThrows(IllegalArgumentException.class, () -> registry.requireEvidence("evidence-example", "material-1"));
        assertThrows(IllegalArgumentException.class, () -> registry.requireEvidence("evidence-retired", "material-1"));
        assertThrows(IllegalArgumentException.class, () -> registry.requireEvidence("evidence-unread", "material-1"));
    }

    @Test
    void rejectsUnknownAndDuplicateTaskKeys() {
        StructuredValidationRegistry registry = StructuredValidationRegistry.forTask("task-1")
                .register(StructuredKeyType.FUNCTION, "function-1");

        assertDoesNotThrow(() -> registry.require(StructuredKeyType.FUNCTION, "function-1"));
        assertThrows(IllegalArgumentException.class, () -> registry.require(StructuredKeyType.FUNCTION, "unknown"));
        assertThrows(IllegalArgumentException.class, () -> registry.register(StructuredKeyType.FUNCTION, "function-1"));
    }

    @Test
    void keepsTheSameTextualKeyIsolatedAcrossBusinessTypes() {
        StructuredValidationRegistry registry = StructuredValidationRegistry.forTask("task-1")
                .register(StructuredKeyType.REQUIREMENT_FACT, "shared-key")
                .register(StructuredKeyType.REVIEW_FINDING, "shared-key");

        assertDoesNotThrow(() -> registry.require(StructuredKeyType.REQUIREMENT_FACT, "shared-key"));
        assertDoesNotThrow(() -> registry.require(StructuredKeyType.REVIEW_FINDING, "shared-key"));
        assertThrows(IllegalArgumentException.class,
                () -> registry.require(StructuredKeyType.TESTCASE, "shared-key"));
    }
}
