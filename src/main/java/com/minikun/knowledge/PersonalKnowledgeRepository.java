package com.minikun.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PersonalKnowledgeRepository {
    Optional<KnowledgeSourceRecord> find(String ownerId, String root, String path);
    UUID begin(String ownerId, String root, String path, String name);
    void replace(UUID sourceId, String hash, Instant modifiedAt, Instant indexedAt,
            List<KnowledgeChunkDraft> chunks);
    void fail(UUID sourceId, Instant indexedAt, String error);
    List<KnowledgeSourceRecord> list(String ownerId, int limit);
    List<KnowledgeChunk> chunks(String ownerId, int limit);
    boolean delete(String ownerId, UUID sourceId);
    int sourceCount(String ownerId);
    int chunkCount(String ownerId);
}
