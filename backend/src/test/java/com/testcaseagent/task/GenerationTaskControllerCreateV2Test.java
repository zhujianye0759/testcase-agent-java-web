package com.testcaseagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.testcaseagent.task.ApprovedFunctionScope.ApprovedTestPointSource;
import com.testcaseagent.task.ApprovedFunctionScope.ApprovedTestPointStatus;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** HTTP creation contract for reviewed V2 pending points. [Req-ID]: REQ-TGV2-016 */
class GenerationTaskControllerCreateV2Test {

    private final GenerationWorkflow workflow = mock(GenerationWorkflow.class);
    private final DynamicTaskScopeResolver resolver = mock(DynamicTaskScopeResolver.class);
    private final MockMvc http = MockMvcBuilders.standaloneSetup(
            new GenerationTaskController(workflow, resolver)).build();

    @Test
    void forwardsOrderedReviewedPointsFromThePublicJsonRequest() throws Exception {
        AtomicReference<CreateGenerationTaskCommand> captured = new AtomicReference<>();
        CreateGenerationTaskRequest resolved = mock(CreateGenerationTaskRequest.class);
        when(resolver.resolve(any())).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return resolved;
        });
        when(workflow.create(resolved)).thenReturn("task-v2");

        http.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(validRequestJson("""
                "testPoints":[{
                  "testPointKey":"point-b","functionKey":"function-b","type":"BOUNDARY_VALUE",
                  "source":"GENERAL_EXPERIENCE","status":"PENDING_CONFIRMATION",
                  "description":"验证待确认边界","missingInformation":["边界值尚未确认"]
                },{
                  "testPointKey":"point-a","functionKey":"function-a","type":"NORMAL_BEHAVIOR",
                  "source":"GENERAL_EXPERIENCE","status":"PENDING_CONFIRMATION",
                  "description":"验证待确认前置条件","missingInformation":["初始状态尚未确认"]
                }]
                """)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("task-v2"));

        assertThat(captured.get().approvedFunctionScope().testPoints())
                .extracting(ApprovedFunctionScope.ApprovedTestPoint::testPointKey)
                .containsExactly("point-b", "point-a");
        assertThat(captured.get().approvedFunctionScope().testPoints())
                .allSatisfy(point -> {
                    assertThat(point.source()).isEqualTo(ApprovedTestPointSource.GENERAL_EXPERIENCE);
                    assertThat(point.status()).isEqualTo(ApprovedTestPointStatus.PENDING_CONFIRMATION);
                });
    }

    @Test
    void treatsAnOmittedReviewedPointFieldAsTheHistoricalEmptyCollection() throws Exception {
        AtomicReference<CreateGenerationTaskCommand> captured = new AtomicReference<>();
        CreateGenerationTaskRequest resolved = mock(CreateGenerationTaskRequest.class);
        when(resolver.resolve(any())).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return resolved;
        });
        when(workflow.create(resolved)).thenReturn("task-v2");

        http.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson("")))
                .andExpect(status().isCreated());

        assertThat(captured.get().approvedFunctionScope().testPoints()).isEmpty();
    }

    @Test
    void rejectsAnUnreviewedPointSourceBeforeResolvingOrCreatingTheTask() throws Exception {
        http.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(validRequestJson("""
                "testPoints":[{
                  "testPointKey":"point-a","functionKey":"function-a","type":"NORMAL_BEHAVIOR",
                  "source":"MODEL_GENERATED","status":"PENDING_CONFIRMATION",
                  "description":"验证待确认条件","missingInformation":["条件尚未确认"]
                }]
                """)))
                .andExpect(status().isBadRequest());

        verify(resolver, never()).resolve(any());
        verify(workflow, never()).create(any());
    }

    private static String validRequestJson(String optionalPointField) {
        String separator = optionalPointField.isBlank() ? "" : "," + optionalPointField;
        return """
                {
                  "taskMode":"ALL","featureDescription":"","fewShotPolicy":"NONE",
                  "schemaVersion":"2.0","promptVersion":"2.0","scopeSelectionIds":["scope-a"],
                  "prompt":"","workflowVersion":"2.0","inputVersion":"2.0","artifactVersion":"2.0",
                  "approvedFunctionScope":{
                    "scopeVersion":"scope-v2",
                    "functions":[
                      {"functionKey":"function-a","name":"功能甲","path":"范围/功能甲","description":""},
                      {"functionKey":"function-b","name":"功能乙","path":"范围/功能乙","description":""}
                    ]%s
                  }
                }
                """.formatted(separator);
    }
}
