package com.minikun.pcs;

public record KnowledgeSelectionPolicy(int memoryTopK, int searchTopK) {
    public static final int UNBOUNDED = Integer.MAX_VALUE;
    public static final KnowledgeSelectionPolicy DEFAULT =
            new KnowledgeSelectionPolicy(UNBOUNDED, UNBOUNDED);

    public KnowledgeSelectionPolicy {
        if (memoryTopK < 1 || searchTopK < 1) {
            throw new IllegalArgumentException("top-K values must be positive");
        }
    }
}
