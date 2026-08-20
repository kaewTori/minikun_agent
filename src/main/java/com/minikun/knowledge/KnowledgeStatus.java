package com.minikun.knowledge;

import java.util.List;

public record KnowledgeStatus(
        boolean enabled,
        boolean semanticAvailable,
        String embeddingModel,
        int sources,
        int chunks,
        List<RootStatus> roots,
        boolean localOnly) {
    public record RootStatus(String name, boolean available) {}
}
