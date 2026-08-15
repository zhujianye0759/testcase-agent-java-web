package com.testcaseagent.markdown;

/**
 * [Req-ID]: REQ-ANA-002, REQ-ANA-003
 *
 * <p>One structured test case row whose text cells are ready for deterministic persistence and export.</p>
 */
public record MarkdownTestCaseRow(
        String caseName,
        String featureModule,
        String preconditions,
        String executionSteps,
        String expectedResult,
        String requirementContent) {
}
