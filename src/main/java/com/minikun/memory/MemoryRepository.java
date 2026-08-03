package com.minikun.memory;

import java.util.List;

import com.minikun.memory.model.Memory;

public interface MemoryRepository {
    boolean save(Memory memory, String fingerprint, String conversationId);

    default boolean persist(Memory memory, String conversationId) {
        try {
            String fingerprint = java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(
                            (conversationId + "\u0000" + memory.category() + "\u0000"
                                    + memory.content().trim().replaceAll("\\s+", " ")
                                    .toLowerCase(java.util.Locale.ROOT))
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return save(memory, fingerprint, conversationId);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    default List<Memory> findAll() {
        throw new UnsupportedOperationException("memory retrieval is not implemented");
    }
}
