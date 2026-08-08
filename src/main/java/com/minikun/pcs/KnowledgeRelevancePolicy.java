package com.minikun.pcs;

public record KnowledgeRelevancePolicy(boolean enabled, double minimumScore) {
    public static final KnowledgeRelevancePolicy DISABLED =
            new KnowledgeRelevancePolicy(false, 0.0d);

    public KnowledgeRelevancePolicy {
        if (!Double.isFinite(minimumScore) || minimumScore < 0.0d || minimumScore > 1.0d) {
            throw new IllegalArgumentException("minimum relevance score must be finite and within 0.0..1.0");
        }
    }

    public boolean accepts(double score) {
        return !enabled || score >= minimumScore;
    }
}