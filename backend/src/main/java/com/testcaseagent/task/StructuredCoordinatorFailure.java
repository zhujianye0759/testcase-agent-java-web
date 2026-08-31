package com.testcaseagent.task;

import com.testcaseagent.knowledgeagent.KnowledgeAgentInvocationException;
import com.testcaseagent.validation.StructuredValidationFailure;
import java.util.Objects;

/**
 * Converts an outer structured-coordinator exception into a bounded diagnostic.
 *
 * <p>The mapping deliberately never reads {@link Throwable#getMessage()} or a stack trace. A task may therefore
 * expose where execution stopped and the broad exception category without persisting model output, material text,
 * URLs, or credentials carried by an arbitrary dependency exception.</p>
 *
 * [Req-ID]: REQ-ESR-006
 */
public final class StructuredCoordinatorFailure {

    private StructuredCoordinatorFailure() {
    }

    /** Creates the safe fixed diagnostic for one allowlisted coordinator stage. */
    public static StructuredValidationFailure from(Stage stage, RuntimeException failure) {
        Objects.requireNonNull(stage, "stage must not be null");
        Objects.requireNonNull(failure, "failure must not be null");
        StructuredValidationFailure.Code code;
        if (failure instanceof StructuredWorkLeaseLostException) {
            code = StructuredValidationFailure.Code.STRUCTURED_COORDINATOR_CONCURRENCY_FAILURE;
        } else if (failure instanceof KnowledgeAgentInvocationException) {
            code = StructuredValidationFailure.Code.STRUCTURED_COORDINATOR_DEPENDENCY_FAILURE;
        } else if (failure instanceof IllegalArgumentException) {
            code = StructuredValidationFailure.Code.STRUCTURED_COORDINATOR_ARGUMENT_FAILURE;
        } else if (failure instanceof IllegalStateException) {
            code = StructuredValidationFailure.Code.STRUCTURED_COORDINATOR_STATE_FAILURE;
        } else {
            code = StructuredValidationFailure.Code.STRUCTURED_COORDINATOR_UNEXPECTED_FAILURE;
        }
        return StructuredValidationFailure.of(code, stage.path);
    }

    /** Fixed execution stages that are safe to persist and return through task detail. */
    public enum Stage {
        TASK_START_STATE_RESUME("$.task_start_state_resume"),
        SESSION_OPEN("$.session_open"),
        INVENTORY_RESUME_TRAVERSAL("$.inventory_resume_traversal"),
        MATERIAL_REVIEW("$.material_review"),
        REQUIREMENT_FACT_EXTRACTION("$.requirement_fact_extraction"),
        FUNCTION_EXTRACTION_PRE_SPLIT("$.function_extraction_pre_split"),
        RECONCILIATION("$.reconciliation"),
        TESTCASE_DESIGN("$.testcase_design"),
        ARTIFACT_EXPORT("$.artifact_export"),
        TESTCASE_EXPORT("$.testcase_export");

        private final String path;

        Stage(String path) {
            this.path = path;
        }

        /** Returns the bounded JSON-style stage coordinate stored in V14 diagnostics. */
        public String path() {
            return path;
        }
    }
}
