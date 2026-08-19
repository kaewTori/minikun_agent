package com.minikun.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class NotificationSchedulerMonitorTest {
    private static final Instant NOW = Instant.parse("2026-08-20T02:00:00Z");

    @Test
    void reportsRegisteredAndRecentlyPolledSchedulerAsUp() {
        NotificationSchedulerMonitor monitor = monitor(Clock.fixed(NOW, ZoneOffset.UTC));
        monitor.register("planner");
        monitor.polled("planner", NOW);

        assertEquals(Status.UP, monitor.health().getStatus());
    }

    @Test
    void reportsRepeatedDeliveryFailuresAsDown() {
        NotificationSchedulerMonitor monitor = monitor(Clock.fixed(NOW, ZoneOffset.UTC));
        monitor.polled("planner", NOW);
        monitor.failed("planner", NOW);
        monitor.failed("planner", NOW);
        monitor.failed("planner", NOW);

        assertEquals(Status.DOWN, monitor.health().getStatus());
    }

    @Test
    void successfulDeliveryClearsFailureState() {
        NotificationSchedulerMonitor monitor = monitor(Clock.fixed(NOW, ZoneOffset.UTC));
        monitor.polled("planner", NOW);
        monitor.failed("planner", NOW);
        monitor.failed("planner", NOW);
        monitor.failed("planner", NOW);
        monitor.delivered("planner", NOW);

        assertEquals(Status.UP, monitor.health().getStatus());
    }

    @Test
    void reportsSchedulerAsStaleWhenPollStops() {
        NotificationSchedulerMonitor monitor = monitor(
                Clock.fixed(NOW.plus(Duration.ofMinutes(3)), ZoneOffset.UTC));
        monitor.polled("planner", NOW);

        assertEquals(Status.DOWN, monitor.health().getStatus());
    }

    private NotificationSchedulerMonitor monitor(Clock clock) {
        return new NotificationSchedulerMonitor(
                clock, new SimpleMeterRegistry(), Duration.ofMinutes(2), 3);
    }
}
