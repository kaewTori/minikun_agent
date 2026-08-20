package com.minikun.knowledge;

public record KnowledgeChunkDraft(int index, String heading, String content, String contentHash,
        String embedding, String embeddingModel) {
}
