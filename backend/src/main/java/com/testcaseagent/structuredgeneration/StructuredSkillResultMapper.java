package com.testcaseagent.structuredgeneration;

import com.testcaseagent.knowledgeagent.FeatureScopeReconciliationResult;
import com.testcaseagent.knowledgeagent.FunctionListExtractionResult;
import com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignResult;
import com.testcaseagent.knowledgeagent.RequirementMaterialQualityReviewResult;
import com.testcaseagent.validation.FeatureReconciliationValidator;
import com.testcaseagent.validation.FunctionListExtractionValidator;
import com.testcaseagent.validation.FunctionalTestcaseResultValidator;
import com.testcaseagent.validation.RequirementMaterialReviewValidator;

/** Maps structurally checked KEE DTOs into the separate Java business-validation model without dropping fields. */
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
                        finding.findingKey(), finding.issueType(), finding.description(), finding.evidenceKeys(),
                        finding.testDesignImpact(), finding.currentProjectRecommendation(),
                        finding.designCenterGuidelineRecommendation(),
                        RequirementMaterialReviewValidator.HandlingLevel.valueOf(finding.handlingLevel().name()))).toList());
    }

    /** Maps the model-owned extraction rows; no item key exists at this stage. */
    public static FunctionListExtractionValidator.Result extraction(FunctionListExtractionResult result) {
        return new FunctionListExtractionValidator.Result(result.functionListItems().stream()
                .map(row -> new FunctionListExtractionValidator.ModelItem(
                        row.path(), row.description(), row.evidenceKeys()))
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
                        testcase.caseKey(), testcase.title(), testcase.preconditions(), testcase.steps().stream()
                                .map(step -> new FunctionalTestcaseResultValidator.Step(
                                        step.stepNo(), step.action(), step.expected()))
                                .toList(),
                        testcase.requirementFactKeys(), testcase.evidenceKeys(),
                        FunctionalTestcaseResultValidator.CaseStatus.valueOf(testcase.caseStatus().name()),
                        testcase.missingInformation())).toList());
    }
}
