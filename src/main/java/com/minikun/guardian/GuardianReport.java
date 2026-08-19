package com.minikun.guardian;

import com.minikun.systemhealth.SystemHealthReport;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Complete read-only guardian inspection with evidence and deterministic recommendations. */
public record GuardianReport(
        Instant generatedAt,
        String status,
        boolean healthy,
        SystemHealthReport system,
        List<GuardianFinding> findings,
        List<GuardianLogSnapshot> logs,
        List<GuardianBackupSnapshot> backups) {

    public GuardianReport {
        generatedAt = Objects.requireNonNull(generatedAt, "guardian report time must not be null");
        status = Objects.requireNonNullElse(status, "UNKNOWN").trim();
        system = Objects.requireNonNull(system, "system health report must not be null");
        findings = List.copyOf(findings == null ? List.of() : findings);
        logs = List.copyOf(logs == null ? List.of() : logs);
        backups = List.copyOf(backups == null ? List.of() : backups);
    }
}
