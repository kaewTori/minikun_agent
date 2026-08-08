package com.minikun.pcs;

import java.util.List;

public final class DefaultKnowledgeRankingService implements KnowledgeRankingService {
    @Override
    public List<KnowledgeRanking> rank(String userRequest, List<KnowledgeCandidate> candidates) {
        return candidates.stream()
                .map(candidate -> new KnowledgeRanking(candidate.candidateId(), 0.0d))
                .toList();
    }
}
