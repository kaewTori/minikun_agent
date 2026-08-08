package com.minikun.memory.model;

import java.time.Instant;
import java.util.Objects;

public record Memory(
    String ownerId,
        MemoryId id,
        MemoryCategory category,
        MemorySource source,
        String content,
        Instant createdAt,
        double confidence,
        String reason) {
    public Memory(MemoryId id, MemoryCategory category, MemorySource source, String content,
            Instant createdAt, double confidence, String reason) {
        this(null, id, category, source, content, createdAt, confidence, reason);
    }

    public Memory {
        if (ownerId != null && (ownerId.isBlank() || "*".equals(ownerId))) {
            throw new IllegalArgumentException("owner id must not be blank or wildcard");
        }
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(createdAt, "created at must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        if (content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }
}
