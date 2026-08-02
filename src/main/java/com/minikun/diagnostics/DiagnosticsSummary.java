package com.minikun.diagnostics;

public record DiagnosticsSummary(
        MetricCount searchRequests,
        DurationSummary searchDuration,
        CacheSummary cache,
        MetricCount timeouts,
        MetricCount failures,
        QualitySummary quality) {

    public record CacheSummary(
            MetricCount hits,
            MetricCount misses,
            MetricCount puts) {}

    public record QualitySummary(
            MetricCount noSearch,
            MetricCount emptyResults,
            MetricCount nonEmptyResults) {}
}
