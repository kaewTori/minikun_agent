package com.minikun.pcs;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class DefaultKnowledgeConsolidationService implements KnowledgeConsolidationService {
    @Override
    public KnowledgeConsolidation consolidate(List<KnowledgeCandidate> candidates) {
        List<KnowledgeCandidate> selected = candidates == null ? List.of() : List.copyOf(candidates);
        if (selected.isEmpty()) {
            return KnowledgeConsolidation.EMPTY;
        }

        List<KnowledgeProvenance> provenance = new ArrayList<>(selected.size());
        for (int index = 0; index < selected.size(); index++) {
            KnowledgeCandidate candidate = Objects.requireNonNull(
                    selected.get(index), "candidates must not contain null");
            provenance.add(KnowledgeProvenance.from(candidate, index));
        }

        List<KnowledgeRelation> relations = new ArrayList<>();
        for (int first = 0; first < selected.size(); first++) {
            for (int second = first + 1; second < selected.size(); second++) {
                KnowledgeRelation.RelationType type = selected.get(first).content()
                        .equals(selected.get(second).content())
                        ? KnowledgeRelation.RelationType.DUPLICATE
                        : KnowledgeRelation.RelationType.UNKNOWN;
                relations.add(new KnowledgeRelation(
                        provenance.get(first), provenance.get(second), type));
            }
        }
        return new KnowledgeConsolidation(selected, provenance, relations, List.of());
    }
}