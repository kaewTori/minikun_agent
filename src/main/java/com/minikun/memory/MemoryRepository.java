package com.minikun.memory;

import com.minikun.memory.model.AcceptedMemory;
import com.minikun.memory.model.Memory;

import java.util.List;

public interface MemoryRepository {
    boolean save(AcceptedMemory memory);

    default boolean persist(AcceptedMemory memory) {
        return save(memory);
    }

    default List<Memory> find(MemoryScope scope, int limit) {
        throw new UnsupportedOperationException("memory retrieval is not implemented");
    }
}
