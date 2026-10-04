package com.minikun.pcs;

import java.time.Instant;
import java.util.Objects;

public record KnowledgeCandidate(
        String candidateId,
        KnowledgeSource source,
        String content,
        int sourcePosition,
        String provenance,
        Instant publishedAt,
        double providerScore) {
    public KnowledgeCandidate(String candidateId, KnowledgeSource source, String content, int sourcePosition) {
        this(candidateId, source, content, sourcePosition, "", null, 0.0);
    }

    public KnowledgeCandidate(
            String candidateId, KnowledgeSource source, String content, int sourcePosition, String provenance) {
        this(candidateId, source, content, sourcePosition, provenance, null, 0.0);
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
        if (Double.isNaN(providerScore) || providerScore < 0.0 || providerScore > 1.0) {
            throw new IllegalArgumentException("provider score must be between 0 and 1");
        }
    }
}
