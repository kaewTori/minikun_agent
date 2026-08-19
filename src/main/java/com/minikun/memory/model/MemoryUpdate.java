package com.minikun.memory.model;

import java.util.Objects;

/** Owner-authorized changes to an existing long-term memory. */
public record MemoryUpdate(MemoryCategory category, String content, double confidence, String reason) {
    public MemoryUpdate {
        Objects.requireNonNull(category, "memory category must not be null");
        content = requireText(content, "content");
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        reason = requireText(reason, "reason");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
