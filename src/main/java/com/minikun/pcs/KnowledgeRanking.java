package com.minikun.pcs;

import java.util.Objects;

public record KnowledgeRanking(String candidateId, double score) {
    public KnowledgeRanking {
        Objects.requireNonNull(candidateId, "candidate id must not be null");
        if (candidateId.isBlank()) {
            throw new IllegalArgumentException("candidate id must not be blank");
        }
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException("ranking score must be finite");
        }
    }
}
