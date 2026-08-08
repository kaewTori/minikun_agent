package com.minikun.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "minikun.memory.retrieval")
public record MemoryRetrievalProperties(int maxCandidates) {
    public MemoryRetrievalProperties {
        if (maxCandidates < 0) {
            throw new IllegalArgumentException("memory retrieval max-candidates must not be negative");
        }
    }
}