package com.testcaseagent.structuredgeneration;

import com.testcaseagent.knowledgeagent.FeatureScopeReconciliationResult;
import com.testcaseagent.knowledgeagent.FunctionListExtractionResult;
import com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignResult;
import com.testcaseagent.knowledgeagent.RequirementMaterialQualityReviewResult;
import com.testcaseagent.validation.FeatureReconciliationValidator;
import com.testcaseagent.validation.FunctionListExtractionValidator;
import com.testcaseagent.validation.FunctionalTestcaseResultValidator;
import com.testcaseagent.validation.RequirementMaterialReviewValidator;

/** Maps structurally checked KEE DTOs into the separate Java business-validation model without dropping fields. [Req-ID]: REQ-FTG-006, REQ-FTG-007 */
public final class StructuredSkillResultMapper {
    private StructuredSkillResultMapper() { }

    /** Maps every requirement fact and review finding field. */
    public static RequirementMaterialReviewValidator.Result review(RequirementMaterialQualityReviewResult result) {
        return new RequirementMaterialReviewValidator.Result(
                result.requirementFacts().stream().map(fact -> new RequirementMaterialReviewValidator.RequirementFact(
                        fact.factKey(), fact.function(), fact.roles(), fact.triggerConditions(), fact.inputs(),
                        fact.businessRules(), fact.outputs(), fact.permissions(), fact.stateChanges(),
                        fact.exceptionHandling(), fact.externalDependencies(), fact.evidenceKeys())).toList(),
                result.reviewFindings().stream().map(finding -> new RequirementMaterialReviewValidator.ReviewFinding(
                        finding.findingKey(), RequirementMaterialReviewValidator.RootCauseKind.valueOf(finding.rootCauseKind().name()),
                        finding.issueType(), new RequirementMaterialReviewValidator.AffectedScope(
                                finding.affectedScope().unitKeys(), finding.affectedScope().summary()),
                        new RequirementMaterialReviewValidator.BadSourceExample(
                                finding.badSourceExample().evidenceKey(), finding.badSourceExample().quote()),
                        new RequirementMaterialReviewValidator.ProposedGoodExample(
                                RequirementMaterialReviewValidator.ProposalStatus.valueOf(finding.proposedGoodExample().status().name()),
                                finding.proposedGoodExample().text()),
                        finding.description(), finding.evidenceKeys(),
                        finding.testDesignImpact(), finding.currentProjectRecommendation(),
                        finding.designCenterGuidelineRecommendation(),
                        RequirementMaterialReviewValidator.HandlingLevel.valueOf(finding.handlingLevel().name()))).toList());
    }

    /** Maps the model-owned extraction rows; no item key exists at this stage. */
    public static FunctionListExtractionValidator.Result extraction(FunctionListExtractionResult result) {
        return new FunctionListExtractionValidator.Result(result.functionListItems().stream()
                .map(row -> new FunctionListExtractionValidator.ModelItem(
                        row.path(), row.description(), row.evidenceKeys(), row.targetQuote()))
                .toList());
    }

    /** Maps every reconciliation relation and preserves its terminal classification. */
    public static FeatureReconciliationValidator.Result reconciliation(FeatureScopeReconciliationResult result) {
        return new FeatureReconciliationValidator.Result(result.reconciliations().stream()
                .map(row -> new FeatureReconciliationValidator.Reconciliation(
                        row.reconciliationKey(), row.functionListItemKeys(), row.requirementFactKeys(),
                        FeatureReconciliationValidator.Classification.valueOf(row.classification().name()),
                        row.evidenceKeys(), row.scopeRecommendation(),
                        FeatureReconciliationValidator.ConfirmationStatus.valueOf(row.confirmationStatus().name())))
                .toList());
    }

    /** Maps every testcase, step, reference, status and missing-information field. */
    public static FunctionalTestcaseResultValidator.Result testcases(FunctionalTestcaseDesignResult result) {
        return new FunctionalTestcaseResultValidator.Result(result.functionKey(), result.testPointKey(),
                result.testcases().stream().map(testcase -> new FunctionalTestcaseResultValidator.Testcase(
                        testcase.caseKey(), testcase.name(), testcase.title(),
                        FunctionalTestcaseResultValidator.Priority.valueOf(testcase.priority().name()),
                        testcase.preconditions(), new FunctionalTestcaseResultValidator.Initialization(
                                testcase.initialization().hardwareConfiguration(), testcase.initialization().softwareConfiguration(),
                                testcase.initialization().testConfiguration(), testcase.initialization().parameterConfiguration()),
                        testcase.inputs().stream().map(input -> new FunctionalTestcaseResultValidator.Input(
                                input.content(), FunctionalTestcaseResultValidator.InputNature.valueOf(input.nature().name()),
                                FunctionalTestcaseResultValidator.InputSource.valueOf(input.source().name()),
                                FunctionalTestcaseResultValidator.TestMethod.valueOf(input.method().name()),
                                FunctionalTestcaseResultValidator.Authenticity.valueOf(input.authenticity().name()),
                                input.sequence())).toList(), testcase.steps().stream()
                                .map(step -> new FunctionalTestcaseResultValidator.Step(
                                        step.stepNo(), step.action(), step.expected(), step.evaluationCriteria(),
                                        step.terminationOrError(), step.resultCollection()))
                                .toList(),
                        testcase.expectedResults(), testcase.evaluationCriteria(), testcase.resultEvaluationCriteria(),
                        testcase.terminationConditions(), testcase.resultCollection(),
                        new FunctionalTestcaseResultValidator.AuthoringInformation(
                                testcase.authoringInformation().author(), testcase.authoringInformation().date()),
                        testcase.requirementFactKeys(), testcase.evidenceKeys(),
                        FunctionalTestcaseResultValidator.CaseStatus.valueOf(testcase.caseStatus().name()),
                        testcase.missingInformation())).toList());
    }
}
