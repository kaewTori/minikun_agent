package com.minikun.knowledge;

import java.util.UUID;

public record KnowledgeSearchResult(
        UUID sourceId,
        String source,
        String root,
        String path,
        int chunk,
        String heading,
        String content,
        String citation,
        double score,
        boolean semantic) {
}
