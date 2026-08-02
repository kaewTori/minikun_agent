package com.minikun.search.model;

import java.time.Duration;
import java.util.Objects;

public record SearchMetadata(
        Duration totalDuration,
        boolean timedOut,
        boolean cacheHit,
        int retryCount) {
    public SearchMetadata {
        Objects.requireNonNull(totalDuration, "total duration must not be null");
        if (totalDuration.isNegative()) {
            throw new IllegalArgumentException("total duration must not be negative");
        }
        if (retryCount < 0) {
            throw new IllegalArgumentException("retry count must not be negative");
        }
        if (timedOut && totalDuration.isZero()) {
            throw new IllegalArgumentException("timed out search must have a positive duration");
        }
    }
}