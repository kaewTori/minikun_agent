package com.minikun.pcs;

import java.util.Objects;

public record KnowledgeRelation(
        KnowledgeProvenance first,
        KnowledgeProvenance second,
        RelationType type) {
    public enum RelationType {
        DUPLICATE,
        RELATED,
        CONFLICTING,
        INDEPENDENT,
        UNKNOWN
    }

    public KnowledgeRelation {
        Objects.requireNonNull(first, "first provenance must not be null");
        Objects.requireNonNull(second, "second provenance must not be null");
        Objects.requireNonNull(type, "relation type must not be null");
        if (first.equals(second)) {
            throw new IllegalArgumentException("relation must contain two distinct candidates");
        }
    }

    public KnowledgeProvenance candidateA() {
        return first;
    }

    public KnowledgeProvenance candidateB() {
        return second;
    }
}