package com.minikun.pcs;

import java.util.Objects;

public record KnowledgeSelectionPolicy(SourcePolicy memory, SourcePolicy search) {
    public static final int UNBOUNDED = Integer.MAX_VALUE;
    public static final KnowledgeSelectionPolicy DEFAULT = new KnowledgeSelectionPolicy(
            SourcePolicy.UNBOUNDED,
            SourcePolicy.UNBOUNDED);

    public KnowledgeSelectionPolicy {
        Objects.requireNonNull(memory, "memory policy must not be null");
        Objects.requireNonNull(search, "search policy must not be null");
    }

    public KnowledgeSelectionPolicy(int memoryTopK, int searchTopK) {
        this(new SourcePolicy(true, memoryTopK, UNBOUNDED),
                new SourcePolicy(true, searchTopK, UNBOUNDED));
    }

    public int memoryTopK() {
        return memory.maxCandidates();
    }

    public int searchTopK() {
        return search.maxCandidates();
    }

    public record SourcePolicy(boolean enabled, int maxCandidates, int maxCharacters) {
        private static final SourcePolicy UNBOUNDED =
                new SourcePolicy(true, KnowledgeSelectionPolicy.UNBOUNDED,
                        KnowledgeSelectionPolicy.UNBOUNDED);

        public SourcePolicy {
            if (maxCandidates < 0) {
                throw new IllegalArgumentException("max candidates must not be negative");
            }
            if (maxCharacters < 0) {
                throw new IllegalArgumentException("max characters must not be negative");
            }
        }
    }
}
