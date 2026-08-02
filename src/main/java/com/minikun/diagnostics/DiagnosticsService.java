package com.minikun.diagnostics;

import java.util.List;

import org.springframework.stereotype.Service;

@Service
public final class DiagnosticsService {

    private static final String REQUESTS = "minikun.search.requests";
    private static final String DURATION = "minikun.search.duration";
    private static final String CACHE_HIT = "minikun.search.cache.hit";
    private static final String CACHE_MISS = "minikun.search.cache.miss";
    private static final String CACHE_PUT = "minikun.search.cache.put";
    private static final String TIMEOUTS = "minikun.search.timeouts";
    private static final String FAILURES = "minikun.search.failures";
    private static final String NO_SEARCH = "minikun.search.quality.no_search";
    private static final String RESULTS = "minikun.search.quality.search.results";

    private final MetricsReader metricsReader;

    public DiagnosticsService(MetricsReader metricsReader) {
        this.metricsReader = metricsReader;
    }

    public DiagnosticsSummary summarize() {
        return new DiagnosticsSummary(
                count(REQUESTS),
                duration(),
                new DiagnosticsSummary.CacheSummary(
                        count(CACHE_HIT), count(CACHE_MISS), count(CACHE_PUT)),
                count(TIMEOUTS),
                count(FAILURES),
                new DiagnosticsSummary.QualitySummary(
                        count(NO_SEARCH), outcome("empty"), outcome("non_empty")));
    }

    private MetricCount count(String name) {
        List<MetricSnapshot> snapshots = metricsReader.read(name);
        boolean present = snapshots.stream().anyMatch(snapshot -> snapshot.presence() == MetricPresence.PRESENT);
        if (!present) {
            return MetricCount.absent();
        }
        long value = Math.round(snapshots.stream()
                .filter(snapshot -> snapshot.presence() == MetricPresence.PRESENT)
                .mapToDouble(MetricSnapshot::value)
                .sum());
        return MetricCount.present(value);
    }

    private MetricCount outcome(String outcome) {
        List<MetricSnapshot> snapshots = metricsReader.read(RESULTS);
        boolean present = snapshots.stream().anyMatch(snapshot ->
                snapshot.presence() == MetricPresence.PRESENT
                        && outcome.equals(snapshot.tags().get("outcome")));
        if (!present) {
            return MetricCount.absent();
        }
        long value = Math.round(snapshots.stream()
                .filter(snapshot -> snapshot.presence() == MetricPresence.PRESENT)
                .filter(snapshot -> outcome.equals(snapshot.tags().get("outcome")))
                .mapToDouble(MetricSnapshot::value)
                .sum());
        return MetricCount.present(value);
    }

    private DurationSummary duration() {
        List<MetricSnapshot> snapshots = metricsReader.read(DURATION);
        List<MetricSnapshot> present = snapshots.stream()
                .filter(snapshot -> snapshot.presence() == MetricPresence.PRESENT)
                .toList();
        if (present.isEmpty()) {
            return DurationSummary.absent();
        }
        long count = present.stream().mapToLong(MetricSnapshot::count).sum();
        long totalNanos = present.stream().mapToLong(MetricSnapshot::totalNanos).sum();
        double averageNanos = count == 0 ? 0 : (double) totalNanos / count;
        return new DurationSummary(MetricPresence.PRESENT, count, totalNanos, averageNanos);
    }
}
