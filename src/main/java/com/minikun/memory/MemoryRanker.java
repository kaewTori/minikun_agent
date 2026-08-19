package com.minikun.memory;

import java.util.List;

import com.minikun.memory.model.Memory;

@FunctionalInterface
public interface MemoryRanker {
    List<Memory> rank(List<Memory> memories, String query, int limit);
}
