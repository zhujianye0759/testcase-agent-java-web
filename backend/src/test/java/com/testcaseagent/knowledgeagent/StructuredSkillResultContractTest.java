package com.testcaseagent.knowledgeagent;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Boundary tests for typed structured Skill results. [Req-ID]: REQ-SKI-004 */
class StructuredSkillResultContractTest {
    /** [Req-ID]: REQ-SKI-004 */
    @Test
    void rejectsAnEmptyMaterialReviewAndNonContinuousCaseSteps() {
        assertThatThrownBy(() -> new RequirementMaterialQualityReviewResult(List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FunctionalTestcaseDesignResult("function-1", "point-1", List.of(
                new FunctionalTestcaseDesignResult.Testcase("case-1", "标题", List.of(), List.of(
                        new FunctionalTestcaseDesignResult.Step(2, "操作", "预期")), List.of(), List.of(),
                        FunctionalTestcaseDesignResult.CaseStatus.FORMAL, List.of()))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
