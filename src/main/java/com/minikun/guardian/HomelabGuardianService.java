package com.minikun.guardian;

import com.minikun.systemhealth.SystemHealthReader;
import com.minikun.systemhealth.SystemHealthReport;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.Instant;
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
                    "ตรวจ service manager และ application log",
                    "ตรวจพบ process ไม่ทำงาน แต่ยังไม่ทราบเหตุที่หยุด", "UNKNOWN"));
        }
    }

    private void resource(
            String component,
            Map<String, Object> snapshot,
            String recommendation,
            List<GuardianFinding> findings) {
        String status = text(snapshot.get("status"));
        if ("UP".equals(status) || "RUNNING".equals(status)) return;
        double used = number(snapshot.get(component.equals("cpu") ? "usage_percent" : "used_percent"));
        GuardianSeverity severity = "DOWN".equals(status) || used >= 95
                ? GuardianSeverity.CRITICAL : GuardianSeverity.WARNING;
        StringBuilder evidence = new StringBuilder("status=" + status);
        for (String key : List.of("usage_percent", "process_usage_percent", "used_percent", "raw_used_percent",
                "available_percent", "warning_threshold_percent", "free_bytes", "total_bytes", "measurement")) {
            if (snapshot.containsKey(key)) evidence.append(", ").append(key).append('=').append(snapshot.get(key));
        }
        boolean measuredWarning = "WARNING".equals(status) && used >= 0;
        String cause = measuredWarning
                ? component + " ใช้งาน " + used + "% จนเข้าเกณฑ์เตือน"
                : "ยังอ่านค่าทรัพยากรไม่ได้ จึงยังยืนยันต้นเหตุไม่ได้";
        if (measuredWarning && component.equals("memory") && "memory_pressure".equals(snapshot.get("measurement"))) {
            cause += " (วัดจาก memory pressure ไม่ใช่ RAM ที่ใช้รวม cache)";
        }
        if (measuredWarning) cause += "; ยังไม่ได้ระบุว่า process ใดเป็นต้นเหตุ";
        findings.add(new GuardianFinding("RESOURCE_" + component.toUpperCase(Locale.ROOT), severity, component,
                component + " requires attention", evidence.toString(), recommendation, cause,
                measuredWarning ? "CONFIRMED" : "UNKNOWN"));
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
                    name + " is unreachable", "status=" + status + ", port=" + snapshot.get("port")
                    + ", latency_ms=" + snapshot.get("latency_ms") + ", failure_reason="
                    + snapshot.getOrDefault("failure_reason", "UNKNOWN"),
                    "ตรวจสถานะและ log ของ " + name + " ก่อนเสนอ restart จาก action allowlist",
                    dependencyCause(snapshot), "UNKNOWN"));
        });
    }

    private String dependencyCause(Map<String, Object> snapshot) {
        String observed = switch (snapshot.getOrDefault("failure_reason", "UNKNOWN").toString()) {
            case "CONNECTION_REFUSED" -> "ปลายทางปฏิเสธการเชื่อมต่อ TCP";
            case "CONNECT_TIMEOUT" -> "เชื่อมต่อ TCP ไม่สำเร็จภายในเวลาที่กำหนด";
            case "NAME_RESOLUTION_FAILED" -> "แปลงชื่อ host เป็น IP ไม่สำเร็จ";
            default -> "probe เชื่อมต่อบริการไม่สำเร็จ";
        };
        return observed + "; ยังยืนยันไม่ได้ว่าบริการหยุด หรือมีปัญหาที่เครือข่าย/การตั้งค่า";
    }

    private void inspectLogs(List<GuardianLogSnapshot> snapshots, List<GuardianFinding> findings) {
        for (GuardianLogSnapshot snapshot : snapshots) {
            if (!"UP".equals(snapshot.status())) {
                String cause = switch (snapshot.unavailableReason()) {
                    case "FILE_MISSING" -> "ไม่พบไฟล์ log ที่ตั้งค่าไว้";
                    case "NOT_READABLE" -> "process ไม่มีสิทธิ์อ่านหรือเข้าถึงไฟล์ log";
                    case "NOT_REGULAR_FILE" -> "log source ที่ตั้งค่าไว้ไม่ใช่ไฟล์ปกติ";
                    default -> "อ่าน log ไม่สำเร็จ แต่ยังยืนยันต้นเหตุไม่ได้";
                };
                findings.add(new GuardianFinding("LOG_UNAVAILABLE", GuardianSeverity.WARNING, snapshot.source(),
                        "Configured log is unavailable", "status=" + snapshot.status()
                        + ", reason=" + snapshot.unavailableReason(),
                        "ตรวจ path และสิทธิ์อ่านของ log source ที่กำหนดไว้", cause,
                        List.of("FILE_MISSING", "NOT_READABLE", "NOT_REGULAR_FILE")
                                .contains(snapshot.unavailableReason()) ? "CONFIRMED" : "UNKNOWN"));
            } else if (recentErrorCount(snapshot) > 0) {
                int recentErrors = recentErrorCount(snapshot);
                findings.add(new GuardianFinding("RECENT_LOG_ERRORS", GuardianSeverity.WARNING, snapshot.source(),
                        "Recent log tail contains errors", "recent_error_count=" + recentErrors
                        + "; log: " + recentErrorEvidence(snapshot),
                        "ตรวจ error และ Caused by ที่แนบมา แล้วเทียบเวลาเกิดเหตุกับสถานะบริการ",
                        "พบ error ใน log ช่วง 15 นาทีล่าสุด; ข้อความที่แนบเป็นหลักฐานจาก log ยังไม่ยืนยันต้นเหตุทั้งหมด",
                        "UNKNOWN"));
            }
        }
    }

    private GuardianLogSnapshot summaryOnly(GuardianLogSnapshot snapshot) {
        return new GuardianLogSnapshot(snapshot.source(), snapshot.status(), List.of(),
                recentWarningCount(snapshot), recentErrorCount(snapshot), snapshot.unavailableReason());
    }

    private String recentErrorEvidence(GuardianLogSnapshot snapshot) {
        List<String> entries = new ArrayList<>();
        for (int index = 0; index < snapshot.lines().size(); index++) {
            String line = snapshot.lines().get(index);
            if (!recent(line) || !error(line)) continue;
            StringBuilder entry = new StringBuilder(excerpt(line));
            // Bounded stack context; never borrow causes from the next timestamped log event.
            for (int next = index + 1; next < Math.min(index + 11, snapshot.lines().size()); next++) {
                String context = snapshot.lines().get(next);
                if (timestamp(context) != null) break;
                if (context.trim().startsWith("Caused by:")) {
                    entry.append(" | ").append(excerpt(context));
                    break;
                }
            }
            entries.add(entry.toString());
        }
        return String.join(" | ", entries.subList(Math.max(0, entries.size() - 2), entries.size()));
    }

    private String excerpt(String line) {
        String clean = line.replaceAll("[\\p{Cntrl}\\p{Cf}]", " ").strip();
        return clean.length() <= 240 ? clean : clean.substring(0, 240) + "…";
    }

    private boolean error(String line) {
        return GuardianLogReader.isErrorLine(line);
    }

    private int recentWarningCount(GuardianLogSnapshot snapshot) {
        return (int) snapshot.lines().stream().filter(this::recent)
                .filter(GuardianLogReader::isWarningLine).count();
    }

    private int recentErrorCount(GuardianLogSnapshot snapshot) {
        return (int) snapshot.lines().stream().filter(this::recent)
                .filter(this::error).count();
    }

    private boolean recent(String line) {
        Instant at = timestamp(line);
        return at != null && !at.isBefore(clock.instant().minus(Duration.ofMinutes(15)))
                && !at.isAfter(clock.instant());
    }

    private Instant timestamp(String line) {
        int separator = line.indexOf(' ');
        if (separator <= 0) return null;
        try {
            return OffsetDateTime.parse(line.substring(0, separator)).toInstant();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private void inspectBackups(List<GuardianBackupSnapshot> snapshots, List<GuardianFinding> findings) {
        for (GuardianBackupSnapshot snapshot : snapshots) {
            if ("UP".equals(snapshot.status())) continue;
            GuardianSeverity severity = "MISSING".equals(snapshot.status())
                    ? GuardianSeverity.CRITICAL : GuardianSeverity.WARNING;
            findings.add(new GuardianFinding("BACKUP_" + snapshot.status(), severity, snapshot.name(),
                    "Backup requires attention", snapshot.detail(),
                    "ตรวจ backup job และยืนยันว่า restore source ยังใช้งานได้",
                    "ผลตรวจ backup: " + snapshot.detail() + "; ยังไม่ยืนยันเหตุที่ backup job มีปัญหา", "UNKNOWN"));
        }
    }

    private String text(Object value) {
        return value == null ? "UNKNOWN" : value.toString().trim().toUpperCase(Locale.ROOT);
    }

    private double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : -1;
    }
}
