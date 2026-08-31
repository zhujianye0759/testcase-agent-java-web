package com.testcaseagent.task;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.testcaseagent.structuredgeneration.StructuredGenerationAcceptanceStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Deterministic lifecycle tests for execution-time lease renewal. */
class ScheduledStructuredWorkLeaseHeartbeatTest {

    /** [Req-ID]: REQ-SEW-003 */
    @Test
    void renewsTheExactClaimAndStopsAfterTheExecutionScopeCloses() {
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        @SuppressWarnings("unchecked") ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        AtomicReference<Runnable> tick = new AtomicReference<>();
        when(scheduler.scheduleWithFixedDelay(any(), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(invocation -> {
                    tick.set(invocation.getArgument(0));
                    return future;
                });
        StructuredGenerationAcceptanceStore.WorkClaim claim = claim();
        when(store.renewLease(claim)).thenReturn(true);
        StructuredWorkLeaseHeartbeat heartbeat = new ScheduledStructuredWorkLeaseHeartbeat(
                store, scheduler, Duration.ofMinutes(1));

        StructuredWorkLeaseHeartbeat.ActiveLease active = heartbeat.start(claim, () -> false);
        tick.get().run();
        active.requireActive();
        active.close();
        tick.get().run();

        verify(store, times(2)).renewLease(claim);
        verify(future).cancel(false);
    }

    /** [Req-ID]: REQ-SEW-003 */
    @Test
    void cancellationStopsRenewalAndFailsThePreAcceptanceHealthCheck() {
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        @SuppressWarnings("unchecked") ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        AtomicReference<Runnable> tick = new AtomicReference<>();
        when(scheduler.scheduleWithFixedDelay(any(), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(invocation -> {
                    tick.set(invocation.getArgument(0));
                    return future;
                });
        StructuredGenerationAcceptanceStore.WorkClaim claim = claim();
        StructuredWorkLeaseHeartbeat heartbeat = new ScheduledStructuredWorkLeaseHeartbeat(
                store, scheduler, Duration.ofMinutes(1));

        StructuredWorkLeaseHeartbeat.ActiveLease active = heartbeat.start(claim, () -> true);
        tick.get().run();

        assertThatThrownBy(active::requireActive).isInstanceOf(CancellationException.class);
        verify(store, never()).renewLease(any());
        active.close();
    }

    /** [Req-ID]: REQ-SEW-003 */
    @Test
    void lostLeaseFailsClosedBeforeAcceptanceAndCannotBeRenewedAgain() {
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        @SuppressWarnings("unchecked") ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        AtomicReference<Runnable> tick = new AtomicReference<>();
        when(scheduler.scheduleWithFixedDelay(any(), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(invocation -> {
                    tick.set(invocation.getArgument(0));
                    return future;
                });
        StructuredGenerationAcceptanceStore.WorkClaim claim = claim();
        when(store.renewLease(claim)).thenReturn(false);
        StructuredWorkLeaseHeartbeat heartbeat = new ScheduledStructuredWorkLeaseHeartbeat(
                store, scheduler, Duration.ofMinutes(1));

        StructuredWorkLeaseHeartbeat.ActiveLease active = heartbeat.start(claim, () -> false);
        tick.get().run();
        tick.get().run();

        assertThatThrownBy(active::requireActive)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Structured work lease was lost during execution");
        verify(store).renewLease(claim);
        active.close();
    }

    /** [Req-ID]: REQ-SEW-003 */
    @Test
    void transientScheduledRenewalFailureDoesNotPermanentlyLoseAnUnexpiredLease() {
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        @SuppressWarnings("unchecked") ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        AtomicReference<Runnable> tick = new AtomicReference<>();
        when(scheduler.scheduleWithFixedDelay(any(), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(invocation -> {
                    tick.set(invocation.getArgument(0));
                    return future;
                });
        StructuredGenerationAcceptanceStore.WorkClaim claim = claim();
        when(store.renewLease(claim))
                .thenThrow(new IllegalStateException("transient database outage"))
                .thenReturn(true, true);
        StructuredWorkLeaseHeartbeat heartbeat = new ScheduledStructuredWorkLeaseHeartbeat(
                store, scheduler, Duration.ofMinutes(1));

        StructuredWorkLeaseHeartbeat.ActiveLease active = heartbeat.start(claim, () -> false);
        tick.get().run();
        tick.get().run();
        active.requireActive();

        verify(store, times(3)).renewLease(claim);
        active.close();
    }

    /** [Req-ID]: REQ-SEW-003 */
    @Test
    void preAcceptanceRenewalExceptionStillFailsClosed() {
        StructuredGenerationAcceptanceStore store = mock(StructuredGenerationAcceptanceStore.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        @SuppressWarnings("unchecked") ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        when(scheduler.scheduleWithFixedDelay(any(), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(invocation -> future);
        StructuredGenerationAcceptanceStore.WorkClaim claim = claim();
        when(store.renewLease(claim)).thenThrow(new IllegalStateException("transient database outage"));
        StructuredWorkLeaseHeartbeat heartbeat = new ScheduledStructuredWorkLeaseHeartbeat(
                store, scheduler, Duration.ofMinutes(1));

        StructuredWorkLeaseHeartbeat.ActiveLease active = heartbeat.start(claim, () -> false);

        assertThatThrownBy(active::requireActive)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Structured work lease was lost during execution");
        verify(store).renewLease(claim);
        active.close();
    }

    private static StructuredGenerationAcceptanceStore.WorkClaim claim() {
        return new StructuredGenerationAcceptanceStore.WorkClaim(
                "work-1", "attempt-1", "task-1", "a".repeat(64),
                "requirement-material-quality-review", "REQUIREMENT_MATERIAL_REVIEW", 1,
                1, 1, "material-1", List.of("evidence-1"), "worker-1");
    }
}
