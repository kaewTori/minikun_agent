package com.minikun.memory.event;

import java.time.Instant;
import java.util.Map;

public record Observation(
        ObservationType type,
        ObservationSource source,
        String ownerId,
        String conversationId,
        Instant occurredAt,
        Map<String, String> attributes) {
    public Observation {
        if (type == null || source == null || occurredAt == null) {
            throw new IllegalArgumentException("observation type, source and timestamp are required");
        }
        ownerId = ownerId == null || ownerId.isBlank() ? null : ownerId.trim();
        conversationId = conversationId == null || conversationId.isBlank() ? null : conversationId.trim();
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
