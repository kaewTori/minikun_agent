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

    default KnowledgeSelection select(
            String userRequest,
            KnowledgeContext memoryKnowledge,
            KnowledgeContext personalKnowledge,
            KnowledgeContext searchKnowledge,
            List<KnowledgeCandidate> browserCandidates) {
        KnowledgeSelection base = select(userRequest, memoryKnowledge, searchKnowledge, browserCandidates);
        if (personalKnowledge == null || personalKnowledge.candidates().isEmpty()) return base;
        java.util.ArrayList<KnowledgeCandidate> selected = new java.util.ArrayList<>(base.selectedCandidates());
        selected.addAll(personalKnowledge.candidates());
        return new KnowledgeSelection(selected, base.rankingFallback(), base.images());
    }

    private static List<KnowledgeCandidate> candidatesFrom(KnowledgeContext knowledge) {
        if (knowledge == null || knowledge.content().isBlank()) {
            return List.of();
        }
        return knowledge.candidates();
    }
}
