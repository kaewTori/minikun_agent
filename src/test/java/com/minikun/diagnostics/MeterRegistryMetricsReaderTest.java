package com.minikun.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MeterRegistryMetricsReaderTest {

    @Test
    void absentReadDoesNotCreateMeter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MetricsReader reader = new MeterRegistryMetricsReader(registry);

        var snapshots = reader.read("minikun.search.requests");

        assertEquals(MetricPresence.ABSENT, snapshots.getFirst().presence());
        assertEquals(0, registry.getMeters().size());
    }

    @Test
    void presentZeroCounterIsReportedAsPresent() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.counter("minikun.search.requests");
        MetricsReader reader = new MeterRegistryMetricsReader(registry);

        var snapshots = reader.read("minikun.search.requests");

        assertEquals(MetricPresence.PRESENT, snapshots.getFirst().presence());
        assertEquals(0, snapshots.getFirst().value());
    }

    @Test
    void timerValuesAndTagsAreReadWithoutMutation() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var timer = registry.timer("minikun.search.duration", "provider", "test");
        timer.record(12, TimeUnit.NANOSECONDS);
        int meterCount = registry.getMeters().size();
        MetricsReader reader = new MeterRegistryMetricsReader(registry);

        var first = reader.read("minikun.search.duration");
        var second = reader.read("minikun.search.duration");

        assertEquals("test", first.getFirst().tags().get("provider"));
        assertEquals(1, first.getFirst().count());
        assertEquals(12, first.getFirst().totalNanos());
        assertEquals(first, second);
        assertEquals(meterCount, registry.getMeters().size());
    }
}
