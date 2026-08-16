package com.minikun.memory;

public record LongTermMemoryScope(String ownerId) {
    public LongTermMemoryScope {
        if (ownerId == null || ownerId.isBlank() || "*".equals(ownerId)) {
            throw new IllegalArgumentException("owner id must not be blank or wildcard");
        }
        ownerId = ownerId.trim();
    }
}
