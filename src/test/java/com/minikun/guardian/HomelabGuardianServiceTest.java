package com.minikun.guardian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.systemhealth.SystemHealthReport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HomelabGuardianServiceTest {
    @Test
    void diagnosesCriticalDependenciesAndResourcePressureDeterministically() {
        SystemHealthReport health = new SystemHealthReport("WARNING", false,
                Map.of("status", "UP"), Map.of("status", "WARNING", "used_percent", 96.0),
                Map.of("status", "UP"), Map.of("status", "WARNING", "used_percent", 91.0),
                Map.of("status", "UP"), Map.of("status", "RUNNING"),
                Map.of("ollama", Map.of("status", "DOWN", "latency_ms", 10),
                        "browser", Map.of("status", "DOWN", "latency_ms", 11)));
        Clock clock = Clock.fixed(Instant.parse("2026-08-20T12:00:00Z"), ZoneOffset.UTC);
        HomelabGuardianService service = new HomelabGuardianService(() -> health,
                new GuardianLogReader(List.of()),
                new GuardianBackupChecker(List.of(), Duration.ofHours(36), clock), clock);

        GuardianReport report = service.inspect();

        assertEquals("CRITICAL", report.status());
        assertTrue(report.findings().stream().anyMatch(value -> value.component().equals("ollama")
                && value.severity() == GuardianSeverity.CRITICAL));
        assertTrue(report.findings().stream().anyMatch(value -> value.component().equals("browser")
                && value.severity() == GuardianSeverity.WARNING));
        assertTrue(report.findings().stream().anyMatch(value -> value.component().equals("memory")
                && value.severity() == GuardianSeverity.CRITICAL));
    }
}
