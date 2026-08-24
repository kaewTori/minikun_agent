package com.minikun.model.task;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.minikun.model.ModelProviderException;

/**
 * Falls back to the rollback task model and temporarily bypasses an unhealthy
 * primary so background workloads do not repeatedly pay its failure latency.
 */
public final class FailoverTaskModelProvider implements TaskModelProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(FailoverTaskModelProvider.class);

    private final TaskModelProvider primary;
    private final TaskModelProvider fallback;
    private final long cooldownNanos;
    private final LongSupplier nanoTime;
    private final AtomicBoolean circuitOpen = new AtomicBoolean();
    private final AtomicBoolean probeInProgress = new AtomicBoolean();
    private final AtomicLong retryAfterNanos = new AtomicLong();

    public FailoverTaskModelProvider(
            TaskModelProvider primary,
            TaskModelProvider fallback,
            Duration cooldown) {
        this(primary, fallback, cooldown, System::nanoTime);
    }

    FailoverTaskModelProvider(
            TaskModelProvider primary,
            TaskModelProvider fallback,
            Duration cooldown,
            LongSupplier nanoTime) {
        this.primary = Objects.requireNonNull(primary, "primary provider must not be null");
        this.fallback = Objects.requireNonNull(fallback, "fallback provider must not be null");
        Objects.requireNonNull(cooldown, "cooldown must not be null");
        if (cooldown.isZero() || cooldown.isNegative()) {
            throw new IllegalArgumentException("cooldown must be positive");
        }
        this.cooldownNanos = cooldown.toNanos();
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime must not be null");
    }

    @Override
    public String generate(TaskModelRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        long now = nanoTime.getAsLong();
        boolean probe = false;
        if (circuitOpen.get()) {
            if (now - retryAfterNanos.get() < 0L) {
                return fallback.generate(request);
            }
            if (!probeInProgress.compareAndSet(false, true)) {
                return fallback.generate(request);
            }
            probe = true;
        }
        try {
            String response = primary.generate(request);
            circuitOpen.set(false);
            retryAfterNanos.set(0L);
            return response;
        } catch (ModelProviderException primaryFailure) {
            if (!primaryFailure.retryable()) {
                circuitOpen.set(false);
                throw primaryFailure;
            }
            retryAfterNanos.set(now + cooldownNanos);
            circuitOpen.set(true);
            LOGGER.warn("process=task_model_failover event=activated cooldown_ms={} reason={}",
                    Duration.ofNanos(cooldownNanos).toMillis(), primaryFailure.getMessage());
            try {
                return fallback.generate(request);
            } catch (RuntimeException fallbackFailure) {
                fallbackFailure.addSuppressed(primaryFailure);
                throw fallbackFailure;
            }
        } finally {
            if (probe) {
                probeInProgress.set(false);
            }
        }
    }

}
