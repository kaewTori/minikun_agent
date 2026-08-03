package com.minikun.memory;

import java.util.List;

import com.minikun.memory.model.Memory;
import com.minikun.memory.model.AcceptedMemory;

public interface MemoryRepository {
    boolean save(AcceptedMemory memory);

    default boolean persist(AcceptedMemory memory) {
        return save(memory);
    }

    default List<Memory> findAll() {
        throw new UnsupportedOperationException("memory retrieval is not implemented");
    }
}
