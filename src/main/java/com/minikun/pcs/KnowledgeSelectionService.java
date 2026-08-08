package com.minikun.pcs;

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
}
