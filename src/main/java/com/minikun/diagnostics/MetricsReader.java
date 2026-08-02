package com.minikun.diagnostics;

import java.util.List;

public interface MetricsReader {
    List<MetricSnapshot> read(String metricName);
}
