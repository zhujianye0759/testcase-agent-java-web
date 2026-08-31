package com.testcaseagent.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.testcaseagent.knowledgeagent.StructuredSkillErrorType;
import com.testcaseagent.knowledgeagent.StructuredSkillExecutionException;
import com.testcaseagent.validation.StructuredValidationFailure;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Safe coordinator diagnostic contract tests. [Req-ID]: REQ-ESR-006, REQ-TGV2-006 */
class StructuredCoordinatorFailureTest {

    @Test
    void everyStageHasOneUniqueBoundedSafePath() {
        assertThat(Arrays.stream(StructuredCoordinatorFailure.Stage.values())
                .map(StructuredCoordinatorFailure.Stage::path))
                .containsExactly(
                        "$.task_start_state_resume",
                        "$.session_open",
                        "$.inventory_resume_traversal",
                        "$.material_review",
                        "$.requirement_fact_extraction",
                        "$.function_extraction_pre_split",
                        "$.reconciliation",
                        "$.testcase_design",
                        "$.artifact_export",
                        "$.testcase_export")
                .doesNotHaveDuplicates()
                .allMatch(StructuredValidationFailure::isSafePath);
    }

    @Test
    void mapsExceptionClassesToFixedCategoriesWithoutUsingTheirMessages() {
        assertThat(StructuredCoordinatorFailure.from(
                        StructuredCoordinatorFailure.Stage.SESSION_OPEN,
                        new IllegalArgumentException("password=argument-secret")))
                .satisfies(failure -> assertThat(failure.code())
                        .isEqualTo("STRUCTURED_COORDINATOR_ARGUMENT_FAILURE"));
        assertThat(StructuredCoordinatorFailure.from(
                        StructuredCoordinatorFailure.Stage.SESSION_OPEN,
                        new IllegalStateException("Authorization=state-secret")))
                .satisfies(failure -> assertThat(failure.code())
                        .isEqualTo("STRUCTURED_COORDINATOR_STATE_FAILURE"));
        assertThat(StructuredCoordinatorFailure.from(
                        StructuredCoordinatorFailure.Stage.SESSION_OPEN,
                        new StructuredWorkLeaseLostException()))
                .satisfies(failure -> assertThat(failure.code())
                        .isEqualTo("STRUCTURED_COORDINATOR_CONCURRENCY_FAILURE"));
        assertThat(StructuredCoordinatorFailure.from(
                        StructuredCoordinatorFailure.Stage.MATERIAL_REVIEW,
                        new StructuredSkillExecutionException(StructuredSkillErrorType.MODEL_EXECUTION_FAILED, false)))
                .satisfies(failure -> assertThat(failure.code())
                        .isEqualTo("STRUCTURED_COORDINATOR_DEPENDENCY_FAILURE"));
        assertThat(StructuredCoordinatorFailure.from(
                        StructuredCoordinatorFailure.Stage.SESSION_OPEN,
                        new RuntimeException("material content must-not-leak")))
                .satisfies(failure -> {
                    assertThat(failure.code()).isEqualTo("STRUCTURED_COORDINATOR_UNEXPECTED_FAILURE");
                    assertThat(failure.toString()).doesNotContain(
                            "password=argument-secret", "Authorization=state-secret", "material content");
                });
    }
}
