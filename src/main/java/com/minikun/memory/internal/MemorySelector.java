package com.minikun.memory.internal;

import java.util.List;

import com.minikun.memory.model.Memory;

final class MemorySelector {
    private final int maximumCount;

    MemorySelector(int maximumCount) {
        if (maximumCount < 1) {
            throw new IllegalArgumentException("maximum memory count must be positive");
        }
        this.maximumCount = maximumCount;
    }

    List<Memory> select(List<Memory> memories) {
        if (memories == null || memories.isEmpty()) {
            return List.of();
        }
        return memories.stream()
                .limit(maximumCount)
                .toList();
    }
}