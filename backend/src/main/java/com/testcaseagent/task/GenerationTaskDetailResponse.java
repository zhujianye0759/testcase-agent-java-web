package com.testcaseagent.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.testcaseagent.markdown.MarkdownAuditRow;
import com.testcaseagent.markdown.MarkdownTestCaseRow;
import java.util.List;

/** Browser-safe accumulated Markdown task projection. [Req-ID]: REQ-WEB-003, REQ-KAG-006, REQ-SCP-004, REQ-CWR-001 */
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
        List<TestCaseRow> testCaseRows,
        GenerationTaskBusinessProgress businessProgress,
        @JsonInclude(JsonInclude.Include.NON_NULL) StructuredResultPayload structuredResult) {

    public static GenerationTaskDetailResponse from(GenerationTaskDetail detail) {
        return new GenerationTaskDetailResponse(detail.id(), detail.taskMode().name(), detail.status().name(),
                detail.totalBatches(), detail.completedBatches(), detail.artifactReady(), detail.artifactId(), detail.failureSummary(),
                FrozenScope.from(detail.request()), detail.batches().stream().map(Batch::from).toList(),
                detail.acceptedRows().auditRows().stream().map(AuditRow::from).toList(),
                detail.acceptedRows().testCaseRows().stream().map(TestCaseRow::from).toList(), detail.businessProgress(),
                structuredResultPayload(detail.structuredResult()));
    }

    /** Version-specific browser payload; V1 keeps its original JSON shape while V2 uses the richer projection. */
    public sealed interface StructuredResultPayload permits StructuredGenerationTaskDetail, LegacyStructuredResult { }

    /** Exact historical V1 payload retained for clients and saved-task readers. [Req-ID]: REQ-TGV2-009, REQ-TGV2-010 */
    public record LegacyStructuredResult(
            String processingStatus,
            String coverageStatus,
            int pendingCandidateCaseCount,
            LegacyPhaseProgress phaseProgress,
            List<LegacyReviewFinding> reviewFindings,
            List<LegacyReconciliation> reconciliations,
            List<LegacyTestPoint> testPoints) implements StructuredResultPayload { }

    public record LegacyPhaseProgress(
            StructuredGenerationTaskDetail.PhaseCount materialTraversal,
            StructuredGenerationTaskDetail.PhaseCount requirementReview,
            StructuredGenerationTaskDetail.PhaseCount featureReconciliation,
            StructuredGenerationTaskDetail.PhaseCount testcaseDesign) { }

    public record LegacyReviewFinding(
            String sourceLabel, String subject, String issueType, String description, String handlingLevel,
            String affectedScope, String badSourceExample, String proposedGoodExample, String testDesignImpact,
            String currentProjectRecommendation, String designCenterGuidelineRecommendation) { }

    public record LegacyReconciliation(
            List<String> functionListPaths, List<String> requirementFunctions, String classification,
            String scopeRecommendation, String confirmationStatus) { }

    public record LegacyTestPoint(
            String functionName, String type, String description, String basis, List<String> missingInformation,
            boolean formalCoverageSatisfied, List<LegacyTestcase> testcases) { }

    public record LegacyTestcase(
            String name, String title, String priority, String status, List<String> preconditions,
            StructuredGenerationTaskDetail.Initialization initialization,
            List<StructuredGenerationTaskDetail.TestInput> inputs,
            List<StructuredGenerationTaskDetail.Step> steps,
            List<String> expectedResults, String evaluationCriteria, String resultEvaluationCriteria,
            List<String> terminationConditions, String resultCollection,
            StructuredGenerationTaskDetail.AuthoringInformation authoringInformation,
            List<String> requirementSummaries, List<String> missingInformation) { }

    private static StructuredResultPayload structuredResultPayload(StructuredGenerationTaskDetail detail) {
        if (detail == null || GenerationContractVersions.V2.equals(detail.workflowVersion())) return detail;
        var phase = detail.phaseProgress();
        return new LegacyStructuredResult(legacyProcessingStatus(detail.processingStatus()),
                legacyCoverageStatus(detail.coverageStatus()), detail.pendingCandidateCaseCount(),
                new LegacyPhaseProgress(phase.materialTraversal(), phase.requirementReview(),
                        phase.featureReconciliation(), phase.testcaseDesign()),
                detail.reviewFindings().stream().map(finding -> new LegacyReviewFinding(
                        finding.sourceLabel(), finding.subject(), finding.issueType(), finding.description(),
                        legacyHandlingLevel(finding.handlingLevel()), finding.affectedScope(), finding.badSourceExample(),
                        finding.proposedGoodExample(), finding.testDesignImpact(), finding.currentProjectRecommendation(),
                        finding.designCenterGuidelineRecommendation())).toList(),
                detail.reconciliations().stream().map(value -> new LegacyReconciliation(
                        value.functionListPaths(), value.requirementFunctions(),
                        legacyReconciliationClassification(value.classification()), value.scopeRecommendation(),
                        legacyConfirmationStatus(value.confirmationStatus()))).toList(),
                detail.testPoints().stream().map(GenerationTaskDetailResponse::legacyTestPoint).toList());
    }

    private static LegacyTestPoint legacyTestPoint(StructuredGenerationTaskDetail.TestPoint point) {
        return new LegacyTestPoint(point.functionName(), point.type(), point.description(), legacyBasis(point.basis()),
                point.missingInformation(), point.formalCoverageSatisfied(),
                point.testcases().stream().map(value -> new LegacyTestcase(
                        value.name(), value.title(), value.priority(), legacyCaseStatus(value.status()),
                        value.preconditions(), value.initialization(), value.inputs(), value.steps(), value.expectedResults(),
                        value.evaluationCriteria(), value.resultEvaluationCriteria(), value.terminationConditions(),
                        value.resultCollection(), value.authoringInformation(), value.requirementSummaries(),
                        value.missingInformation())).toList());
    }

    private static String legacyProcessingStatus(String value) {
        return switch (value) {
            case "待处理" -> "PENDING";
            case "处理中" -> "RUNNING";
            case "已完成" -> "COMPLETED";
            case "失败" -> "FAILED";
            case "已取消" -> "CANCELLED";
            default -> throw new IllegalStateException("Unknown V1 processing status");
        };
    }

    private static String legacyCoverageStatus(String value) {
        return switch (value) {
            case "正式覆盖待完成" -> "PENDING";
            case "正式覆盖完整" -> "COMPLETE";
            case "正式覆盖部分完整" -> "PARTIAL";
            case "正式覆盖无法生成" -> "UNABLE_TO_GENERATE";
            default -> throw new IllegalStateException("Unknown V1 coverage status");
        };
    }

    private static String legacyHandlingLevel(String value) {
        return switch (value) {
            case "阻断" -> "BLOCKING";
            case "继续执行但信息不完整" -> "CONTINUE_INCOMPLETE";
            case "改进建议" -> "IMPROVEMENT";
            default -> throw new IllegalStateException("Unknown V1 handling level");
        };
    }

    private static String legacyConfirmationStatus(String value) {
        return switch (value) {
            case "已确认" -> "CONFIRMED";
            case "待确认" -> "PENDING_CONFIRMATION";
            default -> throw new IllegalStateException("Unknown V1 confirmation status");
        };
    }

    private static String legacyBasis(String value) {
        return switch (value) {
            case "正式需求依据" -> "FORMAL_REQUIREMENT";
            case "通用经验依据" -> "GENERAL_EXPERIENCE";
            default -> throw new IllegalStateException("Unknown V1 testcase basis");
        };
    }

    private static String legacyCaseStatus(String value) {
        return switch (value) {
            case "正式用例" -> "FORMAL";
            case "待确认用例" -> "PENDING_CONFIRMATION";
            default -> throw new IllegalStateException("Unknown V1 testcase status");
        };
    }

    private static String legacyReconciliationClassification(String value) {
        return switch (value) {
            case "完全一致" -> "EXACT_MATCH";
            case "仅功能清单存在" -> "FUNCTION_LIST_ONLY";
            case "仅需求材料存在" -> "REQUIREMENTS_ONLY";
            case "范围冲突" -> "CONFLICT";
            case "重复功能" -> "DUPLICATE";
            case "建议拆分" -> "SPLIT";
            case "建议合并" -> "MERGE";
            case "证据不足" -> "INSUFFICIENT_EVIDENCE";
            default -> throw new IllegalStateException("Unknown V1 reconciliation classification");
        };
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
