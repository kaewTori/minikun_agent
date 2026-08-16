package com.minikun.memory.management;

import com.minikun.memory.MemoryRepository;
import com.minikun.memory.model.Memory;
import com.minikun.memory.model.MemoryId;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/** Owner-scoped inspection and deletion operations for a personal agent. */
@Service
public final class MemoryManagementService {
    private static final int MAXIMUM_LIST_LIMIT = 500;
    private final MemoryRepository repository;

    public MemoryManagementService(MemoryRepository repository) {
        this.repository = Objects.requireNonNull(repository, "memory repository must not be null");
    }

    public List<Memory> list(String ownerId, int limit) {
        validateOwner(ownerId);
        if (limit < 0 || limit > MAXIMUM_LIST_LIMIT) {
            throw new IllegalArgumentException("memory list limit must be between 0 and " + MAXIMUM_LIST_LIMIT);
        }
        return repository.findByOwner(ownerId, limit);
    }

    public boolean delete(String ownerId, MemoryId memoryId) {
        validateOwner(ownerId);
        return repository.deleteByOwner(ownerId, Objects.requireNonNull(memoryId, "memory id must not be null"));
    }

    public int deleteAll(String ownerId) {
        validateOwner(ownerId);
        return repository.deleteAllByOwner(ownerId);
    }

    private void validateOwner(String ownerId) {
        if (ownerId == null || ownerId.isBlank() || "*".equals(ownerId)) {
            throw new IllegalArgumentException("owner id must not be blank or wildcard");
        }
    }
}
