package com.minikun.memory;

import com.minikun.memory.model.AcceptedMemory;
import com.minikun.memory.model.Memory;
import com.minikun.memory.model.MemoryId;
import com.minikun.memory.model.MemoryUpdate;

import java.util.List;

public interface MemoryRepository {
    boolean save(AcceptedMemory memory);

    default boolean persist(AcceptedMemory memory) {
        return save(memory);
    }

    default List<Memory> find(MemoryScope scope, int limit) {
        throw new UnsupportedOperationException("memory retrieval is not implemented");
    }

    default List<Memory> findByOwner(String ownerId, int limit) {
        throw new UnsupportedOperationException("owner memory listing is not implemented");
    }

    default List<Memory> findLongTerm(LongTermMemoryScope scope, int limit) {
        return findByOwner(scope.ownerId(), limit).stream().filter(memory -> memory.currentAt(java.time.Instant.now())).toList();
    }

    default List<Memory> findLongTerm(LongTermMemoryScope scope, String query, int limit) {
        return findLongTerm(scope, limit);
    }

    default boolean deleteByOwner(String ownerId, MemoryId memoryId) {
        throw new UnsupportedOperationException("memory deletion is not implemented");
    }

    default boolean updateByOwner(String ownerId, MemoryId memoryId, MemoryUpdate update) {
        throw new UnsupportedOperationException("memory update is not implemented");
    }

    default int deleteAllByOwner(String ownerId) {
        throw new UnsupportedOperationException("owner memory deletion is not implemented");
    }
}
