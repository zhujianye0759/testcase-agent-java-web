package com.testcaseagent.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
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

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void roundTripsOnlyCanonicalBoundedDirectEvidenceReasonsAndKeepsThePublicMessageStable() {
        var failure = StructuredValidationFailure.directEvidence(
                "$.requirement_facts[0].statement",
                List.of(StructuredValidationFailure.DirectEvidenceReason.CLAUSE_COUNT_MISMATCH,
                        StructuredValidationFailure.DirectEvidenceReason.LITERAL_UNSUPPORTED));

        assertThat(failure.storageMessage()).endsWith(
                "|direct_evidence_reasons=LITERAL_UNSUPPORTED,CLAUSE_COUNT_MISMATCH");
        assertThat(failure.message()).isEqualTo("需求事实正文未由任一引用材料单元直接支撑")
                .doesNotContain("LITERAL_UNSUPPORTED");
        assertThat(StructuredValidationFailure.fromStored(
                StructuredValidationFailure.Code.FACT_DIRECT_EVIDENCE_UNSUPPORTED,
                failure.path(), failure.storageMessage()).storageMessage()).isEqualTo(failure.storageMessage());
        assertThat(StructuredValidationFailure.fromStored(
                StructuredValidationFailure.Code.FACT_DIRECT_EVIDENCE_UNSUPPORTED,
                failure.path(), failure.message()).storageMessage()).isEqualTo(failure.message());
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void rejectsUnknownDuplicateOutOfOrderOrWrongCodeReasonStorage() {
        String message = "需求事实正文未由任一引用材料单元直接支撑|direct_evidence_reasons=";
        for (String malformed : List.of(
                message + "UNKNOWN",
                message + "LITERAL_UNSUPPORTED,LITERAL_UNSUPPORTED",
                message + "CLAUSE_COUNT_MISMATCH,LITERAL_UNSUPPORTED",
                message)) {
            assertThatThrownBy(() -> StructuredValidationFailure.fromStored(
                    StructuredValidationFailure.Code.FACT_DIRECT_EVIDENCE_UNSUPPORTED,
                    "$.requirement_facts[0].statement", malformed))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> StructuredValidationFailure.fromStored(
                StructuredValidationFailure.Code.FACT_ATOMICITY_INVALID,
                "$.requirement_facts[0].statement", message + "LITERAL_UNSUPPORTED"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** [Req-ID]: REQ-TGV2-012 */
    @Test
    void rejectsUnknownStoredReasonsWithoutRetainingTheirValueInTheExceptionChain() {
        String sensitiveMarker = "private-material-marker";
        Throwable rejected = catchThrowable(() -> StructuredValidationFailure.fromStored(
                StructuredValidationFailure.Code.FACT_DIRECT_EVIDENCE_UNSUPPORTED,
                "$.requirement_facts[0].statement",
                "需求事实正文未由任一引用材料单元直接支撑|direct_evidence_reasons=" + sensitiveMarker));

        assertThat(rejected).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Direct-evidence reason is not recognized")
                .hasNoCause();
        assertThat(rejected.toString()).doesNotContain(sensitiveMarker);
    }
}
