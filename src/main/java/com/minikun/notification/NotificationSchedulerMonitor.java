package com.minikun.notification;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/** Tracks scheduler liveness and consecutive delivery failures for Actuator and metrics. */
@Component
public final class NotificationSchedulerMonitor implements HealthIndicator {
    private final Clock clock;
    private final Duration staleAfter;
    private final int failureThreshold;
    private final MeterRegistry meterRegistry;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    public NotificationSchedulerMonitor(
            Clock clock,
            MeterRegistry meterRegistry,
            @Value("${minikun.notification.scheduler.stale-after:2m}") Duration staleAfter,
            @Value("${minikun.notification.scheduler.failure-threshold:3}") int failureThreshold) {
        this.clock = Objects.requireNonNull(clock, "scheduler monitor clock must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meter registry must not be null");
        if (staleAfter == null || staleAfter.isNegative() || staleAfter.isZero()) {
            throw new IllegalArgumentException("scheduler stale-after must be positive");
        }
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("scheduler failure threshold must be positive");
        }
        this.staleAfter = staleAfter;
        this.failureThreshold = failureThreshold;
    }

    public void register(String scheduler) {
        state(scheduler);
    }

    public void polled(String scheduler, Instant at) {
        State state = state(scheduler);
        state.lastPoll.set(epochMillis(at));
        state.polls.increment();
    }

    public void delivered(String scheduler, Instant at) {
        State state = state(scheduler);
        state.lastDelivery.set(epochMillis(at));
        state.consecutiveFailures.set(0);
        state.deliveries.increment();
    }

    public void failed(String scheduler, Instant at) {
        State state = state(scheduler);
        state.lastFailure.set(epochMillis(at));
        state.consecutiveFailures.incrementAndGet();
        state.failures.increment();
    }

    @Override
    public Health health() {
        Instant now = clock.instant();
        if (states.isEmpty()) {
            return Health.unknown().withDetail("reason", "no notification scheduler is enabled").build();
        }
        boolean unhealthy = false;
        Map<String, Object> details = new LinkedHashMap<>();
        for (Map.Entry<String, State> entry : new java.util.TreeMap<>(states).entrySet()) {
            State state = entry.getValue();
            long reference = state.lastPoll.get() > 0 ? state.lastPoll.get() : state.registeredAt;
            boolean stale = Duration.between(Instant.ofEpochMilli(reference), now).compareTo(staleAfter) > 0;
            boolean failing = state.consecutiveFailures.get() >= failureThreshold;
            unhealthy = unhealthy || stale || failing;
            Map<String, Object> scheduler = new LinkedHashMap<>();
            scheduler.put("status", stale ? "STALE" : failing ? "FAILING" : "UP");
            putTime(scheduler, "last_poll", state.lastPoll.get());
            putTime(scheduler, "last_delivery", state.lastDelivery.get());
            putTime(scheduler, "last_failure", state.lastFailure.get());
            scheduler.put("consecutive_failures", state.consecutiveFailures.get());
            details.put(entry.getKey(), scheduler);
        }
        Health.Builder result = unhealthy ? Health.down() : Health.up();
        return result.withDetail("stale_after", staleAfter.toString())
                .withDetail("schedulers", details)
                .build();
    }

    private State state(String scheduler) {
        String name = Objects.requireNonNullElse(scheduler, "").trim().toLowerCase(java.util.Locale.ROOT);
        if (name.isBlank()) {
            throw new IllegalArgumentException("scheduler name must not be blank");
        }
        return states.computeIfAbsent(name, this::newState);
    }

    private State newState(String name) {
        State state = new State(clock.instant().toEpochMilli(),
                Counter.builder("minikun.notification.scheduler.polls").tag("scheduler", name).register(meterRegistry),
                Counter.builder("minikun.notification.scheduler.deliveries").tag("scheduler", name).register(meterRegistry),
                Counter.builder("minikun.notification.scheduler.failures").tag("scheduler", name).register(meterRegistry));
        Gauge.builder("minikun.notification.scheduler.last.poll.epoch", state.lastPoll, AtomicLong::doubleValue)
                .tag("scheduler", name).register(meterRegistry);
        return state;
    }

    private long epochMillis(Instant instant) {
        return Objects.requireNonNull(instant, "scheduler event time must not be null").toEpochMilli();
    }

    private void putTime(Map<String, Object> details, String key, long epochMillis) {
        if (epochMillis > 0) {
            details.put(key, Instant.ofEpochMilli(epochMillis).toString());
        }
    }

    private static final class State {
        private final long registeredAt;
        private final AtomicLong lastPoll = new AtomicLong();
        private final AtomicLong lastDelivery = new AtomicLong();
        private final AtomicLong lastFailure = new AtomicLong();
        private final AtomicInteger consecutiveFailures = new AtomicInteger();
        private final Counter polls;
        private final Counter deliveries;
        private final Counter failures;

        private State(long registeredAt, Counter polls, Counter deliveries, Counter failures) {
            this.registeredAt = registeredAt;
            this.polls = polls;
            this.deliveries = deliveries;
            this.failures = failures;
        }
    }
}
