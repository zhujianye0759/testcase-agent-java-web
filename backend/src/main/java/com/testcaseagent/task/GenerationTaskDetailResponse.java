package com.testcaseagent.task;

import com.testcaseagent.markdown.MarkdownAuditRow;
import com.testcaseagent.markdown.MarkdownTestCaseRow;
import java.util.List;

/** Browser-safe accumulated Markdown task projection. [Req-ID]: REQ-WEB-003, REQ-KAG-006, REQ-SCP-004 */
public record GenerationTaskDetailResponse(
        String id,
        String taskMode,
        String status,
        int totalBatches,
        int completedBatches,
        boolean artifactReady,
        String artifactId,
        String failureSummary,
        FrozenScope frozenScope,
        List<Batch> batches,
        List<AuditRow> auditRows,
        List<TestCaseRow> testCaseRows) {

    public static GenerationTaskDetailResponse from(GenerationTaskDetail detail) {
        return new GenerationTaskDetailResponse(detail.id(), detail.taskMode().name(), detail.status().name(),
                detail.totalBatches(), detail.completedBatches(), detail.artifactReady(), detail.artifactId(), detail.failureSummary(),
                FrozenScope.from(detail.request()), detail.batches().stream().map(Batch::from).toList(),
                detail.acceptedRows().auditRows().stream().map(AuditRow::from).toList(),
                detail.acceptedRows().testCaseRows().stream().map(TestCaseRow::from).toList());
    }

    public record FrozenScope(String state, String materialCategory, String admissionType, int documentCount) {
        private static FrozenScope from(CreateGenerationTaskRequest request) {
            return new FrozenScope("FROZEN", request.requirementScope().materialCategory(),
                    String.join(", ", request.requirementAdmissionTypeKeys()), request.requirementScope().documents().size());
        }
    }
    public record Batch(String status, String failureSummary) {
        private static Batch from(GenerationBatchDetail batch) { return new Batch(batch.status().name(), batch.failureSummary()); }
    }
    public record AuditRow(int sequence, String subjectOrFeature, String issueCategory, String evidenceComparison) {
        private static AuditRow from(MarkdownAuditRow row) { return new AuditRow(row.sequence(), row.subjectOrFeature(), row.issueCategory(), row.evidenceComparison()); }
    }
    public record TestCaseRow(String caseName, String featureModule, String preconditions, String executionSteps,
            String expectedResult, String requirementContent) {
        private static TestCaseRow from(MarkdownTestCaseRow row) {
            return new TestCaseRow(row.caseName(), row.featureModule(), row.preconditions(), row.executionSteps(), row.expectedResult(), row.requirementContent());
        }
    }
}
