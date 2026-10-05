package com.minikun.guardian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.minikun.systemhealth.SystemHealthReport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HomelabGuardianServiceTest {
    @TempDir Path directory;
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-20T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void searchFallbackWarningsDoNotBecomeApplicationErrorIncident() throws Exception {
        Path log = directory.resolve("fallback.log");
        Files.writeString(log, """
                2026-08-20T11:59:00Z WARN TavilySearchProvider message=400 Bad Request: {"error":"Query is invalid."}
                2026-08-20T11:59:01Z WARN FailoverSearchProvider event=fallback reason=SearchExecutionException
                2026-08-20T11:59:02Z INFO request complete error_count=0
                """);
        GuardianReport report = service(healthy(), new GuardianLogReader(List.of(
                new GuardianLogSource("application", log)))).inspect();
        assertTrue(report.healthy());
        assertEquals(0, report.logs().getFirst().errorCount());
        assertEquals(2, report.logs().getFirst().warningCount());
    }

    @Test
    void investigatesRecentRedactedLogErrorsWithBoundedStackContext() throws Exception {
        Path log = directory.resolve("application.log");
        Files.writeString(log, """
                2026-08-20T11:00:00Z ERROR old failure
                2026-08-20T11:59:00Z ERROR database operation failed Authorization: Bearer private-key
                    Caused by: java.net.ConnectException: Connection refused password=hunter2
                2026-08-20T11:59:30Z INFO unrelated event
                    Caused by: unrelated exception
                2026-08-21T12:00:00Z ERROR future failure
                """);
        GuardianReport report = service(healthy(), new GuardianLogReader(List.of(
                new GuardianLogSource("application", log)))).inspect();
        GuardianFinding finding = report.findings().getFirst();

        assertEquals("RECENT_LOG_ERRORS", finding.code());
        assertEquals("UNKNOWN", finding.causeConfidence());
        assertTrue(finding.evidence().contains("recent_error_count=1"));
        assertTrue(finding.evidence().contains("Caused by: java.net.ConnectException"));
        assertTrue(finding.evidence().contains("[REDACTED]"));
        assertFalse(finding.evidence().contains("private-key"));
        assertFalse(finding.evidence().contains("hunter2"));
        assertFalse(finding.evidence().contains("old failure"));
        assertFalse(finding.evidence().contains("future failure"));
        assertFalse(finding.evidence().contains("unrelated exception"));
        assertTrue(report.logs().getFirst().lines().isEmpty());
    }

    @Test
    void explainsMissingLogAndCpuThresholdButDoesNotInventUnknownResourceUsage() {
        SystemHealthReport health = new SystemHealthReport("WARNING", false,
                Map.of("status", "WARNING", "usage_percent", 96.5, "warning_threshold_percent", 90),
                Map.of("status", "UNKNOWN"), Map.of("status", "UP"), Map.of("status", "UP"),
                Map.of("status", "UP"), Map.of("status", "RUNNING"), Map.of());
        GuardianReport report = service(health, new GuardianLogReader(List.of(
                new GuardianLogSource("application", directory.resolve("missing.log"))))).inspect();
        GuardianFinding cpu = report.findings().stream().filter(f -> f.component().equals("cpu")).findFirst().orElseThrow();
        GuardianFinding memory = report.findings().stream().filter(f -> f.component().equals("memory")).findFirst().orElseThrow();
        GuardianFinding log = report.findings().stream().filter(f -> f.code().equals("LOG_UNAVAILABLE")).findFirst().orElseThrow();
        assertEquals(GuardianSeverity.CRITICAL, cpu.severity());
        assertEquals("CONFIRMED", cpu.causeConfidence());
        assertTrue(cpu.cause().contains("96.5%"));
        assertTrue(cpu.evidence().contains("warning_threshold_percent=90"));
        assertEquals("UNKNOWN", memory.causeConfidence());
        assertFalse(memory.cause().contains("-1"));
        assertEquals("CONFIRMED", log.causeConfidence());
        assertTrue(log.cause().contains("ไม่พบไฟล์"));
        assertTrue(log.evidence().contains("FILE_MISSING"));
        assertFalse(log.evidence().contains(directory.toString()));
    }

    @Test
    void doesNotBorrowStackCauseFromNextLogEventAndBoundsEvidence() throws Exception {
        Path log = directory.resolve("application.log");
        Files.writeString(log, "2026-08-20T11:59:00Z ERROR " + "x".repeat(2000)
                + "\n2026-08-20T11:59:01Z INFO separate request\nCaused by: unrelated failure\n");
        GuardianFinding finding = service(healthy(), new GuardianLogReader(List.of(
                new GuardianLogSource("application", log)))).inspect().findings().getFirst();
        assertTrue(finding.evidence().length() < 300);
        assertFalse(finding.evidence().contains("unrelated failure"));
    }

    @Test
    void preservesMacMemoryPressureMeasurementAndReportsProbeFailureWithoutClaimingRootCause() {
        SystemHealthReport health = new SystemHealthReport("WARNING", false,
                Map.of("status", "UP"),
                Map.of("status", "WARNING", "used_percent", 86, "raw_used_percent", 98,
                        "available_percent", 14, "measurement", "memory_pressure"),
                Map.of("status", "UP"), Map.of("status", "UP"), Map.of("status", "UP"),
                Map.of("status", "RUNNING"), Map.of("browser", Map.of("status", "DOWN", "port", 11235,
                        "latency_ms", 0, "failure_reason", "CONNECTION_REFUSED")));
        GuardianReport report = service(health, new GuardianLogReader(List.of())).inspect();
        GuardianFinding memory = report.findings().stream().filter(f -> f.component().equals("memory")).findFirst().orElseThrow();
        GuardianFinding browser = report.findings().stream().filter(f -> f.component().equals("browser")).findFirst().orElseThrow();
        assertTrue(memory.cause().contains("86%") || memory.cause().contains("86.0%"));
        assertTrue(memory.cause().contains("memory pressure"));
        assertTrue(memory.evidence().contains("raw_used_percent=98"));
        assertTrue(browser.cause().contains("ปฏิเสธ"));
        assertEquals("UNKNOWN", browser.causeConfidence());
        assertTrue(browser.evidence().contains("port=11235"));
    }

    private HomelabGuardianService service(SystemHealthReport health, GuardianLogReader logs) {
        return new HomelabGuardianService(() -> health, logs,
                new GuardianBackupChecker(List.of(), Duration.ofHours(36), clock), clock);
    }

    private SystemHealthReport healthy() {
        return new SystemHealthReport("UP", true, Map.of("status", "UP"), Map.of("status", "UP"),
                Map.of("status", "UP"), Map.of("status", "UP"), Map.of("status", "UP"),
                Map.of("status", "RUNNING"), Map.of());
    }
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
