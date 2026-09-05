package com.minikun.pcs;

import java.util.Objects;

public record KnowledgeProvenance(
        String candidateId,
        KnowledgeSource source,
        int sourcePosition,
        int originalCandidateOrder) {
    public KnowledgeProvenance {
        Objects.requireNonNull(candidateId, "candidate id must not be null");
        Objects.requireNonNull(source, "source must not be null");
        if (candidateId.isBlank()) {
            throw new IllegalArgumentException("candidate id must not be blank");
        }
        if (sourcePosition < 0) {
            throw new IllegalArgumentException("source position must not be negative");
        }
        if (originalCandidateOrder < 0) {
            throw new IllegalArgumentException("original candidate order must not be negative");
        }
    }

    public static KnowledgeProvenance from(KnowledgeCandidate candidate, int order) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        return new KnowledgeProvenance(
            candidate.candidateId(), candidate.source(), candidate.sourcePosition(), order);
    }
}
