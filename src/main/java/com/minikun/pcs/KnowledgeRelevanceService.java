package com.minikun.pcs;

import java.util.List;

@FunctionalInterface
public interface KnowledgeRelevanceService {
    List<KnowledgeRelevance> evaluate(String userRequest, List<KnowledgeCandidate> candidates);
}