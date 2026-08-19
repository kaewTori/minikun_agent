package com.minikun.guardian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.minikun.notification.NotificationRequest;
import com.minikun.notification.NotificationSchedulerMonitor;
import com.minikun.proactive.ProactiveNotificationPolicy;
import com.minikun.systemhealth.SystemHealthReport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

class HomelabGuardianSchedulerTest {
    @Test
    void alertsAfterStableThresholdDeduplicatesAndThenReportsRecovery() {
        Instant now = Instant.parse("2026-08-20T12:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        AtomicReference<SystemHealthReport> health = new AtomicReference<>(unhealthy());
        HomelabGuardianService service = new HomelabGuardianService(health::get,
                new GuardianLogReader(List.of()),
                new GuardianBackupChecker(List.of(), Duration.ofHours(36), clock), clock);
        List<NotificationRequest> notifications = new ArrayList<>();
        NotificationSchedulerMonitor monitor = new NotificationSchedulerMonitor(
                clock, new SimpleMeterRegistry(), Duration.ofMinutes(2), 3);
        @SuppressWarnings("unchecked")
        ObjectProvider<JdbcTemplate> jdbc = mock(ObjectProvider.class);
        when(jdbc.getIfAvailable()).thenReturn(null);
        HomelabGuardianScheduler scheduler = new HomelabGuardianScheduler(service, notifications::add,
                monitor, new ProactiveNotificationPolicy(true, ZoneId.of("UTC"), LocalTime.NOON, LocalTime.NOON),
                new GuardianAlertStateStore(jdbc), clock, 2, Duration.ofHours(6));

        scheduler.inspectAndNotify();
        scheduler.inspectAndNotify();
        scheduler.inspectAndNotify();
        assertEquals(1, notifications.size());

        health.set(healthy());
        scheduler.inspectAndNotify();

        assertEquals(2, notifications.size());
        assertEquals("Mini-kun homelab recovered", notifications.getLast().title());
    }

    private SystemHealthReport unhealthy() {
        return report("WARNING", false, Map.of("ollama", Map.of("status", "DOWN", "latency_ms", 2)));
    }

    private SystemHealthReport healthy() {
        return report("UP", true, Map.of("ollama", Map.of("status", "UP", "latency_ms", 1)));
    }

    private SystemHealthReport report(String status, boolean healthy, Map<String, Map<String, Object>> dependencies) {
        return new SystemHealthReport(status, healthy, Map.of("status", "UP"), Map.of("status", "UP"),
                Map.of("status", "UP"), Map.of("status", "UP"), Map.of("status", "UP"),
                Map.of("status", "RUNNING"), dependencies);
    }
}
