package com.minikun.guardian;

import java.util.List;
import java.util.Objects;

/** Bounded, redacted tail of one application-owned log source. */
public record GuardianLogSnapshot(
        String source,
        String status,
        List<String> lines,
        int warningCount,
        int errorCount,
        String unavailableReason) {

    public GuardianLogSnapshot(String source, String status, List<String> lines, int warningCount, int errorCount) {
        this(source, status, lines, warningCount, errorCount, "");
    }

    public GuardianLogSnapshot {
        source = Objects.requireNonNullElse(source, "").trim();
        status = Objects.requireNonNullElse(status, "UNKNOWN").trim();
        lines = List.copyOf(lines == null ? List.of() : lines);
        unavailableReason = Objects.requireNonNullElse(unavailableReason, "").trim();
        if (warningCount < 0 || errorCount < 0) {
            throw new IllegalArgumentException("log counts must not be negative");
        }
    }
}
