package com.minikun.memory.internal;

import java.util.Comparator;
import java.util.List;

import com.minikun.memory.model.Memory;

final class MemorySelector {
    private static final Comparator<Memory> RECENT_FIRST = Comparator
            .comparing(Memory::createdAt)
            .reversed()
            .thenComparing(memory -> memory.id().value());

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
                .sorted(RECENT_FIRST)
                .limit(maximumCount)
                .toList();
    }
}