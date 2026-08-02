package com.minikun.diagnostics;

import java.util.List;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Component
public final class MeterRegistryMetricsReader implements MetricsReader {

    private final MeterRegistry meterRegistry;

    public MeterRegistryMetricsReader(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public List<MetricSnapshot> read(String metricName) {
        List<MetricSnapshot> snapshots = meterRegistry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals(metricName))
                .map(this::snapshot)
                .toList();
        return snapshots.isEmpty() ? List.of(MetricSnapshot.absent(metricName)) : snapshots;
    }

    private MetricSnapshot snapshot(Meter meter) {
        String name = meter.getId().getName();
        var tags = meter.getId().getTags().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        tag -> tag.getKey(), tag -> tag.getValue()));
        if (meter instanceof Counter counter) {
            return new MetricSnapshot(name, tags, MetricPresence.PRESENT, counter.count(), 0, 0);
        }
        if (meter instanceof Timer timer) {
            return new MetricSnapshot(name, tags, MetricPresence.PRESENT, 0,
                    timer.count(), Math.round(timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS)));
        }
        return new MetricSnapshot(name, tags, MetricPresence.PRESENT, 0, 0, 0);
    }
}
