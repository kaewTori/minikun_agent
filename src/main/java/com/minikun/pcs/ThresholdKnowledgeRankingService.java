package com.minikun.pcs;

import java.util.List;
import java.util.Objects;

/** Runs an expensive ranker only when the candidate set is large enough to justify it. */
public final class ThresholdKnowledgeRankingService implements KnowledgeRankingService {
    private final KnowledgeRankingService delegate;
    private final KnowledgeRankingService fallback;
    private final int minimumCandidates;

    public ThresholdKnowledgeRankingService(
            KnowledgeRankingService delegate, int minimumCandidates) {
        this(delegate, new DefaultKnowledgeRankingService(), minimumCandidates);
    }

    public ThresholdKnowledgeRankingService(
            KnowledgeRankingService delegate,
            KnowledgeRankingService fallback,
            int minimumCandidates) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.fallback = Objects.requireNonNull(fallback, "fallback must not be null");
        if (minimumCandidates < 1) {
            throw new IllegalArgumentException("minimum candidates must be positive");
        }
        this.minimumCandidates = minimumCandidates;
    }

    @Override
    public List<KnowledgeRanking> rank(String userRequest, List<KnowledgeCandidate> candidates) {
        return candidates == null || candidates.size() < minimumCandidates
                ? fallback.rank(userRequest, candidates)
                : delegate.rank(userRequest, candidates);
    }
}
