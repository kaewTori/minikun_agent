package com.minikun.memory;

import java.util.List;

import com.minikun.memory.model.Memory;

public interface MemoryRepository {
    boolean save(Memory memory, String fingerprint, String conversationId);

    default List<Memory> findAll() {
        throw new UnsupportedOperationException("memory retrieval is not implemented");
    }
}
