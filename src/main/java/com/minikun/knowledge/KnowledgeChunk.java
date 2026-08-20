package com.minikun.knowledge;

import java.util.UUID;

public record KnowledgeChunk(
        UUID id,
        UUID sourceId,
        String root,
        String path,
        String sourceName,
        int index,
        String heading,
        String content,
        String embedding,
        String embeddingModel) {
}
