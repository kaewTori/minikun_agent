package com.minikun.memory.model;

import java.util.Objects;

public record AcceptedMemory(
        String conversationId,
        MemoryCategory category,
        String content,
        double confidence,
    String reason,
    MemorySource source) {
    public AcceptedMemory {
        Objects.requireNonNull(conversationId, "conversation id must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
    }
}