package com.minikun.pcs;

import java.util.List;

public interface KnowledgeConsolidationService {
    KnowledgeConsolidation consolidate(List<KnowledgeCandidate> candidates);
}