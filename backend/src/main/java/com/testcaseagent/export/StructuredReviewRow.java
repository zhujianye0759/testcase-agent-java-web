package com.testcaseagent.export;

/** One already-validated material-review or feature-reconciliation business projection. [Req-ID]: REQ-SGD-003 */
public record StructuredReviewRow(
        String sourceId, int sequence, Source source, String subject, String classification, String summary, boolean validated) {

    /** Origin of a reader-facing first-sheet row. */
    public enum Source {
        REQUIREMENT_MATERIAL_REVIEW("材料审查"),
        FEATURE_RECONCILIATION("功能核对");

        private final String display;

        Source(String display) {
            this.display = display;
        }

        String display() {
            return display;
        }
    }
}
