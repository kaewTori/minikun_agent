package com.minikun.pcs;

import com.minikun.pcs.model.KnowledgeContext;

import java.util.List;
import java.util.Objects;

public record KnowledgeSelection(List<KnowledgeCandidate> selectedCandidates, boolean rankingFallback) {
    public static final KnowledgeSelection EMPTY = new KnowledgeSelection(List.of(), false);

    public KnowledgeSelection {
        Objects.requireNonNull(selectedCandidates, "selected candidates must not be null");
        selectedCandidates = List.copyOf(selectedCandidates);
        if (selectedCandidates.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("selected candidates must not contain null");
        }
    }

    public KnowledgeContext knowledgeContext() {
        return new KnowledgeContext(selectedCandidates.stream()
                .map(KnowledgeCandidate::content)
                .reduce((left, right) -> left + "\n" + right)
                .orElse(""));
    }
}
