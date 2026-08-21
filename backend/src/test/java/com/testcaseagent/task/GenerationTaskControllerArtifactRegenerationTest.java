package com.testcaseagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** Locks the reader-safe same-task artifact regeneration HTTP contract. [Req-ID]: REQ-SGD-005 */
class GenerationTaskControllerArtifactRegenerationTest {
    private final GenerationWorkflow workflow = mock(GenerationWorkflow.class);
    private final GenerationTaskController controller = new GenerationTaskController(
            workflow, mock(DynamicTaskScopeResolver.class));

    @Test
    void returnsNoContentAfterRegeneratingTheSameTaskArtifact() {
        assertThat(controller.regenerateArtifact("task-1").getStatusCode().value()).isEqualTo(204);
        verify(workflow).regenerateStructuredArtifact("task-1");
    }

    @Test
    void mapsMissingTaskToNotFound() {
        when(workflow.regenerateStructuredArtifact("missing"))
                .thenThrow(new GenerationTaskNotFoundException("missing"));

        assertThatThrownBy(() -> controller.regenerateArtifact("missing"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void mapsIneligibleOrConcurrentPublicationToConflict() {
        when(workflow.regenerateStructuredArtifact("task-1"))
                .thenThrow(new IllegalStateException("Structured artifact regeneration conflict"));

        assertThatThrownBy(() -> controller.regenerateArtifact("task-1"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(409);
    }
}
