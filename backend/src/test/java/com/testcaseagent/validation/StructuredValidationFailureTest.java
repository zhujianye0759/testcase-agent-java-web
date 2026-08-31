package com.testcaseagent.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Safe stored-diagnostic catalog and path contract. [Req-ID]: REQ-FSC-007, REQ-ESR-001 */
class StructuredValidationFailureTest {

    @Test
    void recognizesOnlyBoundedSafeJsonPaths() {
        assertThat(StructuredValidationFailure.isSafePath("$.requirement_facts[6].function")).isTrue();
        assertThat(StructuredValidationFailure.isSafePath(null)).isFalse();
        assertThat(StructuredValidationFailure.isSafePath("")).isFalse();
        assertThat(StructuredValidationFailure.isSafePath("$.requirement_facts[*].function")).isFalse();
        assertThat(StructuredValidationFailure.isSafePath("$" + ".field".repeat(86))).isFalse();
    }
}
