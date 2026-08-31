package com.testcaseagent.task;

import com.testcaseagent.structuredgeneration.StructuredGenerationAcceptanceStore;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Scheduled implementation of the exact-attempt structured work heartbeat. */
public final class ScheduledStructuredWorkLeaseHeartbeat implements StructuredWorkLeaseHeartbeat {
    private final StructuredGenerationAcceptanceStore store;
    private final ScheduledExecutorService scheduler;
    private final Duration interval;

    /** Creates a heartbeat with a bounded fixed-delay renewal interval. [Req-ID]: REQ-SEW-003 */
    public ScheduledStructuredWorkLeaseHeartbeat(StructuredGenerationAcceptanceStore store,
            ScheduledExecutorService scheduler, Duration interval) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.interval = Objects.requireNonNull(interval, "interval must not be null");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
    }

    @Override
    public ActiveLease start(StructuredGenerationAcceptanceStore.WorkClaim claim,
            BooleanSupplier cancellationRequested) {
        Objects.requireNonNull(claim, "claim must not be null");
        Objects.requireNonNull(cancellationRequested, "cancellationRequested must not be null");
        AtomicBoolean closed = new AtomicBoolean();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean lost = new AtomicBoolean();
        long intervalMillis = interval.toMillis();
        if (intervalMillis < 1) throw new IllegalArgumentException("interval must be at least one millisecond");
        Runnable tick = () -> {
            if (closed.get() || cancelled.get() || lost.get()) return;
            if (cancellationRequested.getAsBoolean()) {
                cancelled.set(true);
                return;
            }
            try {
                if (!store.renewLease(claim)) lost.set(true);
            } catch (RuntimeException ignored) {
                // A scheduled database outage is not proof that ownership was lost. The store
                // still rejects expired or replaced claims, so a later tick may safely retry.
            }
        };
        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                tick, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        return new ActiveLease() {
            @Override
            public void requireActive() {
                if (cancelled.get() || cancellationRequested.getAsBoolean()) {
                    cancelled.set(true);
                    throw new CancellationException("Structured task was cancelled");
                }
                if (lost.get()) throw new StructuredWorkLeaseLostException();
                try {
                    if (!store.renewLease(claim)) {
                        lost.set(true);
                        throw new StructuredWorkLeaseLostException();
                    }
                } catch (StructuredWorkLeaseLostException exception) {
                    throw exception;
                } catch (RuntimeException exception) {
                    lost.set(true);
                    throw new StructuredWorkLeaseLostException();
                }
            }

            @Override
            public void close() {
                if (closed.compareAndSet(false, true)) future.cancel(false);
            }
        };
    }
}
