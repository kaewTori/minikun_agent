package com.minikun.pcs;

import java.util.Objects;

public record KnowledgeRelevance(
        String candidateId,
        double score,
        KnowledgeRelevanceDecision decision) {
    public KnowledgeRelevance {
        Objects.requireNonNull(candidateId, "candidate id must not be null");
        Objects.requireNonNull(decision, "relevance decision must not be null");
        if (candidateId.isBlank()) {
            throw new IllegalArgumentException("candidate id must not be blank");
        }
        if (!Double.isFinite(score) || score < 0.0d || score > 1.0d) {
            throw new IllegalArgumentException("relevance score must be finite and within 0.0..1.0");
        }
        candidateId = new String(candidateId);
    }
}