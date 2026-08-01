package com.minikun.memory;

import com.minikun.memory.model.Memory;

public interface MemoryRepository {
    boolean save(Memory memory, String fingerprint, String conversationId);
}
