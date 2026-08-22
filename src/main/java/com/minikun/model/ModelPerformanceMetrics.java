package com.minikun.model;

/** Minimal metrics port used by the model layer without depending on an API adapter. */
public interface ModelPerformanceMetrics {
    void record(String stage, long startedNanos, String result);
}
