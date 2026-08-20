package com.minikun.knowledge;

import java.time.Instant;
import java.util.UUID;

public record KnowledgeSourceRecord(
        UUID id,
        String ownerId,
        String root,
        String path,
        String name,
        String status,
        String fileHash,
        Instant modifiedAt,
        Instant indexedAt,
        int chunkCount,
        String lastError) {
}
