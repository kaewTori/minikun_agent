package com.minikun.pcs;

import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.pcs.model.ImageSource;

import java.util.List;
import java.util.Objects;

public record KnowledgeSelection(
        List<KnowledgeCandidate> selectedCandidates,
        boolean rankingFallback,
        List<ImageSource> images) {
    public static final KnowledgeSelection EMPTY = new KnowledgeSelection(List.of(), false, List.of());

    public KnowledgeSelection(List<KnowledgeCandidate> selectedCandidates, boolean rankingFallback) {
        this(selectedCandidates, rankingFallback, List.of());
    }

    public KnowledgeSelection {
        Objects.requireNonNull(selectedCandidates, "selected candidates must not be null");
        selectedCandidates = List.copyOf(selectedCandidates);
        if (selectedCandidates.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("selected candidates must not contain null");
        }
        images = images == null ? List.of() : List.copyOf(images);
        if (images.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("images must not contain null");
        }
    }

    public KnowledgeContext knowledgeContext() {
        String content = selectedCandidates.stream()
                .map(KnowledgeCandidate::content)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        return new KnowledgeContext(content, selectedCandidates, images);
    }
}
