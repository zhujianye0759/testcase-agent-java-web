package com.testcaseagent.knowledgeagent;

/** Dedicated synchronous port for the three frozen structured isolated Skills. [Req-ID]: REQ-SKI-002, REQ-SKI-004 */
public interface StructuredSkillExecutionPort {
    /** Calls the material-review Skill with one already frozen material slice. */
    StructuredSkillSuccessEnvelope<RequirementMaterialQualityReviewResult> reviewRequirementMaterial(RequirementMaterialQualityReviewInvocation invocation);
    /** Calls the feature reconciliation Skill with frozen source candidates. */
    StructuredSkillSuccessEnvelope<FeatureScopeReconciliationResult> reconcileFeatureScope(FeatureScopeReconciliationInvocation invocation);
    /** Calls the feature-scope Skill's exact extract-function-list operation for one frozen material slice. */
    StructuredSkillSuccessEnvelope<FunctionListExtractionResult> extractFunctionList(FunctionListExtractionInvocation invocation);
    /** Calls the testcase-design Skill for one frozen function test point. */
    StructuredSkillSuccessEnvelope<FunctionalTestcaseDesignResult> designFunctionalTestcases(FunctionalTestcaseDesignInvocation invocation);
}
