package com.minikun.diagnostics;

import java.util.Map;

public record MetricSnapshot(
        String name,
        Map<String, String> tags,
        MetricPresence presence,
        double value,
        long count,
        long totalNanos) {

    public MetricSnapshot {
        tags = Map.copyOf(tags);
    }

    public static MetricSnapshot absent(String name) {
        return new MetricSnapshot(name, Map.of(), MetricPresence.ABSENT, 0, 0, 0);
    }
}
