package com.minikun.memory.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record MinikunEvent(UUID id, Observation observation, Instant publishedAt) {
    public MinikunEvent {
        id = id == null ? UUID.randomUUID() : id;
        observation = java.util.Objects.requireNonNull(observation, "observation");
        publishedAt = publishedAt == null ? Instant.now() : publishedAt;
    }

    public static MinikunEvent from(Observation observation) {
        return new MinikunEvent(null, observation, null);
    }
}
