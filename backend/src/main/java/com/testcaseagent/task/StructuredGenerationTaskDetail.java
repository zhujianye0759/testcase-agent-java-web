package com.testcaseagent.task;

import com.fasterxml.jackson.annotation.JsonInclude;
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
        String workflowVersion,
        String processingStatus,
        String coverageStatus,
        @JsonInclude(JsonInclude.Include.NON_NULL) ValidationFailure validationFailure,
        StructuredRetryEligibility retryEligibility,
        int pendingCandidateCaseCount,
        @JsonInclude(JsonInclude.Include.NON_NULL) FunctionCandidateSummary functionCandidateSummary,
        PhaseProgress phaseProgress,
        List<ReviewFinding> reviewFindings,
        List<TestabilityFeedback> testabilityFeedback,
        List<Reconciliation> reconciliations,
        List<TestPoint> testPoints,
        @JsonInclude(JsonInclude.Include.NON_NULL) V2Collections v2Collections)
        implements GenerationTaskDetailResponse.StructuredResultPayload {

    public StructuredGenerationTaskDetail {
        Objects.requireNonNull(workflowVersion, "workflowVersion must not be null");
        Objects.requireNonNull(processingStatus, "processingStatus must not be null");
        Objects.requireNonNull(coverageStatus, "coverageStatus must not be null");
        Objects.requireNonNull(retryEligibility, "retryEligibility must not be null");
        if (pendingCandidateCaseCount < 0) throw new IllegalArgumentException("pendingCandidateCaseCount must not be negative");
        Objects.requireNonNull(phaseProgress, "phaseProgress must not be null");
        reviewFindings = List.copyOf(Objects.requireNonNull(reviewFindings, "reviewFindings must not be null"));
        testabilityFeedback = List.copyOf(Objects.requireNonNull(
                testabilityFeedback, "testabilityFeedback must not be null"));
        reconciliations = List.copyOf(Objects.requireNonNull(reconciliations, "reconciliations must not be null"));
        testPoints = List.copyOf(Objects.requireNonNull(testPoints, "testPoints must not be null"));
    }

    /** Independent bounded collections used only by the V2 reader projection. */
    public record V2Collections(
            DetailPage<TestabilityFeedback> testabilityFeedback,
            DetailPage<TestPoint> testPoints,
            DetailPage<Testcase> testcases) {
        public V2Collections {
            Objects.requireNonNull(testabilityFeedback, "testabilityFeedback must not be null");
            Objects.requireNonNull(testPoints, "testPoints must not be null");
            Objects.requireNonNull(testcases, "testcases must not be null");
        }
    }

    /** One stable, zero-based page without storage identities or navigation URLs. */
    public record DetailPage<T>(List<T> items, int page, int size, long totalItems, boolean hasNext) {
        public DetailPage {
            items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
            if (page < 0 || size < 1 || totalItems < 0 || items.size() > size) {
                throw new IllegalArgumentException("Structured detail page metadata is inconsistent");
            }
            long consumed = Math.addExact(Math.multiplyExact((long) page, size), items.size());
            if (hasNext != (consumed < totalItems)) {
                throw new IllegalArgumentException("Structured detail page continuation is inconsistent");
            }
        }
    }

    /** Persisted task-level counts for versioned reader-facing structured phases. */
    public record PhaseProgress(
            PhaseCount materialTraversal,
            PhaseCount factExtraction,
            PhaseCount requirementReview,
            PhaseCount featureReconciliation,
            PhaseCount testcaseDesign) {
        public PhaseProgress {
            Objects.requireNonNull(materialTraversal, "materialTraversal must not be null");
            Objects.requireNonNull(factExtraction, "factExtraction must not be null");
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
            String handlingLevel,
            String affectedScope,
            String badSourceExample,
            String proposedGoodExample,
            String testDesignImpact,
            String currentProjectRecommendation,
            String designCenterGuidelineRecommendation) {
        /** Backward-compatible projection for V12 persisted rows. */
        public ReviewFinding(String sourceLabel, String subject, String issueType, String description,
                String handlingLevel, String testDesignImpact, String currentProjectRecommendation,
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
            String confirmationStatus) {
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
            String basis,
            String generationOutcome,
            List<String> generationMissingInformation,
            List<String> missingInformation,
            boolean formalCoverageSatisfied,
            List<Testcase> testcases) {
        public TestPoint {
            Objects.requireNonNull(generationOutcome, "generationOutcome must not be null");
            generationMissingInformation = List.copyOf(Objects.requireNonNull(
                    generationMissingInformation, "generationMissingInformation must not be null"));
            missingInformation = List.copyOf(Objects.requireNonNull(missingInformation, "missingInformation must not be null"));
            testcases = List.copyOf(Objects.requireNonNull(testcases, "testcases must not be null"));
        }
    }

    /** One stored testcase without its generated key or evidence references. */
    public record Testcase(
            String name,
            String title,
            String priority,
            String status,
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
        public Testcase(String title, String status, List<String> preconditions, List<Step> steps,
                List<String> requirementSummaries, List<String> missingInformation) {
            this(title, title, "中", status, preconditions, Initialization.empty(), List.of(), steps,
                    steps.stream().map(Step::expected).toList(), "", "", List.of(), "", AuthoringInformation.empty(),
                    requirementSummaries, missingInformation);
        }
    }

    /** One non-blocking V2 testability observation without evidence or storage identifiers. */
    public record TestabilityFeedback(
            String functionName,
            String observationType,
            String description,
            List<String> affectedFactTypes) {
        public TestabilityFeedback {
            Objects.requireNonNull(functionName, "functionName must not be null");
            Objects.requireNonNull(observationType, "observationType must not be null");
            Objects.requireNonNull(description, "description must not be null");
            affectedFactTypes = List.copyOf(Objects.requireNonNull(
                    affectedFactTypes, "affectedFactTypes must not be null"));
        }
    }

    /** Candidate-protocol counts and reader-safe gaps; stable keys and reason codes remain server-side. */
    public record FunctionCandidateSummary(
            int acceptedCandidateCount,
            int pendingCandidateCount,
            int rejectedCandidateCount,
            int noFunctionSourceCount,
            int unresolvedSourceCount,
            int incompleteWindowCount,
            List<FunctionCandidateIssue> issues) {
        public FunctionCandidateSummary {
            if (acceptedCandidateCount < 0 || pendingCandidateCount < 0 || rejectedCandidateCount < 0
                    || noFunctionSourceCount < 0 || unresolvedSourceCount < 0 || incompleteWindowCount < 0) {
                throw new IllegalArgumentException("Function candidate counts must not be negative");
            }
            issues = List.copyOf(Objects.requireNonNull(issues, "issues must not be null"));
        }
    }

    /** One Chinese, reader-facing candidate or source gap without an internal source identity. */
    public record FunctionCandidateIssue(
            String subject,
            String status,
            String description,
            List<String> missingInformation) {
        public FunctionCandidateIssue {
            Objects.requireNonNull(subject, "subject must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(description, "description must not be null");
            missingInformation = List.copyOf(Objects.requireNonNull(
                    missingInformation, "missingInformation must not be null"));
        }
    }

    /**
     * Safe field-level reason for a rejected structured result; it never contains rejected source or model text.
     *
     * [Req-ID]: REQ-FSC-007
     */
    public record ValidationFailure(String code, String path, String message) {
        public ValidationFailure {
            Objects.requireNonNull(code, "code must not be null");
            Objects.requireNonNull(path, "path must not be null");
            Objects.requireNonNull(message, "message must not be null");
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
