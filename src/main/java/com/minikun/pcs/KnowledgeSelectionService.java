package com.minikun.pcs;

import com.minikun.pcs.model.KnowledgeContext;

import java.util.List;

public interface KnowledgeSelectionService {
    KnowledgeSelection select(
            String userRequest,
            List<KnowledgeCandidate> memoryCandidates,
            List<KnowledgeCandidate> searchCandidates);

    default KnowledgeSelection select(
            String userRequest,
            List<KnowledgeCandidate> memoryCandidates,
            List<KnowledgeCandidate> searchCandidates,
            List<KnowledgeCandidate> browserCandidates) {
        return select(userRequest, memoryCandidates, searchCandidates);
    }

    default KnowledgeSelection select(
            String userRequest,
            KnowledgeContext memoryKnowledge,
            KnowledgeContext searchKnowledge,
            List<KnowledgeCandidate> browserCandidates) {
        return select(
                userRequest,
                candidatesFrom(memoryKnowledge),
                candidatesFrom(searchKnowledge),
                browserCandidates);
    }

    private static List<KnowledgeCandidate> candidatesFrom(KnowledgeContext knowledge) {
        if (knowledge == null || knowledge.content().isBlank()) {
            return List.of();
        }
        return knowledge.candidates();
    }
}
