package com.minikun.pcs;

import java.util.Objects;

public record KnowledgeCandidate(
        String candidateId,
        KnowledgeSource source,
        String content,
        int sourcePosition,
        String provenance) {
    public KnowledgeCandidate(String candidateId, KnowledgeSource source, String content, int sourcePosition) {
        this(candidateId, source, content, sourcePosition, "");
    }
    public KnowledgeCandidate {
        Objects.requireNonNull(candidateId, "candidate id must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (candidateId.isBlank()) {
            throw new IllegalArgumentException("candidate id must not be blank");
        }
        if (content.isBlank()) {
            throw new IllegalArgumentException("candidate content must not be blank");
        }
        if (sourcePosition < 0) {
            throw new IllegalArgumentException("source position must not be negative");
        }
        provenance = provenance == null ? "" : provenance;
        candidateId = new String(candidateId);
        content = new String(content);
    }
}
