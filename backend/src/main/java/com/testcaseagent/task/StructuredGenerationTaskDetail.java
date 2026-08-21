package com.testcaseagent.task;

import com.testcaseagent.structuredgeneration.StructuredCoverageStatus;
import com.testcaseagent.structuredgeneration.StructuredProcessingStatus;
import com.testcaseagent.validation.FeatureReconciliationValidator.ConfirmationStatus;
import com.testcaseagent.validation.FunctionalTestcaseResultValidator.Basis;
import com.testcaseagent.validation.FunctionalTestcaseResultValidator.CaseStatus;
import com.testcaseagent.validation.RequirementMaterialReviewValidator.HandlingLevel;
import java.util.List;
import java.util.Objects;

/**
 * Reader-safe projection of Java-validated structured-generation rows.
 *
 * <p>It deliberately has no model payload, stable key, evidence coordinate, or storage identifier.</p>
 *
 * [Req-ID]: REQ-STG-006, REQ-FTG-007, REQ-FTG-009
 */
public record StructuredGenerationTaskDetail(
        StructuredProcessingStatus processingStatus,
        StructuredCoverageStatus coverageStatus,
        int pendingCandidateCaseCount,
        PhaseProgress phaseProgress,
        List<ReviewFinding> reviewFindings,
        List<Reconciliation> reconciliations,
        List<TestPoint> testPoints) {

    public StructuredGenerationTaskDetail {
        Objects.requireNonNull(processingStatus, "processingStatus must not be null");
        Objects.requireNonNull(coverageStatus, "coverageStatus must not be null");
        if (pendingCandidateCaseCount < 0) throw new IllegalArgumentException("pendingCandidateCaseCount must not be negative");
        Objects.requireNonNull(phaseProgress, "phaseProgress must not be null");
        reviewFindings = List.copyOf(Objects.requireNonNull(reviewFindings, "reviewFindings must not be null"));
        reconciliations = List.copyOf(Objects.requireNonNull(reconciliations, "reconciliations must not be null"));
        testPoints = List.copyOf(Objects.requireNonNull(testPoints, "testPoints must not be null"));
    }

    /** Persisted task-level counts for the four reader-facing structured phases. */
    public record PhaseProgress(
            PhaseCount materialTraversal,
            PhaseCount requirementReview,
            PhaseCount featureReconciliation,
            PhaseCount testcaseDesign) {
        public PhaseProgress {
            Objects.requireNonNull(materialTraversal, "materialTraversal must not be null");
            Objects.requireNonNull(requirementReview, "requirementReview must not be null");
            Objects.requireNonNull(featureReconciliation, "featureReconciliation must not be null");
            Objects.requireNonNull(testcaseDesign, "testcaseDesign must not be null");
        }
    }

    /** One phase's durable registered, completed, and failed work counts. */
    public record PhaseCount(int total, int completed, int failed) {
        public PhaseCount {
            if (total < 0 || completed < 0 || failed < 0 || completed + failed > total) {
                throw new IllegalArgumentException("Structured phase counts are inconsistent");
            }
        }
    }

    /** A requirement-material finding represented exclusively by reader-facing text. */
    public record ReviewFinding(
            String sourceLabel,
            String subject,
            String issueType,
            String description,
            HandlingLevel handlingLevel,
            String affectedScope,
            String badSourceExample,
            String proposedGoodExample,
            String testDesignImpact,
            String currentProjectRecommendation,
            String designCenterGuidelineRecommendation) {
        /** Backward-compatible projection for V12 persisted rows. */
        public ReviewFinding(String sourceLabel, String subject, String issueType, String description,
                HandlingLevel handlingLevel, String testDesignImpact, String currentProjectRecommendation,
                String designCenterGuidelineRecommendation) {
            this(sourceLabel, subject, issueType, description, handlingLevel, "", "", "", testDesignImpact,
                    currentProjectRecommendation, designCenterGuidelineRecommendation);
        }
    }

    /** A function/requirement reconciliation without its internal source references. */
    public record Reconciliation(
            List<String> functionListPaths,
            List<String> requirementFunctions,
            String classification,
            String scopeRecommendation,
            ConfirmationStatus confirmationStatus) {
        public Reconciliation {
            functionListPaths = List.copyOf(Objects.requireNonNull(functionListPaths, "functionListPaths must not be null"));
            requirementFunctions = List.copyOf(Objects.requireNonNull(requirementFunctions, "requirementFunctions must not be null"));
        }
    }

    /** A test-point result with its business-readable case candidates. */
    public record TestPoint(
            String functionName,
            String type,
            String description,
            Basis basis,
            List<String> missingInformation,
            boolean formalCoverageSatisfied,
            List<Testcase> testcases) {
        public TestPoint {
            missingInformation = List.copyOf(Objects.requireNonNull(missingInformation, "missingInformation must not be null"));
            testcases = List.copyOf(Objects.requireNonNull(testcases, "testcases must not be null"));
        }
    }

    /** One stored testcase without its generated key or evidence references. */
    public record Testcase(
            String name,
            String title,
            String priority,
            CaseStatus status,
            List<String> preconditions,
            Initialization initialization,
            List<TestInput> inputs,
            List<Step> steps,
            List<String> expectedResults,
            String evaluationCriteria,
            String resultEvaluationCriteria,
            List<String> terminationConditions,
            String resultCollection,
            AuthoringInformation authoringInformation,
            List<String> requirementSummaries,
            List<String> missingInformation) {
        public Testcase {
            preconditions = List.copyOf(Objects.requireNonNull(preconditions, "preconditions must not be null"));
            Objects.requireNonNull(initialization, "initialization must not be null");
            inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs must not be null"));
            steps = List.copyOf(Objects.requireNonNull(steps, "steps must not be null"));
            expectedResults = List.copyOf(Objects.requireNonNull(expectedResults, "expectedResults must not be null"));
            terminationConditions = List.copyOf(Objects.requireNonNull(terminationConditions, "terminationConditions must not be null"));
            Objects.requireNonNull(authoringInformation, "authoringInformation must not be null");
            requirementSummaries = List.copyOf(Objects.requireNonNull(requirementSummaries, "requirementSummaries must not be null"));
            missingInformation = List.copyOf(Objects.requireNonNull(missingInformation, "missingInformation must not be null"));
        }

        /** Backward-compatible projection for V12 persisted rows. */
        public Testcase(String title, CaseStatus status, List<String> preconditions, List<Step> steps,
                List<String> requirementSummaries, List<String> missingInformation) {
            this(title, title, "中", status, preconditions, Initialization.empty(), List.of(), steps,
                    steps.stream().map(Step::expected).toList(), "", "", List.of(), "", AuthoringInformation.empty(),
                    requirementSummaries, missingInformation);
        }
    }

    /** One browser-readable testcase step. */
    public record Step(int stepNo, String action, String expected, String evaluationCriteria,
            String terminationOrError, String resultCollection) {
        public Step(int stepNo, String action, String expected) { this(stepNo, action, expected, "", "", ""); }
    }

    /** Four explicit reader-safe initialization categories. */
    public record Initialization(List<String> hardwareConfiguration, List<String> softwareConfiguration,
            List<String> testConfiguration, List<String> parameterConfiguration) {
        public Initialization {
            hardwareConfiguration = List.copyOf(hardwareConfiguration);
            softwareConfiguration = List.copyOf(softwareConfiguration);
            testConfiguration = List.copyOf(testConfiguration);
            parameterConfiguration = List.copyOf(parameterConfiguration);
        }
        public static Initialization empty() { return new Initialization(List.of(), List.of(), List.of(), List.of()); }
    }

    /** One test input with Chinese presentation labels rather than internal enums. */
    public record TestInput(String content, String nature, String source, String method,
            String authenticity, String sequence) { }

    /** Optional author/date echoed from the request. */
    public record AuthoringInformation(String author, String date) {
        public static AuthoringInformation empty() { return new AuthoringInformation("", ""); }
    }
}
