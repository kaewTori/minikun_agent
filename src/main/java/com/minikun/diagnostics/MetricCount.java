package com.minikun.diagnostics;

public record MetricCount(MetricPresence presence, long value) {
    public static MetricCount absent() {
        return new MetricCount(MetricPresence.ABSENT, 0);
    }

    public static MetricCount present(long value) {
        return new MetricCount(MetricPresence.PRESENT, value);
    }
}
