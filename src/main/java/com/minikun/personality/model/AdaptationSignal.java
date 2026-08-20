package com.minikun.personality.model;

import java.time.Instant;
import java.util.Objects;

/** Aggregated owner-scoped evidence for one adaptive response-style candidate. */
public record AdaptationSignal(
        String ownerId,
        String dimension,
        String value,
        int observations,
        int explicitObservations,
        double score,
        Instant updatedAt) {

    public AdaptationSignal {
        ownerId = require(ownerId, "ownerId");
        dimension = require(dimension, "dimension");
        value = require(value, "value");
        if (observations < 0 || explicitObservations < 0 || explicitObservations > observations) {
            throw new IllegalArgumentException("adaptation observation counts are invalid");
        }
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException("adaptation score must be finite");
        }
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
