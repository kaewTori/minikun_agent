package com.minikun.pcs;

import java.util.List;
import java.util.Objects;

public record KnowledgeConsolidation(
        List<KnowledgeCandidate> selectedCandidates,
        List<KnowledgeProvenance> provenance,
        List<KnowledgeRelation> relations,
        List<KnowledgeConflict> conflicts) {
    public static final KnowledgeConsolidation EMPTY = new KnowledgeConsolidation(
            List.of(), List.of(), List.of(), List.of());

    public KnowledgeConsolidation {
        selectedCandidates = immutableList(selectedCandidates, "selected candidates");
        provenance = immutableList(provenance, "provenance");
        relations = immutableList(relations, "relations");
        conflicts = immutableList(conflicts, "conflicts");
        if (provenance.size() != selectedCandidates.size()) {
            throw new IllegalArgumentException("provenance must contain every selected candidate");
        }
    }

    private static <T> List<T> immutableList(List<T> values, String name) {
        Objects.requireNonNull(values, name + " must not be null");
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(name + " must not contain null");
        }
        return List.copyOf(values);
    }
}