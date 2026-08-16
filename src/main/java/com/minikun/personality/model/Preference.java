package com.minikun.personality.model;

import java.time.Instant;
import java.time.Duration;
import java.util.Objects;

public record Preference(String ownerId, String key, String value, double confidence, Instant updatedAt) {
    public Preference {
        ownerId = require(ownerId, "ownerId");
        key = require(key, "key");
        value = require(value, "value");
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public boolean activeAt(Instant now, Duration maxAge, double minimumConfidence) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(maxAge, "maxAge");
        return confidence >= minimumConfidence && !updatedAt.plus(maxAge).isBefore(now);
    }
}
