package com.minikun.pcs;

import java.util.Objects;

public record KnowledgeConflict(
        KnowledgeProvenance first,
        KnowledgeProvenance second,
        String evidence) {
    public KnowledgeConflict {
        Objects.requireNonNull(first, "first provenance must not be null");
        Objects.requireNonNull(second, "second provenance must not be null");
        Objects.requireNonNull(evidence, "evidence must not be null");
        if (first.equals(second)) {
            throw new IllegalArgumentException("conflict must contain two distinct candidates");
        }
        if (evidence.isBlank()) {
            throw new IllegalArgumentException("evidence must not be blank");
        }
    }
}
