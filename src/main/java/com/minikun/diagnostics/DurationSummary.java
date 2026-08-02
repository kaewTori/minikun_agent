package com.minikun.diagnostics;

public record DurationSummary(
        MetricPresence presence,
        long count,
        long totalNanos,
        double averageNanos) {

    public static DurationSummary absent() {
        return new DurationSummary(MetricPresence.ABSENT, 0, 0, 0);
    }
}
