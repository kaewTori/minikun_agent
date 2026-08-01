package com.minikun.memory.model;

import java.util.Objects;
import java.util.UUID;

public record MemoryId(UUID value) {
    public MemoryId {
        Objects.requireNonNull(value, "memory id must not be null");
    }

    public static MemoryId generate() {
        return new MemoryId(UUID.randomUUID());
    }
}
