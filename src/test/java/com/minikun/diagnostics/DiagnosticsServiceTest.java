package com.minikun.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class DiagnosticsServiceTest {

    @Test
    void missingMetricsRemainAbsent() {
        DiagnosticsSummary summary = new DiagnosticsService(name -> List.of(MetricSnapshot.absent(name))).summarize();

        assertEquals(MetricPresence.ABSENT, summary.searchRequests().presence());
        assertEquals(MetricPresence.ABSENT, summary.searchDuration().presence());
        assertEquals(MetricPresence.ABSENT, summary.quality().emptyResults().presence());
    }

    @Test
    void presentZeroMetricsRemainDistinctFromAbsent() {
        MetricsReader reader = name -> List.of(new MetricSnapshot(
                name, Map.of(), MetricPresence.PRESENT, 0, 0, 0));

        DiagnosticsSummary summary = new DiagnosticsService(reader).summarize();

        assertEquals(MetricPresence.PRESENT, summary.searchRequests().presence());
        assertEquals(0, summary.searchRequests().value());
        assertEquals(MetricPresence.PRESENT, summary.searchDuration().presence());
    }

    @Test
    void aggregatesTaggedMetricsAndComputesAverageDuration() {
        MetricsReader reader = name -> switch (name) {
            case "minikun.search.requests" -> List.of(snapshot(name, 3), snapshot(name, 2));
            case "minikun.search.duration" -> List.of(
                    timer(name, Map.of("provider", "a"), 2, 100),
                    timer(name, Map.of("provider", "b"), 1, 50));
            case "minikun.search.quality.search.results" -> List.of(
                    snapshot(name, Map.of("outcome", "empty"), 4),
                    snapshot(name, Map.of("outcome", "non_empty"), 6));
            default -> List.of(MetricSnapshot.absent(name));
        };

        DiagnosticsSummary summary = new DiagnosticsService(reader).summarize();

        assertEquals(5, summary.searchRequests().value());
        assertEquals(3, summary.searchDuration().count());
        assertEquals(50, summary.searchDuration().averageNanos());
        assertEquals(4, summary.quality().emptyResults().value());
        assertEquals(6, summary.quality().nonEmptyResults().value());
        assertTrue(summary.cache().hits().presence() == MetricPresence.ABSENT);
    }

    private static MetricSnapshot snapshot(String name, long value) {
        return snapshot(name, Map.of(), value);
    }

    private static MetricSnapshot snapshot(String name, Map<String, String> tags, long value) {
        return new MetricSnapshot(name, tags, MetricPresence.PRESENT, value, 0, 0);
    }

    private static MetricSnapshot timer(String name, Map<String, String> tags, long count, long totalNanos) {
        return new MetricSnapshot(name, tags, MetricPresence.PRESENT, 0, count, totalNanos);
    }
}
