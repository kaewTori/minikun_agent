package com.minikun.memory.model;

import java.time.Instant;
import java.util.Objects;

/** A grounded fact slot. Null validFrom means the start is unknown, not the recording time. */
public record TemporalFact(String subject, String key, String value, Instant validFrom,
        Instant validTo, Instant recordedAt, String evidence) {
    public TemporalFact {
        subject = text(subject, 255);
        key = text(key, 255);
        value = text(value, 500);
        evidence = text(evidence, 2000);
        Objects.requireNonNull(recordedAt, "recordedAt");
        if (validTo != null && validTo.isBefore(validFrom == null ? recordedAt : validFrom))
            throw new IllegalArgumentException("fact end must follow its start");
    }
    private static String text(String value, int limit) {
        if (value == null || value.isBlank() || value.length() > limit)
            throw new IllegalArgumentException("invalid fact field");
        return value.strip();
    }
}
