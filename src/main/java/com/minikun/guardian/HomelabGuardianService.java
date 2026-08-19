package com.minikun.guardian;

import com.minikun.systemhealth.SystemHealthReader;
import com.minikun.systemhealth.SystemHealthReport;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Deterministic guardian analysis; the chat model explains verified findings but does not invent probes. */
public final class HomelabGuardianService {
    private final SystemHealthReader health;
    private final GuardianLogReader logs;
    private final GuardianBackupChecker backups;
    private final Clock clock;

    public HomelabGuardianService(
            SystemHealthReader health,
            GuardianLogReader logs,
            GuardianBackupChecker backups,
            Clock clock) {
        this.health = Objects.requireNonNull(health, "system health reader must not be null");
        this.logs = Objects.requireNonNull(logs, "guardian log reader must not be null");
        this.backups = Objects.requireNonNull(backups, "guardian backup checker must not be null");
        this.clock = Objects.requireNonNull(clock, "guardian clock must not be null");
    }

    public GuardianReport inspect() {
        SystemHealthReport system = health.read();
        List<GuardianLogSnapshot> logEvidence = logs.inspectAll(80);
        List<GuardianLogSnapshot> logSnapshots = logEvidence.stream().map(this::summaryOnly).toList();
        List<GuardianBackupSnapshot> backupSnapshots = backups.inspect();
        List<GuardianFinding> findings = new ArrayList<>();
        inspectResources(system, findings);
        inspectDependencies(system, findings);
        inspectLogs(logEvidence, findings);
        inspectBackups(backupSnapshots, findings);
        findings.sort(Comparator.comparing(GuardianFinding::severity).reversed()
                .thenComparing(GuardianFinding::component));
        boolean critical = findings.stream().anyMatch(value -> value.severity() == GuardianSeverity.CRITICAL);
        boolean warning = findings.stream().anyMatch(value -> value.severity() == GuardianSeverity.WARNING);
        String status = critical ? "CRITICAL" : warning ? "WARNING" : "UP";
        return new GuardianReport(clock.instant(), status, !critical && !warning, system,
                findings, logSnapshots, backupSnapshots);
    }

    public GuardianLogSnapshot logs(String source, int lines) {
        return logs.read(source, lines);
    }

    public List<String> logSources() {
        return logs.sourceNames();
    }

    public List<GuardianBackupSnapshot> backups() {
        return backups.inspect();
    }

    private void inspectResources(SystemHealthReport report, List<GuardianFinding> findings) {
        resource("cpu", report.cpu(), "ลดงานที่ใช้ CPU และตรวจ process ที่ทำงานผิดปกติ", findings);
        resource("memory", report.memory(), "ตรวจ memory pressure และ process ที่ใช้หน่วยความจำสูง", findings);
        resource("swap", report.swap(), "ตรวจ memory pressure ก่อนเพิ่ม swap หรือ restart service", findings);
        resource("disk", report.disk(), "ลบหรือย้ายข้อมูลที่ตรวจสอบแล้ว และตรวจ backup ก่อนดำเนินการ", findings);
        Object processStatus = report.process().get("status");
        if (processStatus != null && !"RUNNING".equals(processStatus.toString())) {
            findings.add(new GuardianFinding("PROCESS_NOT_RUNNING", GuardianSeverity.CRITICAL, "minikun",
                    "Mini-kun process is not running", "status=" + processStatus,
                    "ตรวจ service manager และ application log"));
        }
    }

    private void resource(
            String component,
            Map<String, Object> snapshot,
            String recommendation,
            List<GuardianFinding> findings) {
        String status = text(snapshot.get("status"));
        if ("UP".equals(status) || "RUNNING".equals(status)) return;
        double used = number(snapshot.get("used_percent"));
        GuardianSeverity severity = "DOWN".equals(status) || used >= 95
                ? GuardianSeverity.CRITICAL : GuardianSeverity.WARNING;
        String evidence = used >= 0 ? "status=" + status + ", used_percent=" + used : "status=" + status;
        findings.add(new GuardianFinding("RESOURCE_" + component.toUpperCase(Locale.ROOT), severity, component,
                component + " requires attention", evidence, recommendation));
    }

    private void inspectDependencies(SystemHealthReport report, List<GuardianFinding> findings) {
        report.dependencies().forEach((name, snapshot) -> {
            String status = text(snapshot.get("status"));
            if ("UP".equals(status)) return;
            GuardianSeverity severity = switch (name.toLowerCase(Locale.ROOT)) {
                case "application", "postgres", "ollama", "redis" -> GuardianSeverity.CRITICAL;
                default -> GuardianSeverity.WARNING;
            };
            findings.add(new GuardianFinding("DEPENDENCY_DOWN", severity, name,
                    name + " is unreachable", "status=" + status + ", latency_ms=" + snapshot.get("latency_ms"),
                    "ตรวจสถานะและ log ของ " + name + " ก่อนเสนอ restart จาก action allowlist"));
        });
    }

    private void inspectLogs(List<GuardianLogSnapshot> snapshots, List<GuardianFinding> findings) {
        for (GuardianLogSnapshot snapshot : snapshots) {
            if (!"UP".equals(snapshot.status())) {
                findings.add(new GuardianFinding("LOG_UNAVAILABLE", GuardianSeverity.WARNING, snapshot.source(),
                        "Configured log is unavailable", "status=" + snapshot.status(),
                        "ตรวจ path และสิทธิ์อ่านของ log source ที่กำหนดไว้"));
            } else if (recentErrorCount(snapshot) > 0) {
                int recentErrors = recentErrorCount(snapshot);
                findings.add(new GuardianFinding("RECENT_LOG_ERRORS", GuardianSeverity.WARNING, snapshot.source(),
                        "Recent log tail contains errors", "recent_error_count=" + recentErrors,
                        "อ่านบรรทัด error ที่ผ่านการปกปิดข้อมูลลับ แล้วเทียบกับ dependency ที่มีปัญหา"));
            }
        }
    }

    private GuardianLogSnapshot summaryOnly(GuardianLogSnapshot snapshot) {
        return new GuardianLogSnapshot(snapshot.source(), snapshot.status(), List.of(),
                recentWarningCount(snapshot), recentErrorCount(snapshot));
    }

    private int recentWarningCount(GuardianLogSnapshot snapshot) {
        return (int) snapshot.lines().stream().filter(this::recent)
                .map(value -> value.toUpperCase(Locale.ROOT)).filter(value -> value.contains("WARN")).count();
    }

    private int recentErrorCount(GuardianLogSnapshot snapshot) {
        return (int) snapshot.lines().stream().filter(this::recent)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .filter(value -> value.contains("ERROR") || value.contains("EXCEPTION") || value.contains("FATAL"))
                .count();
    }

    private boolean recent(String line) {
        int separator = line.indexOf(' ');
        if (separator <= 0) return false;
        try {
            return !OffsetDateTime.parse(line.substring(0, separator)).toInstant()
                    .isBefore(clock.instant().minus(Duration.ofMinutes(15)));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void inspectBackups(List<GuardianBackupSnapshot> snapshots, List<GuardianFinding> findings) {
        for (GuardianBackupSnapshot snapshot : snapshots) {
            if ("UP".equals(snapshot.status())) continue;
            GuardianSeverity severity = "MISSING".equals(snapshot.status())
                    ? GuardianSeverity.CRITICAL : GuardianSeverity.WARNING;
            findings.add(new GuardianFinding("BACKUP_" + snapshot.status(), severity, snapshot.name(),
                    "Backup requires attention", snapshot.detail(),
                    "ตรวจ backup job และยืนยันว่า restore source ยังใช้งานได้"));
        }
    }

    private String text(Object value) {
        return value == null ? "UNKNOWN" : value.toString().trim().toUpperCase(Locale.ROOT);
    }

    private double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : -1;
    }
}
