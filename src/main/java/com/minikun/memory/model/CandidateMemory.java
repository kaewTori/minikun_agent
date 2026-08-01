package com.minikun.memory.model;

import java.util.Objects;

public record CandidateMemory(MemoryCategory category, String content, double confidence, String reason) {
    public CandidateMemory {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
    }
}
