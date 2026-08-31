package com.testcaseagent.export;

/** One already-validated material-review or feature-reconciliation business projection. [Req-ID]: REQ-SGD-003 */
public record StructuredReviewRow(
        String sourceId, int sequence, Source source, String subject, String classification,
        String affectedScope, String summary, String badSourceExample, String proposedGoodExample,
        String testDesignImpact, String currentProjectRecommendation,
        String designCenterGuidelineRecommendation, String severity, String evidenceSource, boolean validated) {

    /** Backward-compatible projection for persisted V12 rows. */
    public StructuredReviewRow(String sourceId, int sequence, Source source, String subject,
            String classification, String summary, boolean validated) {
        this(sourceId, sequence, source, subject, classification, "", summary, "", "", "", "", "", "", "", validated);
    }

    /** Origin of a reader-facing first-sheet row. */
    public enum Source {
        REQUIREMENT_MATERIAL_REVIEW("材料审查"),
        FUNCTION_CANDIDATE_AUDIT("功能候选审查"),
        FEATURE_RECONCILIATION("功能核对"),
        /** V2 generation-time feedback is non-blocking and replaces the old admission-review projection. */
        TESTABILITY_FEEDBACK("需求可测性反馈"),
        /** V2 pending-only and unable outcomes remain visible without entering the formal testcase sheet. */
        GENERATION_OUTCOME("用例生成结果");

        private final String display;

        Source(String display) {
            this.display = display;
        }

        String display() {
            return display;
        }
    }
}
