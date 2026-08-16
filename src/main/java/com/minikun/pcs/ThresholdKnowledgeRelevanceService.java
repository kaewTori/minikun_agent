package com.minikun.pcs;

import java.util.List;
import java.util.Objects;

/** Runs an expensive relevance model only for sufficiently large candidate sets. */
public final class ThresholdKnowledgeRelevanceService implements KnowledgeRelevanceService {
    private final KnowledgeRelevanceService delegate;
    private final KnowledgeRelevanceService fallback;
    private final int minimumCandidates;

    public ThresholdKnowledgeRelevanceService(
            KnowledgeRelevanceService delegate, KnowledgeRelevanceService fallback,
            int minimumCandidates) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.fallback = Objects.requireNonNull(fallback, "fallback must not be null");
        if (minimumCandidates < 1) {
            throw new IllegalArgumentException("minimum candidates must be positive");
        }
        this.minimumCandidates = minimumCandidates;
    }

    @Override
    public List<KnowledgeRelevance> evaluate(String userRequest, List<KnowledgeCandidate> candidates) {
        return candidates == null || candidates.size() < minimumCandidates
                ? fallback.evaluate(userRequest, candidates)
                : delegate.evaluate(userRequest, candidates);
    }
}
