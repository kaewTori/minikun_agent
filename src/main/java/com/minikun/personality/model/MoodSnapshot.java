package com.minikun.personality.model;

import java.time.Instant;

public record MoodSnapshot(Mood mood, double intensity, Instant observedAt, String reason) {
    public static final MoodSnapshot DEFAULT = new MoodSnapshot(Mood.CALM, 0, Instant.EPOCH, "default");

    public MoodSnapshot {
        mood = mood == null ? Mood.CALM : mood;
        if (!Double.isFinite(intensity) || intensity < 0 || intensity > 1) {
            throw new IllegalArgumentException("intensity must be between 0 and 1");
        }
        observedAt = observedAt == null ? Instant.EPOCH : observedAt;
        reason = reason == null || reason.isBlank() ? "unspecified" : reason.trim();
    }

    public boolean active() { return intensity > 0; }
}
