package com.minikun.memory.model;

import java.time.Instant;
import java.util.Objects;

public record Memory(
        MemoryId id,
        MemoryCategory category,
        MemorySource source,
        String content,
        Instant createdAt) {
    public Memory {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(createdAt, "created at must not be null");
        if (content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
    }
}
