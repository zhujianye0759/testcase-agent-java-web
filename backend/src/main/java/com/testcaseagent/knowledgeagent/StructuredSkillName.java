package com.testcaseagent.knowledgeagent;

/** The only server-selected Skills available through the synchronous isolated route. [Req-ID]: REQ-SKI-002 */
public enum StructuredSkillName {
    REQUIREMENT_MATERIAL_QUALITY_REVIEW("requirement-material-quality-review"),
    FEATURE_SCOPE_RECONCILIATION("feature-scope-reconciliation"),
    FUNCTIONAL_TESTCASE_DESIGN("functional-testcase-design");

    private final String wireValue;
    StructuredSkillName(String wireValue) { this.wireValue = wireValue; }
    public String wireValue() { return wireValue; }
}
