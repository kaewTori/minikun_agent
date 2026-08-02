package com.minikun.diagnostics;

import org.springframework.stereotype.Component;

@Component
public final class DiagnosticsFormatter {

    public String format(DiagnosticsSummary summary) {
        StringBuilder output = new StringBuilder();
        output.append("Search diagnostics\n");
        appendCount(output, "Search requests", summary.searchRequests());
        appendDuration(output, summary.searchDuration());
        output.append("Cache\n");
        appendCount(output, "  Hits", summary.cache().hits());
        appendCount(output, "  Misses", summary.cache().misses());
        appendCount(output, "  Puts", summary.cache().puts());
        appendCount(output, "Timeouts", summary.timeouts());
        appendCount(output, "Failures", summary.failures());
        output.append("Search quality\n");
        appendCount(output, "  No-search decisions", summary.quality().noSearch());
        appendCount(output, "  Empty results", summary.quality().emptyResults());
        appendCount(output, "  Non-empty results", summary.quality().nonEmptyResults());
        return output.toString();
    }

    private void appendCount(StringBuilder output, String label, MetricCount count) {
        output.append(label).append(": ");
        if (count.presence() == MetricPresence.ABSENT) {
            output.append("not yet exercised");
        } else {
            output.append(count.value());
        }
        output.append('\n');
    }

    private void appendDuration(StringBuilder output, DurationSummary duration) {
        output.append("Average search duration: ");
        if (duration.presence() == MetricPresence.ABSENT) {
            output.append("not yet exercised");
        } else {
            output.append(duration.averageNanos()).append(" ns");
        }
        output.append('\n');
    }
}
