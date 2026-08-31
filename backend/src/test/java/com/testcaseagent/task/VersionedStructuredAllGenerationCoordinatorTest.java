package com.testcaseagent.task;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

/** [Req-ID]: REQ-TGV2-002, REQ-TGV2-010 */
class VersionedStructuredAllGenerationCoordinatorTest {

    @Test
    void routesV2ButKeepsHistoricalStructuredTasksReadOnlyWithoutFallback() {
        StructuredAllGenerationCoordinator historical = mock(StructuredAllGenerationCoordinator.class);
        StructuredAllGenerationCoordinator v2 = mock(StructuredAllGenerationCoordinator.class);
        VersionedStructuredAllGenerationCoordinator coordinator =
                new VersionedStructuredAllGenerationCoordinator(historical, v2);
        var v2Request = org.mockito.Mockito.mock(CreateGenerationTaskRequest.class);
        var historicalRequest = org.mockito.Mockito.mock(CreateGenerationTaskRequest.class);
        when(v2Request.isV2()).thenReturn(true);

        coordinator.execute("v2-task", v2Request);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> coordinator.execute("historical-task", historicalRequest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Historical structured generation tasks are read-only");

        verify(v2).execute("v2-task", v2Request);
        verify(historical, never()).execute("v2-task", v2Request);
        verify(historical, never()).execute("historical-task", historicalRequest);
        verify(v2, never()).execute("historical-task", historicalRequest);
    }
}
