package com.minikun.memory;

import com.minikun.memory.model.CandidateMemory;

public record MemoryPolicy(
        double minimumConfidence,
        int maximumContentLength) {
    public MemoryPolicy {
        if (!Double.isFinite(minimumConfidence) || minimumConfidence < 0.0 || minimumConfidence > 1.0) {
            throw new IllegalArgumentException("minimum confidence must be between 0 and 1");
        }
        if (maximumContentLength < 1) {
            throw new IllegalArgumentException("maximum content length must be positive");
        }
    }

    public static MemoryPolicy defaults() {
        return new MemoryPolicy(0.7, 500);
    }

    public boolean accepts(CandidateMemory candidate) {
        return candidate.confidence() >= minimumConfidence
                && candidate.content().length() <= maximumContentLength
                && !isTemporary(candidate.content());
    }

    private boolean isTemporary(String content) {
        String normalized = content.toLowerCase(java.util.Locale.ROOT);
        return normalized.matches(".*\\b(today|tonight|this week|for now|ชั่วคราว|วันนี้|คืนนี้)\\b.*");
    }
}
