package com.minikun.pcs;

import java.util.List;

@FunctionalInterface
public interface KnowledgeRankingService {
    List<KnowledgeRanking> rank(String userRequest, List<KnowledgeCandidate> candidates);
}
