package com.minikun.pcs;

import java.util.List;
import java.util.Objects;

public final class DefaultKnowledgeRelevanceService implements KnowledgeRelevanceService {
    private final KnowledgeRelevancePolicy policy;

    public DefaultKnowledgeRelevanceService() {
        this(KnowledgeRelevancePolicy.DISABLED);
    }

    public DefaultKnowledgeRelevanceService(KnowledgeRelevancePolicy policy) {
        this.policy = Objects.requireNonNull(policy, "relevance policy must not be null");
    }

    @Override
    public List<KnowledgeRelevance> evaluate(String userRequest, List<KnowledgeCandidate> candidates) {
        Objects.requireNonNull(candidates, "candidates must not be null");
        return candidates.stream()
                .map(candidate -> relevanceFor(candidate))
                .toList();
    }

    private KnowledgeRelevance relevanceFor(KnowledgeCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        double score = 1.0d;
        KnowledgeRelevanceDecision decision = policy.accepts(score)
                ? KnowledgeRelevanceDecision.RELEVANT
                : KnowledgeRelevanceDecision.IRRELEVANT;
        return new KnowledgeRelevance(candidate.candidateId(), score, decision);
    }
}