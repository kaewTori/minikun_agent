package com.minikun.diagnostics;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DiagnosticsFormatterTest {

    @Test
    void rendersAbsentAndPresentZeroDifferently() {
        DiagnosticsSummary absent = new DiagnosticsSummary(
                MetricCount.absent(), DurationSummary.absent(),
                new DiagnosticsSummary.CacheSummary(MetricCount.absent(), MetricCount.absent(), MetricCount.absent()),
                MetricCount.absent(), MetricCount.absent(),
                new DiagnosticsSummary.QualitySummary(MetricCount.absent(), MetricCount.absent(), MetricCount.absent()));
        DiagnosticsSummary zero = new DiagnosticsSummary(
                MetricCount.present(0), new DurationSummary(MetricPresence.PRESENT, 0, 0, 0),
                new DiagnosticsSummary.CacheSummary(MetricCount.present(0), MetricCount.present(0), MetricCount.present(0)),
                MetricCount.present(0), MetricCount.present(0),
                new DiagnosticsSummary.QualitySummary(MetricCount.present(0), MetricCount.present(0), MetricCount.present(0)));

        String absentText = new DiagnosticsFormatter().format(absent);
        String zeroText = new DiagnosticsFormatter().format(zero);

        assertTrue(absentText.contains("not yet exercised"));
        assertTrue(!zeroText.contains("not yet exercised"));
        assertTrue(zeroText.contains("Search requests: 0"));
    }
}
