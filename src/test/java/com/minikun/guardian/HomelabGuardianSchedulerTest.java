package com.minikun.guardian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

class HomelabGuardianSchedulerTest {
    @TempDir Path directory;

    @Test
    void alertsAfterStableThresholdDeduplicatesAndThenReportsRecovery() throws Exception {
        Instant now = Instant.parse("2026-08-20T12:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        AtomicReference<SystemHealthReport> health = new AtomicReference<>(unhealthy());
        Path log = directory.resolve("application.log");
        Files.writeString(log, "2026-08-20T11:59:00Z ERROR connection failed token=secret-value\n"
                + "Caused by: java.net.ConnectException: Connection refused\n");
        HomelabGuardianService service = new HomelabGuardianService(health::get,
                new GuardianLogReader(List.of(new GuardianLogSource("application", log))),
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
        assertTrue(notifications.getFirst().message().contains("ผลสืบเบื้องต้น"));
        assertTrue(notifications.getFirst().message().contains("ปฏิเสธการเชื่อมต่อ TCP"));
        assertTrue(notifications.getFirst().message().contains("หลักฐาน:"));
        assertTrue(notifications.getFirst().message().contains("แนะนำ:"));
        assertTrue(notifications.getFirst().message().contains("Caused by:"));
        assertFalse(notifications.getFirst().message().contains("secret-value"));
        assertTrue(notifications.getFirst().message().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 4096);
        assertFalse(notifications.getFirst().message().contains("เพื่อดูหลักฐานและแนวทางแก้"));

        health.set(healthy());
        Files.writeString(log, "2026-08-20T12:00:00Z INFO recovered\n");
        scheduler.inspectAndNotify();

        assertEquals(2, notifications.size());
        assertEquals("Mini-kun homelab recovered", notifications.getLast().title());
    }

    private SystemHealthReport unhealthy() {
        return report("WARNING", false, Map.of("ollama", Map.of("status", "DOWN", "latency_ms", 2,
                "failure_reason", "CONNECTION_REFUSED", "port", 11434)));
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
