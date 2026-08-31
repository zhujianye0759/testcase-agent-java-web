package com.testcaseagent.knowledgeagent;

/** Dedicated synchronous port for the three frozen structured isolated Skills. [Req-ID]: REQ-SKI-002, REQ-SKI-004 */
public interface StructuredSkillExecutionPort {
    /** Calls the V2 fact extraction Skill with one exact admitted material document. */
    StructuredSkillSuccessEnvelope<RequirementFactExtractionV2Result> extractRequirementFactsV2(
            RequirementFactExtractionV2Invocation invocation);
    /** Calls the material-review Skill with one already frozen material slice. */
    StructuredSkillSuccessEnvelope<RequirementMaterialQualityReviewResult> reviewRequirementMaterial(RequirementMaterialQualityReviewInvocation invocation);
    /** Calls the feature reconciliation Skill with frozen source candidates. */
    StructuredSkillSuccessEnvelope<FeatureScopeReconciliationResult> reconcileFeatureScope(FeatureScopeReconciliationInvocation invocation);
    /**
     * Calls one protocol V2 owner page while preserving the normal structured success envelope.
     * New reconciliation runs use this method; V1 remains for historical completed-work compatibility.
     */
    StructuredSkillSuccessEnvelope<FeatureScopeReconciliationPageResult> reconcileFeatureScopePage(
            FeatureScopeReconciliationPageInvocation invocation);
    /** Calls the feature-scope Skill's exact extract-function-list operation for one frozen material slice. */
    StructuredSkillSuccessEnvelope<FunctionListExtractionResult> extractFunctionList(FunctionListExtractionInvocation invocation);
    /** Calls the auditable protocol V1 candidate operation without falling back to legacy extraction. */
    StructuredSkillSuccessEnvelope<FunctionCandidateExtractionResult> extractFunctionCandidates(
            FunctionCandidateExtractionInvocation invocation);
    /** Calls the testcase-design Skill for one frozen function test point. */
    StructuredSkillSuccessEnvelope<FunctionalTestcaseDesignResult> designFunctionalTestcases(FunctionalTestcaseDesignInvocation invocation);
    /** Calls the V2 testcase design Skill for exactly one Java-owned test point. */
    StructuredSkillSuccessEnvelope<FunctionalTestcaseDesignV2Result> designFunctionalTestcasesV2(
            FunctionalTestcaseDesignV2Invocation invocation);
}
