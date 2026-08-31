package com.testcaseagent.task;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** HTTP contract tests for explicit retry conflicts. [Req-ID]: REQ-ESR-004 */
class GenerationTaskControllerRetryTest {

    private final GenerationWorkflow workflow = mock(GenerationWorkflow.class);
    private final GenerationTaskController controller = new GenerationTaskController(
            workflow, mock(DynamicTaskScopeResolver.class));
    private final MockMvc http = MockMvcBuilders.standaloneSetup(controller).build();

    /** [Req-ID]: REQ-ESR-001, REQ-ESR-005 */
    @Test
    void mapsEligibleExplicitRetryToNoContent() throws Exception {
        http.perform(post("/api/tasks/task-eligible/retry"))
                .andExpect(status().isNoContent());
    }

    /** [Req-ID]: REQ-ESR-001, REQ-ESR-004 */
    @Test
    void mapsStaleOrIneligibleRetryToConflict() throws Exception {
        doThrow(new GenerationTaskRetryConflictException()).when(workflow).retryFailedBatches("task-stale");

        http.perform(post("/api/tasks/task-stale/retry"))
                .andExpect(status().isConflict());
    }

    /** [Req-ID]: REQ-TGV2-009 */
    @Test
    void rejectsUnboundedStructuredDetailPagesBeforeCallingTheWorkflow() throws Exception {
        http.perform(get("/api/tasks/task-1").queryParam("size", "21"))
                .andExpect(status().isBadRequest());
        http.perform(get("/api/tasks/task-1").queryParam("feedbackPage", "-1"))
                .andExpect(status().isBadRequest());
    }
}
